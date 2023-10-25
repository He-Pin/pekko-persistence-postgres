package akka.persistence.postgres.query.dao

import akka.NotUsed
import akka.persistence.PersistentRepr
import akka.persistence.postgres.config.ReadJournalConfig
import akka.persistence.postgres.journal.dao.{
  ByteArrayJournalSerializer,
  JournalMetadataTable,
  PartitionedJournalTable
}
import akka.persistence.postgres.tag.{ CachedTagIdResolver, SimpleTagDao, TagIdResolver }
import akka.serialization.Serialization
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import slick.jdbc.JdbcBackend.Database

import scala.concurrent.ExecutionContext
import scala.util.Try

class PartitionedReadJournalDao(
    val db: Database,
    val readJournalConfig: ReadJournalConfig,
    serialization: Serialization,
    val tagIdResolver: TagIdResolver)(implicit val ec: ExecutionContext, val mat: Materializer)
    extends BaseByteArrayReadJournalDao {

  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  val queries = new ReadJournalQueries(
    PartitionedJournalTable(readJournalConfig.journalTableConfiguration),
    readJournalConfig.includeDeleted)
  private val metadataQueries: ReadJournalMetadataQueries = new ReadJournalMetadataQueries(
    JournalMetadataTable(readJournalConfig.journalMetadataTableConfiguration))

  val serializer = new ByteArrayJournalSerializer(
    serialization,
    new CachedTagIdResolver(
      new SimpleTagDao(db, readJournalConfig.tagsTableConfiguration),
      readJournalConfig.tagsConfig))

  override def messages(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long,
      max: Long): Source[Try[(PersistentRepr, Long)], NotUsed] = {
    // This behaviour override is only applied here, because it is only useful on the PartitionedJournal strategy.
    val query = if (readJournalConfig.useJournalMetadata) {
      metadataQueries.minAndMaxOrderingForPersistenceId(persistenceId).result.headOption.flatMap {
        case Some((minOrdering, _)) =>
          // if journal_metadata knows the min ordering of a persistenceId,
          // use it to help the query planner to avoid scanning unnecessary partitions.
          queries.messagesMinOrderingBoundedQuery(persistenceId, fromSequenceNr, toSequenceNr, max, minOrdering).result
        case None =>
          // fallback to standard behaviour
          queries.messagesQuery(persistenceId, fromSequenceNr, toSequenceNr, max).result
      }
    } else
      queries.messagesQuery(persistenceId, fromSequenceNr, toSequenceNr, max).result

    Source.fromPublisher(db.stream(query)).via(serializer.deserializeFlow)
  }
}
