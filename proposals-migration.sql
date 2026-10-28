-- Ритм 1.3: общее самочувствие и предложения активностей от профиля просмотра.
-- Добавочная миграция: данные не удаляются. Применяется после sleep-edit-migration.sql.
begin;

-- Общее самочувствие: оценка 0–10 и описание в любое время, несколько записей в день.
alter table public.ritm_health drop constraint if exists ritm_health_kind_check;
alter table public.ritm_health add constraint ritm_health_kind_check check(kind in ('migraine','morning','wellbeing'));

create or replace function public.ritm_health_save(p_token text,p_id uuid,p_kind text,p_day date,p_payload jsonb,p_revision integer) returns void language plpgsql security definer set search_path=public as $$
declare k text;n integer;t jsonb;
begin
 if public.ritm_role(p_token) is distinct from 'owner' then raise exception 'Дневник доступен только владельцу';end if;
 if p_id is null or p_day is null or p_kind not in ('migraine','morning','wellbeing') or p_payload is null or jsonb_typeof(p_payload)<>'object' or octet_length(p_payload::text)>16000 then raise exception 'Проверьте запись';end if;
 if p_day > (now() at time zone 'Europe/Moscow')::date then raise exception 'Дата не может быть в будущем';end if;
 if length(coalesce(p_payload->>'note',''))>2000 then raise exception 'Заметка: максимум 2000 символов';end if;
 if p_kind='morning' then
  foreach k in array array['head','body','energy','mood'] loop
   if p_payload->>k is not null then n:=(p_payload->>k)::integer;if n<0 or n>10 then raise exception 'Шкала от 0 до 10';end if;end if;
  end loop;
 elsif p_kind='wellbeing' then
  n:=(p_payload->>'score')::integer;if n is null or n<0 or n>10 then raise exception 'Оцените самочувствие от 0 до 10';end if;
  if p_payload->>'at' is null or ((p_payload->>'at')::timestamptz at time zone 'Europe/Moscow')::date<>p_day then raise exception 'Проверьте время записи';end if;
  if (p_payload->>'at')::timestamptz>now()+interval '5 minutes' then raise exception 'Время не может быть в будущем';end if;
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

-- Предложения активностей: профиль просмотра предлагает, владелец принимает или отклоняет.
create table if not exists public.ritm_proposals(
 id uuid primary key,
 created_at timestamptz not null default now(),
 day date not null,
 start integer check(start is null or start between 570 and 1465),
 duration integer not null default 120 check(duration between 15 and 900),
 kind text not null default 'together' check(kind in ('work','sport','together','personal','meal')),
 title text not null check(length(trim(title)) between 1 and 100),
 note text not null default '' check(length(note)<=500),
 status text not null default 'pending' check(status in ('pending','accepted','declined','withdrawn')),
 decided_at timestamptz,
 reply text not null default '' check(length(reply)<=300)
);
alter table public.ritm_proposals enable row level security;
revoke all on public.ritm_proposals from anon,authenticated;

create or replace function public.ritm_proposals_read(p_token text) returns jsonb language plpgsql security definer set search_path=public as $$
begin
 if public.ritm_role(p_token) is null then raise exception 'Сессия истекла. Введите email снова.';end if;
 return coalesce((select jsonb_agg(to_jsonb(p) order by p.created_at desc) from (select * from public.ritm_proposals order by created_at desc limit 300) p),'[]'::jsonb);
end $$;

