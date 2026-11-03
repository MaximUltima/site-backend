CREATE TABLE content_versions (
    id          bigint PRIMARY KEY,
    kind        varchar(32) NOT NULL,
    slug        varchar(120) NOT NULL,
    lang        varchar(16) NOT NULL,
    status      varchar(16) NOT NULL CHECK (status IN ('draft', 'published', 'unpublished')),
    data        text NOT NULL,
    actor       text,
    created_at  text NOT NULL,
    prev_hash   varchar(64) NOT NULL,
    hash        varchar(64) NOT NULL
);
CREATE INDEX content_versions_doc ON content_versions (kind, slug, lang, id DESC);
CREATE INDEX content_versions_live ON content_versions (kind, lang, slug, id DESC, status);

CREATE TABLE content_chain_state (
    id        integer PRIMARY KEY CHECK (id = 1),
    next_id   bigint NOT NULL,
    last_hash varchar(64) NOT NULL
);
INSERT INTO content_chain_state (id, next_id, last_hash) VALUES (1, 1, '');

CREATE OR REPLACE FUNCTION content_version_chain_insert() RETURNS trigger AS $$
DECLARE state record;
BEGIN
    SELECT next_id, last_hash INTO state FROM content_chain_state WHERE id = 1;
    IF NEW.id <> state.next_id OR NEW.prev_hash <> state.last_hash OR NEW.hash IS NULL OR length(NEW.hash) <> 64 THEN
        RAISE EXCEPTION 'content_versions: broken chain';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER content_versions_chain BEFORE INSERT ON content_versions
    FOR EACH ROW EXECUTE FUNCTION content_version_chain_insert();

CREATE OR REPLACE FUNCTION content_version_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'content_versions: append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER content_versions_no_update BEFORE UPDATE ON content_versions
    FOR EACH ROW EXECUTE FUNCTION content_version_append_only();
CREATE TRIGGER content_versions_no_delete BEFORE DELETE ON content_versions
    FOR EACH ROW EXECUTE FUNCTION content_version_append_only();

CREATE TABLE content_schedule (
    id            bigserial PRIMARY KEY,
    version_id    bigint NOT NULL REFERENCES content_versions(id),
    kind          varchar(32) NOT NULL,
    slug          varchar(120) NOT NULL,
    lang          varchar(16) NOT NULL,
    publish_at    text NOT NULL,
    by_actor      varchar(32) NOT NULL,
    created_at    text NOT NULL,
    state         varchar(16) NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'done', 'canceled')),
    done_version  bigint REFERENCES content_versions(id),
    done_at       text
);
CREATE UNIQUE INDEX content_schedule_one ON content_schedule (kind, slug, lang) WHERE state = 'pending';
CREATE INDEX content_schedule_due ON content_schedule (state, publish_at);

CREATE TABLE download_files (
    id            bigserial PRIMARY KEY,
    sha256        varchar(64) NOT NULL UNIQUE,
    size          bigint NOT NULL,
    ext           varchar(8) NOT NULL,
    name          varchar(80) NOT NULL,
    license       varchar(32),
    source        varchar(500),
    uploaded_by   varchar(32) NOT NULL,
    created_at    text NOT NULL,
    status        varchar(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'hidden')),
    hidden_reason varchar(200)
);

CREATE TABLE stats_daily (
    kind    varchar(16) NOT NULL,
    slug    varchar(120) NOT NULL,
    lang    varchar(16) NOT NULL,
    day     date NOT NULL,
    views   bigint NOT NULL DEFAULT 0,
    reads   bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (kind, slug, lang, day)
);
CREATE INDEX stats_daily_day ON stats_daily (day);

CREATE TABLE stats_downloads (
    file_id   bigint NOT NULL,
    day       date NOT NULL,
    downloads bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (file_id, day)
);
