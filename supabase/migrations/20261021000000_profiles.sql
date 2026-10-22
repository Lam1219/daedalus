-- Personal information from the app's first-sign-in setup. Same rules as the health data:
-- owner-only access, removed with the account, and research sees no name or full birth date.

create table public.profiles (
  user_id     uuid primary key default auth.uid() references auth.users (id) on delete cascade,
  first_name  text not null check (length(first_name) between 1 and 100),
  last_name   text not null default '' check (length(last_name) <= 100),
  birth_date  date check (birth_date >= date '1900-01-01'),  -- the app only allows past dates
  sex         text check (sex in ('female', 'male', 'intersex', 'undisclosed')),
  lifestyle   text check (lifestyle in ('sedentary', 'light', 'active', 'very_active')),
  symptoms    text[] not null default '{}' check (symptoms <@ array[
                'tremor', 'slowness', 'stiffness', 'freezing', 'balance', 'dyskinesia', 'wearing_off',
                'handwriting', 'voice', 'swallowing', 'sleep', 'fatigue', 'mood', 'memory',
                'constipation', 'smell', 'dizziness', 'pain'
              ]::text[]),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create trigger profiles_touch before update on public.profiles
  for each row execute function public.touch_updated_at();

alter table public.profiles enable row level security;
revoke all on public.profiles from anon;

-- The app creates the row once (upsert) and updates it; deleting happens with the account.
create policy "read own profile" on public.profiles
  for select to authenticated using ((select auth.uid()) = user_id);
create policy "create own profile" on public.profiles
  for insert to authenticated with check ((select auth.uid()) = user_id);
create policy "update own profile" on public.profiles
  for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
revoke delete on public.profiles from authenticated;

-- Research: birth year only, no name.
create view analysis.profiles as
  select pr.user_id as participant_id,
         extract(year from pr.birth_date)::int as birth_year,
         pr.sex, pr.lifestyle, pr.symptoms
  from public.profiles pr join analysis.participants p on p.participant_id = pr.user_id;

grant select on analysis.profiles to daedalus_analyst;
