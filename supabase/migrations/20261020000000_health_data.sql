-- Daedalus health data.
--
-- Rules this schema enforces:
--   * Each person owns their data. Row-level security limits every table and the session-file
--     bucket to the signed-in user (auth.uid()); nobody else can read it through the API.
--   * Deleting an account deletes all of that person's rows (on delete cascade).
--   * Research analysis only sees people who have not opted out, and never their email:
--     analysts read the `analysis` views, keyed by the account's random UUID.
--   * The anonymous (signed-out) API key can read or write nothing.

-- ---------------------------------------------------------------------------
-- Consent
-- ---------------------------------------------------------------------------

create table public.consents (
  user_id           uuid primary key references auth.users (id) on delete cascade,
  analysis_opt_out  boolean not null default false,  -- true = no research analysis
  policy_version    text not null default '2026-10',
  updated_at        timestamptz not null default now()
);

-- Every new account gets a consent row, using the choice made on the sign-up screen
-- (sent as user metadata `analysis_opt_out`).
create function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.consents (user_id, analysis_opt_out)
  values (new.id, coalesce((new.raw_user_meta_data ->> 'analysis_opt_out')::boolean, false));
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

create function public.touch_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create trigger consents_touch before update on public.consents
  for each row execute function public.touch_updated_at();

-- The app changes its consent through this call (runs as the user, so RLS still applies).
create function public.set_analysis_opt_out(opt_out boolean)
returns void
language sql
security invoker
set search_path = ''
as $$
  update public.consents set analysis_opt_out = opt_out where user_id = (select auth.uid());
$$;
revoke execute on function public.set_analysis_opt_out(boolean) from public, anon;
grant execute on function public.set_analysis_opt_out(boolean) to authenticated;

-- ---------------------------------------------------------------------------
-- Health records (mirror what the phone keeps locally)
-- ---------------------------------------------------------------------------

create table public.medication_schedule (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name        text not null,
  dosage      text not null,
  hour        smallint not null check (hour between 0 and 23),
  minute      smallint not null check (minute between 0 and 59),
  created_at  timestamptz not null default now()
);

create table public.dose_logs (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid() references auth.users (id) on delete cascade,
  medication  text not null,
  dosage      text not null,
  taken_at    timestamptz not null,
  schedule_id uuid references public.medication_schedule (id) on delete set null,
  created_at  timestamptz not null default now()
);

create table public.checkins (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null default auth.uid() references auth.users (id) on delete cascade,
  feeling      text not null,
  recorded_at  timestamptz not null,
  created_at   timestamptz not null default now()
);

-- One row per watch session; the CSV itself is in the private `sessions` bucket.
create table public.session_files (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null default auth.uid() references auth.users (id) on delete cascade,
  object_path  text not null unique,   -- <user_id>/<file name>.csv in the sessions bucket
  mode         text,
  started_at   timestamptz,
  duration_s   integer,
  created_at   timestamptz not null default now(),
  check (split_part(object_path, '/', 1) = user_id::text)
);

create index on public.medication_schedule (user_id);
create index on public.dose_logs (user_id, taken_at);
create index on public.checkins (user_id, recorded_at);
create index on public.session_files (user_id, started_at);

-- ---------------------------------------------------------------------------
-- Row-level security: owners only
-- ---------------------------------------------------------------------------

alter table public.consents            enable row level security;
alter table public.medication_schedule enable row level security;
alter table public.dose_logs           enable row level security;
alter table public.checkins            enable row level security;
alter table public.session_files       enable row level security;

revoke all on public.consents, public.medication_schedule, public.dose_logs,
              public.checkins, public.session_files from anon;

-- Consent: read and change your own choice. Rows are created by the trigger and
-- removed with the account, so no insert/delete for users.
create policy "read own consent" on public.consents
  for select to authenticated using ((select auth.uid()) = user_id);
create policy "update own consent" on public.consents
  for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
revoke insert, delete on public.consents from authenticated;

create policy "own rows" on public.medication_schedule
  for all to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "own rows" on public.dose_logs
  for all to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "own rows" on public.checkins
  for all to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "own rows" on public.session_files
  for all to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

-- ---------------------------------------------------------------------------
-- Session files: private bucket, one folder per user
-- ---------------------------------------------------------------------------

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('sessions', 'sessions', false, 52428800, array['text/csv'])
on conflict (id) do update set public = false;

create policy "sessions: read own" on storage.objects
  for select to authenticated
  using (bucket_id = 'sessions' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "sessions: upload own" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'sessions' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "sessions: update own" on storage.objects
  for update to authenticated
  using (bucket_id = 'sessions' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "sessions: delete own" on storage.objects
  for delete to authenticated
  using (bucket_id = 'sessions' and (storage.foldername(name))[1] = (select auth.uid())::text);

-- ---------------------------------------------------------------------------
-- Research analysis: consenting users only, no email
-- ---------------------------------------------------------------------------
-- The `analysis` schema is not exposed through the app API. Python scripts connect to the
-- database as `daedalus_analyst` (read-only, these views only). People who opted out are
-- filtered out here, so a script cannot include them by mistake.

create schema analysis;
revoke all on schema analysis from public, anon, authenticated;

create view analysis.participants as
  select c.user_id as participant_id, c.policy_version, c.updated_at as consent_updated_at
  from public.consents c
  where not c.analysis_opt_out;

create view analysis.dose_logs as
  select d.user_id as participant_id, d.medication, d.dosage, d.taken_at, d.schedule_id
  from public.dose_logs d join analysis.participants p on p.participant_id = d.user_id;

create view analysis.medication_schedule as
  select m.user_id as participant_id, m.id as schedule_id, m.name, m.dosage, m.hour, m.minute
  from public.medication_schedule m join analysis.participants p on p.participant_id = m.user_id;

create view analysis.checkins as
  select c.user_id as participant_id, c.feeling, c.recorded_at
  from public.checkins c join analysis.participants p on p.participant_id = c.user_id;

create view analysis.session_files as
  select s.user_id as participant_id, s.object_path, s.mode, s.started_at, s.duration_s
  from public.session_files s join analysis.participants p on p.participant_id = s.user_id;

-- Read-only role for analysis scripts. It cannot log in until you give it a password
-- (see supabase/README.md), and it can only read the views above.
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'daedalus_analyst') then
    create role daedalus_analyst nologin;
  end if;
end;
$$;
grant usage on schema analysis to daedalus_analyst;
grant select on all tables in schema analysis to daedalus_analyst;
alter role daedalus_analyst set default_transaction_read_only = on;
