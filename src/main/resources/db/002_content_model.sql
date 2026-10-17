-- Модель контента по контракту docs/api.md (этап 20).
-- Статьи переделываются на месте, чтобы не потерять то, что уже лежит в базе.

-- Статьи
ALTER TABLE articles RENAME COLUMN content TO body;
ALTER TABLE articles RENAME COLUMN publish_date TO published_at;
ALTER TABLE articles ALTER COLUMN published_at TYPE timestamptz USING published_at AT TIME ZONE 'Europe/Moscow';
ALTER TABLE articles ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'Europe/Moscow';
ALTER TABLE articles ALTER COLUMN updated_at TYPE timestamptz USING updated_at AT TIME ZONE 'Europe/Moscow';
UPDATE articles SET body = '' WHERE body IS NULL;
ALTER TABLE articles ALTER COLUMN body SET NOT NULL;
ALTER TABLE articles ALTER COLUMN body SET DEFAULT '';
ALTER TABLE articles
    ADD COLUMN slug varchar(200),
    ADD COLUMN announce text NOT NULL DEFAULT '',
    ADD COLUMN summary text,
    ADD COLUMN reference_list text,
    ADD COLUMN tags text,
    ADD COLUMN cover text,
    ADD COLUMN status varchar(16) NOT NULL DEFAULT 'PUBLISHED',
    ADD COLUMN source_url varchar(1000);
UPDATE articles SET slug = 'article-' || id WHERE slug IS NULL;
ALTER TABLE articles ALTER COLUMN slug SET NOT NULL;
ALTER TABLE articles ALTER COLUMN status SET DEFAULT 'DRAFT';
ALTER TABLE articles ADD CONSTRAINT articles_slug_key UNIQUE (slug);
ALTER TABLE articles ADD CONSTRAINT articles_source_url_key UNIQUE (source_url);
CREATE INDEX articles_feed_idx ON articles (status, published_at DESC);

-- Новости
CREATE TABLE news (
    id           bigserial PRIMARY KEY,
    slug         varchar(200)  NOT NULL UNIQUE,
    title        varchar(256)  NOT NULL,
    announce     text          NOT NULL DEFAULT '',
    lead         text,
    body         text          NOT NULL DEFAULT '',
    cover        text,
    status       varchar(16)   NOT NULL DEFAULT 'DRAFT',
    published_at timestamptz   NOT NULL DEFAULT now(),
    source_url   varchar(1000) UNIQUE,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz
);
CREATE INDEX news_feed_idx ON news (status, published_at DESC);

-- Выпуски газеты
CREATE TABLE newspaper_issues (
    id           bigserial PRIMARY KEY,
    slug         varchar(200)  NOT NULL UNIQUE,
    number       varchar(32)   NOT NULL,
    title        varchar(256)  NOT NULL,
    contents     text,
    cover        text,
    pdf_url      varchar(1000),
    status       varchar(16)   NOT NULL DEFAULT 'DRAFT',
    published_at timestamptz   NOT NULL DEFAULT now(),
    source_url   varchar(1000) UNIQUE,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz
);
CREATE INDEX newspaper_feed_idx ON newspaper_issues (status, published_at DESC);

-- Книги
CREATE TABLE books (
    id           bigserial PRIMARY KEY,
    slug         varchar(200)  NOT NULL UNIQUE,
    title        varchar(256)  NOT NULL,
    description  text          NOT NULL DEFAULT '',
    cover        text,
    buy_url      varchar(1000),
    position     integer       NOT NULL DEFAULT 0,
    status       varchar(16)   NOT NULL DEFAULT 'DRAFT',
    published_at timestamptz   NOT NULL DEFAULT now(),
    source_url   varchar(1000) UNIQUE,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz
);

-- Выпуски журнала
CREATE TABLE magazine_issues (
    id           bigserial PRIMARY KEY,
    slug         varchar(200)  NOT NULL UNIQUE,
    title        varchar(256)  NOT NULL,
    cover        text,
    specs        text,
    contents     text          NOT NULL DEFAULT '',
    buy_url      varchar(1000),
    status       varchar(16)   NOT NULL DEFAULT 'DRAFT',
    published_at timestamptz   NOT NULL DEFAULT now(),
    source_url   varchar(1000) UNIQUE,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz
);

-- Видео
CREATE TABLE videos (
    id           bigserial PRIMARY KEY,
    slug         varchar(200)  NOT NULL UNIQUE,
    title        varchar(256)  NOT NULL,
    url          varchar(1000) NOT NULL,
    preview      text,
    status       varchar(16)   NOT NULL DEFAULT 'DRAFT',
    published_at timestamptz   NOT NULL DEFAULT now(),
    source_url   varchar(1000) UNIQUE,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz
);
CREATE INDEX videos_feed_idx ON videos (status, published_at DESC);

-- Медиатека: файлы, загруженные через админку
CREATE TABLE media (
    id          bigserial PRIMARY KEY,
    path        varchar(500)  NOT NULL UNIQUE,
    mime        varchar(100)  NOT NULL,
    width       integer       NOT NULL,
    height      integer       NOT NULL,
    size_bytes  bigint        NOT NULL,
    alt         text          NOT NULL DEFAULT '',
    caption     text,
    credit      text,
    created_at  timestamptz   NOT NULL DEFAULT now()
);

-- Криптокошельки для страницы «Поддержать»
CREATE TABLE wallets (
    id        bigserial PRIMARY KEY,
    network   varchar(64)  NOT NULL,
    label     varchar(128),
    address   varchar(256) NOT NULL,
    position  integer      NOT NULL DEFAULT 0
);

-- Прочие настройки сайта: ссылка на магазин и т. п.
CREATE TABLE settings (
    key   varchar(100) PRIMARY KEY,
    value text
);
