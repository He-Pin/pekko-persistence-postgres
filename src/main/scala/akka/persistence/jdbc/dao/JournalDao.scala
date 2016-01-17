/*
 * Copyright 2015 Dennis Vriend
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.persistence.jdbc.dao

import akka.persistence.jdbc.dao.Tables.{ JournalDeletedToRow, JournalRow }
import akka.persistence.jdbc.serialization.Serialized
import akka.persistence.jdbc.util.SlickDriver
import akka.stream.scaladsl._
import akka.stream.{ FlowShape, Materializer }
import slick.driver.JdbcProfile
import slick.jdbc.JdbcBackend

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Try }

object JournalDao {
  /**
   * Factory method
   */
  def apply(driver: String, db: JdbcBackend#Database)(implicit ec: ExecutionContext, mat: Materializer): JournalDao =
    if (SlickDriver.forDriverName.isDefinedAt(driver)) {
      new JdbcSlickJournalDao(db, SlickDriver.forDriverName(driver))
    } else throw new IllegalArgumentException("Unknown slick driver: " + driver)
}

trait JournalDao {

  /**
   * Writes serialized messages
   */
  def writeList(xs: Iterable[Serialized]): Future[Unit]

  /**
   * Writes serialized messages
   */
  def writeFlow: Flow[Try[Iterable[Serialized]], Try[Iterable[Serialized]], Unit]

  /**
   * Deletes all persistent messages up to toSequenceNr (inclusive) for the persistenceId
   */
  def delete(persistenceId: String, toSequenceNr: Long): Future[Unit]

  /**
   * Returns the highest sequence number for the events that are stored for that `persistenceId`. When no events are
   * found for the `persistenceId`, 0L will be the highest sequence number
   */
  def highestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long]

  /**
   * Returns a Source of bytes for a certain persistenceId
   */
  def messages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long): Source[Array[Byte], Unit]

  /**
   * Returns a Set containing all persistenceIds
   */
  def allPersistenceIds: Future[Set[String]]

  /**
   * Returns distinct stream of persistenceIds
   */
  def allPersistenceIdsSource: Source[String, Unit]
}

trait WriteMessagesFacade {
  def writeMessages: Flow[Try[Iterable[Serialized]], Try[Iterable[Serialized]], Unit]
}

class FlowGraphWriteMessagesFacade(journalDao: JournalDao)(implicit ec: ExecutionContext, mat: Materializer) extends WriteMessagesFacade {
  def writeMessages: Flow[Try[Iterable[Serialized]], Try[Iterable[Serialized]], Unit] =
    Flow.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._
      val broadcast = b.add(Broadcast[Try[Iterable[Serialized]]](2))
      val zip = b.add(Zip[Unit, Try[Iterable[Serialized]]]())

      broadcast.out(0).collect {
        case Success(xs) ⇒ xs
      }.mapAsync(1)(journalDao.writeList) ~> zip.in0
      broadcast.out(1) ~> zip.in1

      FlowShape(broadcast.in, zip.out)
    }).map {
      case (x, y) ⇒ y
    }
}

// see: http://slick.typesafe.com/doc/3.1.1/sql-to-slick.html
class SlickJournalDaoQueries(val profile: JdbcProfile) extends Tables {
  import profile.api._

  def insertDeletedTo(persistenceId: String, highestSequenceNr: Option[Long]) =
    DeletedToTable += JournalDeletedToRow(persistenceId, highestSequenceNr.getOrElse(0L))

  def selectAllDeletedTo(persistenceId: String): Query[DeletedTo, JournalDeletedToRow, Seq] =
    DeletedToTable.filter(_.persistenceId === persistenceId)

  def selectAllJournalForPersistenceId(persistenceId: String): Query[Journal, JournalRow, Seq] =
    JournalTable.filter(_.persistenceId === persistenceId).sortBy(_.sequenceNumber.desc)

