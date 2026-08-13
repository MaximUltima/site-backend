CREATE TABLE articles(
    id bigserial not null primary key,
    title varchar(256) not null,
    publish_date timestamp not null default CURRENT_TIMESTAMP,
    content text,
    created_at timestamp not null default CURRENT_TIMESTAMP,
    updated_at timestamp
);