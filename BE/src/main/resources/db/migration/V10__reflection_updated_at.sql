-- Reflection-edit support: track when the body was last modified so the FE
-- can render a "수정됨" caption while keeping submitted_at as the immutable
-- day-complete marker. Backfill existing rows with submitted_at so the
-- caption never lights up retroactively for un-edited reflections.

alter table reflections add column updated_at timestamptz;

update reflections set updated_at = submitted_at;

alter table reflections alter column updated_at set not null;
