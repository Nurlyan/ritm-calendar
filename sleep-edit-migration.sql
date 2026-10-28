-- Ритм 1.2: редактирование записей сна, устойчивые id для офлайн-очереди, полная история.
-- Добавочная миграция: не удаляет данные. Применяется после email-access.sql и health-migration.sql.
begin;
alter table public.ritm_sleep add column if not exists revision integer not null default 1;
alter table public.ritm_sleep add column if not exists updated_at timestamptz not null default now();
alter table public.ritm_sleep alter column quality drop not null;

-- Полная история календаря отдельно от JSON плана, чтобы не обрезать её до 200 записей.
create table if not exists public.ritm_history(id uuid primary key default gen_random_uuid(), at timestamptz not null default now(), message text not null check(length(message)<=250));
alter table public.ritm_history enable row level security;
revoke all on public.ritm_history from anon,authenticated;
insert into public.ritm_history(id,at,message)
 select (h->>'id')::uuid,(h->>'at')::timestamptz,left(h->>'message',250) from public.ritm_calendar c,jsonb_array_elements(c.state->'history') h
 on conflict (id) do nothing;

create or replace function public.ritm_save(p_token text,p_version integer,p_state jsonb,p_message text) returns integer language plpgsql security definer set search_path=public as $$
declare v integer;h jsonb;
begin
 if public.ritm_role(p_token) is distinct from 'owner' then raise exception 'Доступ только для просмотра';end if;
 select version into v from public.ritm_calendar where id=1 for update;
 if v<>p_version then raise exception 'План изменился на другом устройстве. Повторите действие.';end if;
 insert into public.ritm_history(message) values(left(p_message,250));
 -- В JSON остаются последние 200 записей для быстрого чтения; полная история — в ritm_history.
 select coalesce(jsonb_agg(jsonb_build_object('id',id,'at',at,'message',message) order by at desc),'[]'::jsonb) into h from (select * from public.ritm_history order by at desc limit 200) q;
 update public.ritm_calendar set state=jsonb_set(p_state,'{history}',h),version=v+1 where id=1;
 return v+1;
end $$;

-- Создание или исправление записи сна. p_revision=0 — новая запись; повторная отправка с тем же id не создаёт дубль.
create or replace function public.ritm_sleep_save(p_token text,p_id uuid,p_entry jsonb,p_revision integer) returns integer language plpgsql security definer set search_path=public as $$
declare b timestamptz;w timestamptz;q integer;n text;r integer;
begin
 if public.ritm_role(p_token) is distinct from 'owner' then raise exception 'Дневник доступен в профиле владельца';end if;
 b:=(p_entry->>'bed')::timestamptz;w:=(p_entry->>'wake')::timestamptz;q:=(p_entry->>'quality')::integer;n:=coalesce(p_entry->>'note','');
 if p_id is null or b is null or w is null or w<=b or w>b+interval '24 hours' then raise exception 'Проверьте время: от 0 до 24 часов';end if;
 if q is not null and (q<1 or q>5) then raise exception 'Качество от 1 до 5';end if;
 if length(n)>2000 then raise exception 'Заметка: максимум 2000 символов';end if;
 if p_revision=0 then
  insert into public.ritm_sleep(id,bed,wake,quality,note) values(p_id,b,w,q,n) on conflict (id) do nothing;
  select revision into r from public.ritm_sleep where id=p_id;
  return r;
 end if;
 update public.ritm_sleep set bed=b,wake=w,quality=q,note=n,revision=revision+1,updated_at=clock_timestamp() where id=p_id and revision=p_revision returning revision into r;
 if r is null then raise exception 'Запись изменена на другом устройстве. Откройте её заново.';end if;
 return r;
end $$;

-- Отметка версии сна учитывает и исправления, а не только новые записи.
create or replace function public.ritm_poll(p_token text) returns jsonb language plpgsql security definer set search_path=public as $$
declare r text;result jsonb;
begin
 r:=public.ritm_role(p_token);if r is null then raise exception 'Сессия истекла. Введите email снова.';end if;
 select jsonb_build_object('version',version,'role',r) into result from public.ritm_calendar where id=1;
 if r='owner' then
 result:=result||jsonb_build_object('sleepStamp',(select count(*)::text||':'||coalesce(max(updated_at)::text,'') from public.ritm_sleep),'healthStamp',(select count(*)::text||':'||coalesce(max(updated_at)::text,'') from public.ritm_health));
 end if;
 return result;
end $$;

-- Повторная отправка новой записи здоровья из офлайн-очереди не должна падать на дубле id.
create or replace function public.ritm_health_save(p_token text,p_id uuid,p_kind text,p_day date,p_payload jsonb,p_revision integer) returns void language plpgsql security definer set search_path=public as $$
declare k text;n integer;t jsonb;
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
  insert into public.ritm_health(id,kind,day,payload) values(p_id,p_kind,p_day,p_payload) on conflict (id) do nothing;
 else
  update public.ritm_health set payload=p_payload,day=p_day,revision=revision+1,updated_at=clock_timestamp() where id=p_id and kind=p_kind and revision=p_revision;
  if not found then raise exception 'Запись изменена на другом устройстве. Откройте её заново.';end if;
 end if;
end $$;

-- Полная история для экспорта, страницами.
create or replace function public.ritm_history_read(p_token text,p_offset integer default 0) returns jsonb language plpgsql security definer set search_path=public as $$
begin
 if public.ritm_role(p_token) is null then raise exception 'Сессия истекла. Введите email снова.';end if;
 if p_offset<0 then raise exception 'Invalid offset';end if;
 return coalesce((select jsonb_agg(jsonb_build_object('id',id,'at',at,'message',message) order by at desc) from (select * from public.ritm_history order by at desc limit 500 offset p_offset) q),'[]'::jsonb);
end $$;

revoke all on function public.ritm_sleep_save(text,uuid,jsonb,integer),public.ritm_history_read(text,integer) from public;
grant execute on function public.ritm_sleep_save(text,uuid,jsonb,integer),public.ritm_history_read(text,integer) to anon,authenticated;
commit;
