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
    plan_code text not null default 'free',
    subscription_status text not null default 'inactive',
    plan_expires_at timestamptz,
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
alter table if exists users add column if not exists plan_code text not null default 'free';
alter table if exists users add column if not exists subscription_status text not null default 'inactive';
alter table if exists users add column if not exists plan_expires_at timestamptz;
alter table if exists users add column if not exists token_invalid_before timestamptz;

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
    created_at timestamptz not null default now()
);

create table if not exists subscriptions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null unique references users(id) on delete cascade,
    plan_code text not null,
    status text not null check (status in ('inactive', 'pending', 'active', 'canceled', 'past_due', 'expired')),
    payment_method_id text,
    cancel_at_period_end boolean not null default false,
    current_period_start timestamptz,
    current_period_end timestamptz,
    last_payment_id text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists billing_payments (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users(id) on delete cascade,
    provider text not null,
    provider_payment_id text not null unique,
    amount_value numeric(10, 2) not null,
    currency text not null default 'RUB',
    kind text not null,
    status text not null,
    payment_method_id text,
    raw_payload jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
