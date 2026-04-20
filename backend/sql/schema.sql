create extension if not exists pgcrypto;

create table if not exists users (
    id uuid primary key default gen_random_uuid(),
    email text unique,
    password_hash text,
    full_name text not null,
    birth_date date,
    is_verified boolean not null default false,
    verification_code varchar(6),
    telegram_user_id bigint unique,
    telegram_chat_id bigint unique,
    telegram_username text,
    telegram_first_name text,
    telegram_last_name text,
    telegram_photo_url text,
    auth_provider text not null default 'legacy_email',
    created_at timestamptz not null default now()
);

alter table if exists users alter column password_hash drop not null;
alter table if exists users alter column birth_date drop not null;
alter table if exists users add column if not exists telegram_first_name text;
alter table if exists users add column if not exists telegram_last_name text;
alter table if exists users add column if not exists telegram_photo_url text;

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

create table if not exists messages (
    id uuid primary key,
    chat_id uuid not null references chats(id) on delete cascade,
    role text not null,
    content text not null,
    timestamp_ms bigint not null,
    image_url text,
    created_at timestamptz not null default now()
);
