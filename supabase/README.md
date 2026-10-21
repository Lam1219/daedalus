# Supabase setup for Daedalus

Supabase holds the account (email + password), the research consent, and — once the app
uploads it — the user's health data. Everything is in one project.

## 1. Project and app keys

1. Create a Supabase project in the **Canada (Central)** region. The region can't be changed
   later, and keeping health data in Canada simplifies Law 25 (Quebec) and PHIPA (Ontario).
2. Copy `daedalus-companion/supabase.properties.example` to `daedalus-companion/supabase.properties`
   and fill in the project URL and **anon** key (Project Settings → API). This file is not committed.
   Never put the **service_role** key in the app.
3. Authentication → URL Configuration → **Redirect URLs**: add `daedalus://auth-callback`
   (used by the confirmation and password-reset emails).
4. Authentication → Providers → **Email**: on. Keep "Confirm email" on.

## 2. Database and storage

```bash
supabase link --project-ref <your-project-ref>
supabase db push
```

This applies `migrations/20261020000000_health_data.sql`:

| What | Who can reach it |
| --- | --- |
| `consents` (research opt-out, one row per account) | the user: read and change own row |
| `medication_schedule`, `dose_logs`, `checkins`, `session_files` | the user: own rows only |
| `sessions` storage bucket (watch CSV files, private, `<user_id>/…`) | the user: own folder only |
| `analysis.*` views | `daedalus_analyst` only, read-only, opted-out users excluded, no email |

- Row-level security is on for every table; the anon key can read or write nothing.
- Deleting an account in Authentication → Users deletes all of that person's rows.
  Their files in the `sessions` bucket must be deleted too (Storage → sessions → their folder).
- The sign-up screen sends the research choice as user metadata; a trigger stores it in `consents`.
  The app changes it later through `set_analysis_opt_out(opt_out)`.

## 3. Research analysis (Python)

Analysis scripts connect straight to the database as the read-only `daedalus_analyst` role and
only see the `analysis` views, so people who opted out are never included.

1. Give the role a strong password (SQL editor, run once, don't commit the password):

   ```sql
   alter role daedalus_analyst with login password '<strong password>';
   ```

2. Connect with the **session pooler** connection string (Project Settings → Database), using
   user `daedalus_analyst.<project-ref>`, and `sslmode=require`.

   ```python
   import os, pandas as pd, sqlalchemy as sa
   engine = sa.create_engine(os.environ["DAEDALUS_ANALYSIS_DB_URL"])  # keep the URL out of notebooks
   doses = pd.read_sql("select * from analysis.dose_logs", engine)
   ```

3. Session CSVs: list consenting files with `select object_path from analysis.session_files`.
   Downloading them needs a storage key; do this from a trusted machine only, and never
   download files that aren't in that view.

## 4. Security checklist (dashboard)

- [ ] Region: Canada (Central).
- [ ] Organization: MFA on for every member; only people who need access are members.
- [ ] Authentication → Policies / Passwords: minimum length 8+; turn on leaked-password
      protection if your plan has it.
- [ ] Database → Settings: **Enforce SSL** on; add **Network restrictions** so only known IPs
      can connect directly to the database (the app uses the API, not direct connections).
- [ ] Database → Backups: turn on point-in-time recovery if your plan has it.
- [ ] Advisors → Security Advisor: no warnings after `db push`.
- [ ] Keep the service_role key and analyst password in a password manager, not in code.

Before collecting real patient data, have the privacy setup reviewed (privacy impact assessment
for Law 25; ethics board approval for research use).

## Later: passkeys

Passkey sign-in was removed from the app for now. The server code is kept in `later/passkeys/`
(Edge Function, migration, assetlinks.json) and is **not** deployed by `db push`.
