-- Lets a signed-in person delete their account from the app (App Store 5.1.1(v), Google Play).
-- Deleting the auth user removes everything else through `on delete cascade`: consent,
-- profile, schedule, dose logs, check-ins and session-file rows. The app deletes the person's
-- files in the `sessions` bucket first, through the Storage API.

create function public.delete_my_account()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  uid uuid := (select auth.uid());
begin
  if uid is null then
    raise exception 'not signed in';
  end if;
  delete from auth.users where id = uid;
end;
$$;

revoke execute on function public.delete_my_account() from public, anon;
grant execute on function public.delete_my_account() to authenticated;
