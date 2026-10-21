-- Lets a signed-in person delete everything saved in their account (the app's
-- "Delete uploaded data" and "Delete all my data"). The account itself and its consent
-- record stay. Session files in Storage are deleted by the app first, through the
-- Storage API (its policies only allow a user's own folder).

create function public.delete_my_data()
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
  delete from public.session_files       where user_id = uid;
  delete from public.dose_logs           where user_id = uid;
  delete from public.checkins            where user_id = uid;
  delete from public.medication_schedule where user_id = uid;
  delete from public.profiles            where user_id = uid;
end;
$$;

revoke execute on function public.delete_my_data() from public, anon;
grant execute on function public.delete_my_data() to authenticated;
