-- Passkeys (WebAuthn) for Daedalus sign-in.
-- Only the `passkeys` Edge Function (service role) reads or writes these tables;
-- RLS is on with no policies, so the app's anon/user keys cannot touch them.

create table if not exists public.passkey_credentials (
  id            text primary key,                    -- credential ID (base64url)
  user_id       uuid not null references auth.users (id) on delete cascade,
  public_key    text not null,                       -- COSE public key (base64url)
  counter       bigint not null default 0,
  transports    text[] not null default '{}',
  device_name   text,
  created_at    timestamptz not null default now(),
  last_used_at  timestamptz
);
create index if not exists passkey_credentials_user_id_idx on public.passkey_credentials (user_id);

create table if not exists public.passkey_challenges (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid references auth.users (id) on delete cascade,  -- null for sign-in
  challenge   text not null,
  kind        text not null check (kind in ('register', 'login')),
  expires_at  timestamptz not null default now() + interval '5 minutes'
);

alter table public.passkey_credentials enable row level security;
alter table public.passkey_challenges enable row level security;
