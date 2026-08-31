begin;

create table if not exists public.audio_share_updates (
    channel text primary key,
    latest_version text not null,
    zip_url text not null,
    signature text not null,
    notes text not null default '',
    published_at timestamptz not null default now(),
    enabled boolean not null default false,
    constraint audio_share_updates_channel_check
        check (channel in ('audio-share-server-stable', 'audio-share-android-stable')),
    constraint audio_share_updates_version_check
        check (latest_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    constraint audio_share_updates_https_check
        check (zip_url ~ '^https://')
);

alter table public.audio_share_updates enable row level security;

revoke all on table public.audio_share_updates from anon, authenticated;
grant select, insert, update on table public.audio_share_updates to service_role;

commit;

select
    schemaname,
    tablename,
    rowsecurity
from pg_catalog.pg_tables
where schemaname = 'public'
  and tablename = 'audio_share_updates';
