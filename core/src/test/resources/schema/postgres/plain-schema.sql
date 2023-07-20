DROP TABLE IF EXISTS public.journal;

CREATE TABLE IF NOT EXISTS public.journal
(
    ordering        BIGSERIAL,
    sequence_number BIGINT                NOT NULL,
    deleted         BOOLEAN DEFAULT FALSE NOT NULL,
    persistence_id  TEXT                  NOT NULL,
    message         BYTEA                 NOT NULL,
    tags            int[],
    metadata        jsonb                 NOT NULL,
    PRIMARY KEY (persistence_id, sequence_number)
);

CREATE EXTENSION IF NOT EXISTS intarray WITH SCHEMA public;
CREATE INDEX journal_tags_idx ON public.journal USING GIN (tags public.gin__int_ops);
CREATE INDEX journal_ordering_idx ON public.journal USING BRIN (ordering);

DROP TABLE IF EXISTS public.tags;

CREATE TABLE IF NOT EXISTS public.tags
(
    id              BIGSERIAL,
    name            TEXT                        NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS tags_name_idx on public.tags (name);

DROP TABLE IF EXISTS public.snapshot;

CREATE TABLE IF NOT EXISTS public.snapshot
(
    persistence_id  TEXT   NOT NULL,
    sequence_number BIGINT NOT NULL,
    created         BIGINT NOT NULL,
    snapshot        BYTEA  NOT NULL,
    metadata        jsonb  NOT NULL,
    PRIMARY KEY (persistence_id, sequence_number)
);

DROP TRIGGER IF EXISTS trig_check_persistence_id_max_sequence_number ON public.journal_metadata;
DROP FUNCTION IF EXISTS public.check_persistence_id_max_sequence_number();
DROP TRIGGER IF EXISTS trig_update_journal_metadata ON public.journal;
DROP FUNCTION IF EXISTS public.update_journal_metadata();
DROP TABLE IF EXISTS public.journal_metadata;

CREATE TABLE public.journal_metadata(
  id BIGINT GENERATED ALWAYS AS IDENTITY,
  persistence_id TEXT NOT NULL,
  max_sequence_number BIGINT NOT NULL,
  min_ordering BIGINT NOT NULL,
  max_ordering BIGINT NOT NULL,
  PRIMARY KEY (persistence_id)
) PARTITION BY HASH(persistence_id);

CREATE TABLE public.journal_metadata_0 PARTITION OF public.journal_metadata FOR VALUES WITH (MODULUS 2, REMAINDER 0);
CREATE TABLE public.journal_metadata_1 PARTITION OF public.journal_metadata FOR VALUES WITH (MODULUS 2, REMAINDER 1);

CREATE OR REPLACE FUNCTION public.update_journal_metadata() RETURNS TRIGGER AS
$$
DECLARE
BEGIN
  INSERT INTO public.journal_metadata (persistence_id, max_sequence_number, max_ordering, min_ordering)
  VALUES (NEW.persistence_id, NEW.sequence_number, NEW.ordering, NEW.ordering)
  ON CONFLICT (persistence_id) DO UPDATE
  SET
    max_sequence_number = NEW.sequence_number,
    max_ordering = NEW.ordering,
    min_ordering = LEAST(public.journal_metadata.min_ordering, NEW.ordering);

  RETURN NEW;
END;
$$
LANGUAGE plpgsql;

CREATE TRIGGER trig_update_journal_metadata
  AFTER INSERT ON public.journal
  FOR EACH ROW
  EXECUTE PROCEDURE public.update_journal_metadata();

CREATE OR REPLACE FUNCTION public.check_persistence_id_max_sequence_number() RETURNS TRIGGER AS
$$
DECLARE
BEGIN
  IF NEW.max_sequence_number <= OLD.max_sequence_number THEN
    RAISE EXCEPTION 'New max_sequence_number not higher than previous value';
  END IF;

  RETURN NEW;
END;
$$
LANGUAGE plpgsql;


CREATE TRIGGER trig_check_persistence_id_max_sequence_number
  BEFORE UPDATE ON public.journal_metadata
  FOR EACH ROW
  EXECUTE PROCEDURE public.check_persistence_id_max_sequence_number();
