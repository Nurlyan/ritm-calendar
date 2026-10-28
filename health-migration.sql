begin;
create table if not exists public.ritm_health (
 id uuid primary key, kind text not null check(kind in ('migraine','morning')),
 day date not null, payload jsonb not null check(octet_length(payload::text)<=16000),
 revision integer not null default 1, updated_at timestamptz not null default now()
);
create unique index if not exists ritm_one_morning on public.ritm_health(day) where kind='morning';
alter table public.ritm_health enable row level security;
revoke all on public.ritm_health from anon,authenticated;
create or replace function public.ritm_poll(p_token text) returns jsonb language plpgsql security definer set search_path=public as $$
declare r text;result jsonb;
begin
 r:=public.ritm_role(p_token);if r is null then raise exception 'Сессия истекла. Введите email снова.';end if;
 select jsonb_build_object('version',version,'role',r) into result from public.ritm_calendar where id=1;
 if r='owner' then
 result:=result||jsonb_build_object('sleepStamp',(select count(*)::text||':'||coalesce(max(id::text),'') from public.ritm_sleep),'healthStamp',(select count(*)::text||':'||coalesce(max(updated_at)::text,'') from public.ritm_health));
 end if;
 return result;
end $$;
create or replace function public.ritm_health_read(p_token text,p_offset integer default 0) returns jsonb language plpgsql security definer set search_path=public as $$
begin
 if public.ritm_role(p_token) is distinct from 'owner' then raise exception 'Дневник доступен только владельцу';end if;
 if p_offset<0 then raise exception 'Invalid offset';end if;
 return coalesce((select jsonb_agg(q order by day desc,id) from (select * from public.ritm_health order by day desc,id limit 500 offset p_offset) q),'[]'::jsonb);
end $$;
create or replace function public.ritm_health_save(p_token text,p_id uuid,p_kind text,p_day date,p_payload jsonb,p_revision integer) returns void language plpgsql security definer set search_path=public as $$
declare k text;n integer;t jsonb;v integer;
begin
 if public.ritm_role(p_token) is distinct from 'owner' then raise exception 'Дневник доступен только владельцу';end if;
 if p_id is null or p_day is null or p_kind not in ('migraine','morning') or p_payload is null or jsonb_typeof(p_payload)<>'object' or octet_length(p_payload::text)>16000 then raise exception 'Проверьте запись';end if;
 if p_day > (now() at time zone 'Europe/Moscow')::date then raise exception 'Дата не может быть в будущем';end if;
 if length(coalesce(p_payload->>'note',''))>2000 then raise exception 'Заметка: максимум 2000 символов';end if;
 if p_kind='morning' then
  foreach k in array array['head','body','energy','mood'] loop
   if p_payload->>k is not null then n:=(p_payload->>k)::integer;if n<0 or n>10 then raise exception 'Шкала от 0 до 10';end if;end if;
  end loop;
 else
  n:=(p_payload->>'pain')::integer;if n is null or n<0 or n>10 then raise exception 'Укажите силу боли от 0 до 10';end if;
  if p_payload->>'start' is null or ((p_payload->>'start')::timestamptz at time zone 'Europe/Moscow')::date<>p_day then raise exception 'Проверьте начало приступа';end if;
  if (p_payload->>'start')::timestamptz>now()+interval '5 minutes' then raise exception 'Начало не может быть в будущем';end if;
  if p_payload->>'end' is not null and ((p_payload->>'end')::timestamptz<(p_payload->>'start')::timestamptz or (p_payload->>'end')::timestamptz>now()+interval '5 minutes') then raise exception 'Проверьте окончание приступа';end if;
  if jsonb_typeof(p_payload->'treatments') is distinct from 'array' or jsonb_array_length(p_payload->'treatments')>10 then raise exception 'Не более 10 приёмов';end if;
  for t in select value from jsonb_array_elements(p_payload->'treatments') loop
   if coalesce(length(trim(t->>'name')),0) not between 1 and 150 or length(coalesce(t->>'dose',''))>100 or coalesce(t->>'effect','') not in ('unknown','none','partial','full') then raise exception 'Проверьте приём препарата';end if;
   if t->>'painAfter' is not null and ((t->>'painAfter')::integer<0 or (t->>'painAfter')::integer>10) then raise exception 'Шкала от 0 до 10';end if;
   if t->>'at' is not null and ((t->>'at')::timestamptz<(p_payload->>'start')::timestamptz or (t->>'at')::timestamptz>now()+interval '5 minutes') then raise exception 'Проверьте время приёма';end if;
   if t->>'assessedAt' is not null and (t->>'at' is null or (t->>'assessedAt')::timestamptz<(t->>'at')::timestamptz or (t->>'assessedAt')::timestamptz>now()+interval '5 minutes') then raise exception 'Проверьте время оценки эффекта';end if;
  end loop;
 end if;
 if p_revision=0 then
  insert into public.ritm_health(id,kind,day,payload) values(p_id,p_kind,p_day,p_payload);
 else
  update public.ritm_health set payload=p_payload,day=p_day,revision=revision+1,updated_at=clock_timestamp() where id=p_id and kind=p_kind and revision=p_revision;
  if not found then raise exception 'Запись изменена на другом устройстве. Откройте её заново.';end if;
 end if;
end $$;
revoke all on function public.ritm_poll(text),public.ritm_health_read(text,integer),public.ritm_health_save(text,uuid,text,date,jsonb,integer) from public;
grant execute on function public.ritm_poll(text),public.ritm_health_read(text,integer),public.ritm_health_save(text,uuid,text,date,jsonb,integer) to anon,authenticated;
commit;
