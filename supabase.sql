-- Run once in Supabase SQL Editor. No personal records or credentials in Git.
begin;
create table public.calendars (
 id uuid primary key default gen_random_uuid(),
 owner_id uuid not null references auth.users(id),
 state jsonb not null default '{"plan":{},"backlog":[],"history":[]}',
 version integer not null default 0
);
create table public.calendar_viewers (
 calendar_id uuid not null references public.calendars(id) on delete cascade,
 user_id uuid not null references auth.users(id),
 primary key(calendar_id,user_id)
);
create table public.sleep_entries (
 id uuid primary key default gen_random_uuid(),
 owner_id uuid not null references auth.users(id),
 bed timestamptz not null,
 wake timestamptz not null,
 quality integer not null check(quality between 1 and 5),
 note text not null default '' check(length(note)<=2000),
 check(wake>bed and wake<=bed+interval '24 hours')
);
alter table public.calendars enable row level security;
alter table public.calendar_viewers enable row level security;
alter table public.sleep_entries enable row level security;
revoke all on public.calendars,public.calendar_viewers,public.sleep_entries from anon;
revoke all on public.calendars,public.calendar_viewers,public.sleep_entries from authenticated;
grant select,update on public.calendars to authenticated;
grant select on public.calendar_viewers to authenticated;
grant select,insert on public.sleep_entries to authenticated;
create policy own_membership on public.calendar_viewers for select to authenticated using(user_id=auth.uid());
create policy read_calendar on public.calendars for select to authenticated using(owner_id=auth.uid() or exists(select 1 from public.calendar_viewers v where v.calendar_id=calendars.id and v.user_id=auth.uid()));
create policy edit_calendar on public.calendars for update to authenticated using(owner_id=auth.uid()) with check(owner_id=auth.uid());
create policy private_sleep_read on public.sleep_entries for select to authenticated using(owner_id=auth.uid());
create policy private_sleep_insert on public.sleep_entries for insert to authenticated with check(owner_id=auth.uid() and exists(select 1 from public.calendars c where c.owner_id=auth.uid()));
-- Database enforces boundaries even when someone calls the API directly.
create function public.validate_calendar() returns trigger language plpgsql set search_path=public as $$
declare d record; b jsonb; prev_end integer; s integer; e integer; calls integer; wd integer;
begin
 if new.owner_id<>old.owner_id or new.id<>old.id then raise exception 'Owner is immutable'; end if;
 if new.version<>old.version+1 then raise exception 'Version must increment by one'; end if;
 if pg_column_size(new.state)>2000000 then raise exception 'Calendar too large'; end if;
 if jsonb_typeof(new.state->'plan') is distinct from 'object' or jsonb_typeof(new.state->'backlog') is distinct from 'array' or jsonb_typeof(new.state->'history') is distinct from 'array' then raise exception 'Invalid state'; end if;
 if (new.state - array['plan','backlog','history']) <> '{}'::jsonb then raise exception 'Only calendar data belongs here'; end if;
 for d in select * from jsonb_each(new.state->'plan') loop
  if d.key !~ '^\d{4}-\d{2}-\d{2}$' or jsonb_typeof(d.value)<>'array' or jsonb_array_length(d.value)>40 then raise exception 'Invalid day'; end if;
  wd:=extract(isodow from d.key::date); prev_end:=570; calls:=0;
  for b in select value from jsonb_array_elements(d.value) order by (value->>'start')::integer loop
   s:=(b->>'start')::integer; e:=(b->>'end')::integer;
   if s is null or e is null or s<prev_end or s<570 or e<=s or e>1470 then raise exception 'Schedule must fit 09:30–00:30 without overlap'; end if;
   if coalesce(length(trim(b->>'title')),0) not between 1 and 100 then raise exception 'Invalid title'; end if;
   if coalesce(b->>'kind','') not in ('work','sport','together','personal','meal','sleep','call') then raise exception 'Invalid activity'; end if;
   if b->>'kind'='call' then
    calls:=calls+1;
    if wd<>2 or s<>1200 or e<>1280 or (b->>'locked')::boolean is distinct from true then raise exception 'Tuesday call is fixed at 20:00–21:20'; end if;
   end if;
   prev_end:=e;
  end loop;
  if wd=2 and calls<>1 then raise exception 'Tuesday call is required'; end if;
 end loop;
 return new;
end $$;
create trigger guard_calendar before update on public.calendars for each row execute function public.validate_calendar();
alter publication supabase_realtime add table public.calendars;
commit;

-- AFTER creating two users in Supabase Authentication > Users:
-- Copy their UUIDs. Run these statements separately with actual values.
-- insert into public.calendars(owner_id) values ('OWNER_USER_UUID') returning id;
-- insert into public.calendar_viewers(calendar_id,user_id) values ('CALENDAR_UUID','VIEWER_USER_UUID');
