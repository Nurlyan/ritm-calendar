begin;
create table public.ritm_calendar(id integer primary key check(id=1), state jsonb not null default '{"plan":{},"backlog":[],"history":[]}', version integer not null default 0);
insert into public.ritm_calendar(id) values(1);
create table public.ritm_profiles(email_hash text primary key, role text not null check(role in ('owner','viewer')));
create table public.ritm_sessions(token_hash text primary key, role text not null, expires_at timestamptz not null);
create table public.ritm_sleep(id uuid primary key default gen_random_uuid(),bed timestamptz not null,wake timestamptz not null,quality integer not null check(quality between 1 and 5),note text not null default '' check(length(note)<=2000),check(wake>bed and wake<=bed+interval '24 hours'));
alter table public.ritm_calendar enable row level security;
alter table public.ritm_profiles enable row level security;
alter table public.ritm_sessions enable row level security;
alter table public.ritm_sleep enable row level security;
revoke all on public.ritm_calendar,public.ritm_profiles,public.ritm_sessions,public.ritm_sleep from anon,authenticated;
create function public.ritm_guard() returns trigger language plpgsql set search_path=public as $$
declare d record;b jsonb;prev_end integer;s integer;e integer;calls integer;wd integer;
begin
 if new.version<>old.version+1 then raise exception 'Version conflict';end if;
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
 return new; end $$;
create trigger ritm_guard before update on public.ritm_calendar for each row execute function public.ritm_guard();
create function public.ritm_role(p_token text) returns text language sql security definer set search_path=public as $$ select role from public.ritm_sessions where token_hash=encode(sha256(convert_to(p_token,'UTF8')),'hex') and expires_at>now() $$;
revoke all on function public.ritm_role(text) from public,anon,authenticated;
create function public.ritm_login(p_email text) returns jsonb language plpgsql security definer set search_path=public as $$
declare r text;t text;
begin
 select role into r from public.ritm_profiles where email_hash=encode(sha256(convert_to(lower(trim(p_email)),'UTF8')),'hex');
 if r is null then raise exception 'Этот email не добавлен в календарь';end if;
 t:=gen_random_uuid()::text||gen_random_uuid()::text;
 delete from public.ritm_sessions where expires_at<now();
 insert into public.ritm_sessions values(encode(sha256(convert_to(t,'UTF8')),'hex'),r,now()+interval '365 days');
 return jsonb_build_object('token',t,'role',r,'email',lower(trim(p_email)));
end $$;
create function public.ritm_read(p_token text) returns jsonb language plpgsql security definer set search_path=public as $$
declare r text;result jsonb;
begin
 r:=public.ritm_role(p_token);if r is null then raise exception 'Сессия истекла. Введите email снова.';end if;
 select jsonb_build_object('state',state,'version',version,'role',r) into result from public.ritm_calendar where id=1;
 if r='owner' then result:=result||jsonb_build_object('sleep',coalesce((select jsonb_agg(x order by bed desc) from public.ritm_sleep x),'[]'::jsonb));end if;
 return result;
end $$;
create function public.ritm_save(p_token text,p_version integer,p_state jsonb,p_message text) returns integer language plpgsql security definer set search_path=public as $$
declare v integer;h jsonb;
begin
 if public.ritm_role(p_token) is distinct from 'owner' then raise exception 'Доступ только для просмотра';end if;
 select version,state->'history' into v,h from public.ritm_calendar where id=1 for update;
 if v<>p_version then raise exception 'План изменился на другом устройстве. Повторите действие.';end if;
 h:=jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'at',now(),'message',left(p_message,250)))||h;
 select jsonb_agg(value) into h from (select value from jsonb_array_elements(h) limit 200) q;
 update public.ritm_calendar set state=jsonb_set(p_state,'{history}',h),version=v+1 where id=1;
 return v+1;
end $$;
create function public.ritm_add_sleep(p_token text,p_entry jsonb) returns void language plpgsql security definer set search_path=public as $$
begin
 if public.ritm_role(p_token) is distinct from 'owner' then raise exception 'Дневник доступен в профиле владельца';end if;
 insert into public.ritm_sleep(bed,wake,quality,note) values((p_entry->>'bed')::timestamptz,(p_entry->>'wake')::timestamptz,(p_entry->>'quality')::integer,coalesce(p_entry->>'note',''));
end $$;
create function public.ritm_logout(p_token text) returns void language sql security definer set search_path=public as $$ delete from public.ritm_sessions where token_hash=encode(sha256(convert_to(p_token,'UTF8')),'hex') $$;
revoke all on function public.ritm_login(text),public.ritm_read(text),public.ritm_save(text,integer,jsonb,text),public.ritm_add_sleep(text,jsonb),public.ritm_logout(text) from public;
grant execute on function public.ritm_login(text),public.ritm_read(text),public.ritm_save(text,integer,jsonb,text),public.ritm_add_sleep(text,jsonb),public.ritm_logout(text) to anon,authenticated;
commit;
