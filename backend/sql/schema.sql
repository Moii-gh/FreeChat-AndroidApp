create extension if not exists pgcrypto;

create table if not exists users (
    id uuid primary key default gen_random_uuid(),
    email text unique,
    password_hash text,
    full_name text not null,
    birth_date date,
    is_verified boolean not null default false,
    verification_code_hash text,
    verification_code_expires_at timestamptz,
    verification_code_sent_at timestamptz,
    verification_attempt_count integer not null default 0,
    telegram_user_id bigint unique,
    telegram_chat_id bigint unique,
    telegram_username text,
    telegram_first_name text,
    telegram_last_name text,
    telegram_photo_url text,
    auth_provider text not null default 'legacy_email',
    bonus_requests integer not null default 0,
    token_invalid_before timestamptz,
    created_at timestamptz not null default now()
);

alter table if exists users alter column password_hash drop not null;
alter table if exists users alter column birth_date drop not null;
alter table if exists users drop column if exists verification_code;
alter table if exists users add column if not exists verification_code_hash text;
alter table if exists users add column if not exists verification_code_expires_at timestamptz;
alter table if exists users add column if not exists verification_code_sent_at timestamptz;
alter table if exists users add column if not exists verification_attempt_count integer not null default 0;
alter table if exists users add column if not exists telegram_first_name text;
alter table if exists users add column if not exists telegram_last_name text;
alter table if exists users add column if not exists telegram_photo_url text;
alter table if exists users drop column if exists plan_code;
alter table if exists users drop column if exists subscription_status;
alter table if exists users drop column if exists plan_expires_at;
alter table if exists users add column if not exists bonus_requests integer not null default 0;
alter table if exists users add column if not exists token_invalid_before timestamptz;


do $$ begin
    create type file_category_enum as enum ('avatar', 'image', 'document', 'generated_image');
exception
    when duplicate_object then null;
end $$;

create table if not exists files (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users(id) on delete cascade,
    file_category file_category_enum not null,
    original_name text not null,
    filename text not null unique,
    mime_type text not null,
    size bigint not null,
    storage_type text not null default 'local',
    url text,
    thumb_url text,
    width integer,
    height integer,
    created_at timestamptz not null default now()
);

alter table if exists users add column if not exists avatar_file_id uuid references files(id) on delete set null;

create table if not exists telegram_auth_challenges (
    id uuid primary key default gen_random_uuid(),
    start_token text not null unique,
    purpose text not null check (purpose in ('register', 'login', 'migrate')),
    code varchar(6),
    user_id uuid references users(id) on delete cascade,
    telegram_user_id bigint,
    telegram_chat_id bigint,
    telegram_username text,
    verified_at timestamptz,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_at timestamptz not null default now()
);

create table if not exists chats (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    title text not null,
    timestamp_ms bigint not null,
    is_pinned boolean not null default false,
    last_updated_ms bigint not null,
    summary text not null default '',
    is_deleted boolean not null default false,
    created_at timestamptz not null default now()
);

create table if not exists auth_nonces (
    kind text not null,
    nonce_hash text not null,
    expires_at timestamptz not null,
    consumed_at timestamptz not null default now(),
    primary key (kind, nonce_hash)
);

create table if not exists ai_daily_usage (
    user_id uuid not null references users(id) on delete cascade,
    usage_date date not null,
    request_count integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, usage_date)
);

create table if not exists messages (
    id uuid primary key,
    chat_id uuid not null references chats(id) on delete cascade,
    role text not null,
    content text not null,
    timestamp_ms bigint not null,
    image_url text,
    attachment_data text,
    attachment_mime_type text,
    attachment_file_name text,
    attachment_context text,
    created_at timestamptz not null default now()
);

alter table if exists messages add column if not exists attachment_data text;
alter table if exists messages add column if not exists attachment_mime_type text;
alter table if exists messages add column if not exists attachment_file_name text;
alter table if exists messages add column if not exists attachment_context text;

create table if not exists chat_share_links (
    id uuid primary key default gen_random_uuid(),
    owner_user_id uuid not null references users(id) on delete cascade,
    source_chat_id uuid not null,
    token_hash text not null unique,
    title text not null,
    summary text not null default '',
    snapshot_json jsonb not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null default now()
);

create index if not exists idx_chat_share_links_owner_source
    on chat_share_links(owner_user_id, source_chat_id);

create index if not exists idx_chat_share_links_active
    on chat_share_links(token_hash, expires_at)
    where revoked_at is null;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'chatapp') then
        grant usage on schema public to chatapp;
        grant select, insert, update, delete on all tables in schema public to chatapp;
        grant usage, select, update on all sequences in schema public to chatapp;
    end if;
end $$;
