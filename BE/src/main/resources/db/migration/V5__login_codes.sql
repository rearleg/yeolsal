-- Phase 5: Kakao OAuth one-time login code.
--
-- Purpose: avoid leaking JWT access/refresh tokens via deep-link query
-- parameters (which can show up in OS link handlers, analytics, screenshots,
-- and logs). After Kakao OAuth completes server-side, /auth/kakao/callback
-- now redirects to the mobile app with a short-lived single-use `code`
-- only. The app then calls POST /auth/kakao/exchange to swap the code for
-- yeosal access/refresh tokens via the normal JSON response body.
--
-- Decisions:
--   * `code` is Base64URL-encoded (no padding) of 32 random bytes ~= 43 chars.
--     The column allows up to 64 chars to leave headroom for future variants.
--   * Short TTL (~2 min) keeps the steal-and-replay window small.
--   * `consumed_at` lets us reject double-use deterministically; combined
--     with `findByCodeAndConsumedAtIsNull` in the repo, replay returns the
--     same generic "invalid code" error.
--   * No index on `expires_at`; cleanup of stale rows runs ad-hoc and the
--     table stays small thanks to short TTL + cascade-delete from users.

create table login_codes (
    id           bigserial primary key,
    code         varchar(64) not null unique,
    user_id      bigint not null references users(id) on delete cascade,
    expires_at   timestamptz not null,
    consumed_at  timestamptz,
    created_at   timestamptz not null default now()
);
