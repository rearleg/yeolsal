create table users (
    id bigserial primary key,
    email varchar(255) not null unique,
    nickname varchar(80) not null,
    password_hash varchar(255),
    auth_provider varchar(30) not null default 'EMAIL',
    timezone varchar(80) not null default 'Asia/Seoul',
    created_at timestamptz not null default now()
);

create table refresh_tokens (
    id bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    token_hash varchar(255) not null unique,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null default now()
);

create table friendships (
    id bigserial primary key,
    requester_id bigint not null references users(id),
    addressee_id bigint not null references users(id),
    status varchar(30) not null,
    created_at timestamptz not null default now(),
    unique (requester_id, addressee_id)
);

create table daily_entries (
    id bigserial primary key,
    user_id bigint not null references users(id),
    entry_date date not null,
    goal text not null,
    created_at timestamptz not null default now(),
    unique (user_id, entry_date)
);

create table todo_items (
    id bigserial primary key,
    daily_entry_id bigint not null references daily_entries(id) on delete cascade,
    title text not null,
    completed boolean not null default false,
    completed_at timestamptz
);

create table reflections (
    id bigserial primary key,
    daily_entry_id bigint not null unique references daily_entries(id) on delete cascade,
    body text not null,
    submitted_at timestamptz not null default now()
);

create table monthly_goals (
    id bigserial primary key,
    user_id bigint not null references users(id),
    month char(7) not null,
    goal text not null,
    unique (user_id, month)
);