  def highestSequenceNrForPersistenceId(persistenceId: String): Rep[Option[Long]] =
    selectAllJournalForPersistenceId(persistenceId).map(_.sequenceNumber).max

  def selectByPersistenceIdAndMaxSequenceNumber(persistenceId: String, maxSequenceNr: Long): Query[Journal, JournalRow, Seq] =
    selectAllJournalForPersistenceId(persistenceId).filter(_.sequenceNumber <= maxSequenceNr)

  def highestSequenceNumberFromJournalForPersistenceIdFromSequenceNr(persistenceId: String, fromSequenceNr: Long): Rep[Option[Long]] =
    selectAllJournalForPersistenceId(persistenceId).filter(_.sequenceNumber >= fromSequenceNr).map(_.sequenceNumber).max

  def selectHighestSequenceNrFromDeletedTo(persistenceId: String): Rep[Option[Long]] =
    selectAllDeletedTo(persistenceId).map(_.deletedTo).max

  def allPersistenceIdsDistinct: Query[Rep[String], String, Seq] =
    JournalTable.map(_.persistenceId).distinct
}

trait SlickJournalDao extends JournalDao with Tables {

  import profile.api._

  implicit def ec: ExecutionContext

  implicit def mat: Materializer

  def writeMessagesFacade: WriteMessagesFacade

  def db: JdbcBackend#Database

  def queries: SlickJournalDaoQueries = new SlickJournalDaoQueries(profile)

  def writeList(xs: Iterable[Serialized]): Future[Unit] = for {
    _ ← db.run(JournalTable ++= xs.map(ser ⇒ JournalRow(ser.persistenceId, ser.sequenceNr, ser.serialized.array())))
  } yield ()

  def writeFlow: Flow[Try[Iterable[Serialized]], Try[Iterable[Serialized]], Unit] =
    Flow[Try[Iterable[Serialized]]].via(writeMessagesFacade.writeMessages)

  override def delete(persistenceId: String, maxSequenceNr: Long): Future[Unit] = {
    val actions = (for {
      highestSequenceNr ← queries.highestSequenceNrForPersistenceId(persistenceId).result
      _ ← queries.selectByPersistenceIdAndMaxSequenceNumber(persistenceId, maxSequenceNr).delete
      _ ← queries.insertDeletedTo(persistenceId, highestSequenceNr)
    } yield ()).transactionally
    db.run(actions)
  }

  override def highestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    val actions = (for {
      seqNumFoundInJournalTable ← queries.highestSequenceNumberFromJournalForPersistenceIdFromSequenceNr(persistenceId, fromSequenceNr).result
      highestSeqNumberFoundInDeletedToTable ← queries.selectHighestSequenceNrFromDeletedTo(persistenceId).result
      highestSequenceNumber = seqNumFoundInJournalTable.getOrElse(highestSeqNumberFoundInDeletedToTable.getOrElse(0L))
    } yield highestSequenceNumber).transactionally
    db.run(actions)
  }

  override def messages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long): Source[Array[Byte], Unit] = {
    Source.fromPublisher(
      db.stream(JournalTable
        .filter(_.persistenceId === persistenceId)
        .filter(_.sequenceNumber >= fromSequenceNr)
        .filter(_.sequenceNumber <= toSequenceNr)
        .sortBy(_.sequenceNumber.asc)
        .take(max)
        .result))
      .map(_.message)
  }

  override def allPersistenceIds: Future[Set[String]] = for {
    ids ← db.run(queries.allPersistenceIdsDistinct.result)
  } yield ids.toSet

  override def allPersistenceIdsSource: Source[String, Unit] =
    Source.fromPublisher(db.stream(queries.allPersistenceIdsDistinct.result))
}

class JdbcSlickJournalDao(val db: JdbcBackend#Database, override val profile: JdbcProfile)(implicit val ec: ExecutionContext, val mat: Materializer) extends SlickJournalDao {
  override val writeMessagesFacade: WriteMessagesFacade = new FlowGraphWriteMessagesFacade(this)
}