-- Новое предложение; повторная отправка с тем же id не создаёт дубль. Не больше 30 ожидающих одновременно.
create or replace function public.ritm_propose(p_token text,p_id uuid,p_payload jsonb) returns void language plpgsql security definer set search_path=public as $$
declare d date;s integer;
begin
 if public.ritm_role(p_token) is distinct from 'viewer' then raise exception 'Предлагать активности может профиль просмотра';end if;
 d:=(p_payload->>'day')::date;s:=(p_payload->>'start')::integer;
 if p_id is null or d is null then raise exception 'Выберите день';end if;
 if d<(now() at time zone 'Europe/Moscow')::date or d>(now() at time zone 'Europe/Moscow')::date+90 then raise exception 'День — от сегодня до 90 дней вперёд';end if;
 if (select count(*) from public.ritm_proposals where status='pending')>=30 and not exists(select 1 from public.ritm_proposals where id=p_id) then raise exception 'Слишком много ожидающих предложений';end if;
 insert into public.ritm_proposals(id,day,start,duration,kind,title,note)
 values(p_id,d,s,coalesce((p_payload->>'duration')::integer,120),coalesce(p_payload->>'kind','together'),left(trim(p_payload->>'title'),100),left(coalesce(p_payload->>'note',''),500))
 on conflict (id) do nothing;
end $$;

-- Решение владельца (accepted/declined) или отзыв предложения автором (withdrawn).
create or replace function public.ritm_proposal_decide(p_token text,p_id uuid,p_status text,p_reply text) returns void language plpgsql security definer set search_path=public as $$
declare r text;
begin
 r:=public.ritm_role(p_token);
 if r is null then raise exception 'Сессия истекла. Введите email снова.';end if;
 if (r='owner' and p_status not in ('accepted','declined')) or (r='viewer' and p_status<>'withdrawn') then raise exception 'Это действие недоступно';end if;
 update public.ritm_proposals set status=p_status,decided_at=clock_timestamp(),reply=left(coalesce(p_reply,''),300) where id=p_id and status='pending';
 if not found then raise exception 'Предложение уже рассмотрено';end if;
end $$;

-- События для фоновой проверки: владельцу — новые предложения, профилю просмотра — решения по ним.
create or replace function public.ritm_inbox(p_token text,p_since timestamptz) returns jsonb language plpgsql security definer set search_path=public as $$
declare r text;
begin
 r:=public.ritm_role(p_token);
 if r is null then raise exception 'Сессия истекла. Введите email снова.';end if;
 return jsonb_build_object('now',clock_timestamp(),'role',r,'events',coalesce((
  select jsonb_agg(jsonb_build_object('id',id,'title',title,'day',day,'start',start,'status',status,'reply',reply) order by coalesce(decided_at,created_at))
  from public.ritm_proposals
  where (r='owner' and status='pending' and created_at>p_since) or (r='viewer' and status in ('accepted','declined') and decided_at>p_since)),'[]'::jsonb));
end $$;

-- Версия предложений в общей проверке изменений, для обоих профилей.
create or replace function public.ritm_poll(p_token text) returns jsonb language plpgsql security definer set search_path=public as $$
declare r text;result jsonb;
begin
 r:=public.ritm_role(p_token);if r is null then raise exception 'Сессия истекла. Введите email снова.';end if;
 select jsonb_build_object('version',version,'role',r) into result from public.ritm_calendar where id=1;
 result:=result||jsonb_build_object('proposalStamp',(select count(*)::text||':'||coalesce(max(greatest(created_at,coalesce(decided_at,created_at)))::text,'') from public.ritm_proposals));
 if r='owner' then
 result:=result||jsonb_build_object('sleepStamp',(select count(*)::text||':'||coalesce(max(updated_at)::text,'') from public.ritm_sleep),'healthStamp',(select count(*)::text||':'||coalesce(max(updated_at)::text,'') from public.ritm_health));
 end if;
 return result;
end $$;

revoke all on function public.ritm_proposals_read(text),public.ritm_propose(text,uuid,jsonb),public.ritm_proposal_decide(text,uuid,text,text),public.ritm_inbox(text,timestamptz) from public;
grant execute on function public.ritm_proposals_read(text),public.ritm_propose(text,uuid,jsonb),public.ritm_proposal_decide(text,uuid,text,text),public.ritm_inbox(text,timestamptz) to anon,authenticated;
commit;
