-- Ритм · отбой до 02:00 и дела внутри занятий.
-- 1) Подготовка ко сну может плавать: занятия дня плана заканчиваются не позже 02:00 (1560 минут от полуночи дня плана).
-- 2) У занятия может быть список «что ещё делал» (items): до 30 записей {id,title,start,end}.
-- Меняется только функция проверки; данные не трогаются.
create or replace function public.ritm_guard() returns trigger language plpgsql set search_path to 'public' as $function$
declare d record;b jsonb;i jsonb;prev_end integer;s integer;e integer;calls integer;wd integer;
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
   if s is null or e is null or s<prev_end or s<570 or e<=s or e>1560 then raise exception 'Schedule must fit 09:30–02:00 without overlap'; end if;
   if coalesce(length(trim(b->>'title')),0) not between 1 and 100 then raise exception 'Invalid title'; end if;
   if coalesce(b->>'kind','') not in ('work','sport','together','personal','meal','sleep','call') then raise exception 'Invalid activity'; end if;
   if b->>'kind'='call' then
    calls:=calls+1;
    if wd<>2 or s<>1200 or e<>1280 or (b->>'locked')::boolean is distinct from true then raise exception 'Tuesday call is fixed at 20:00–21:20'; end if;
   end if;
   if b ? 'items' then
    if jsonb_typeof(b->'items')<>'array' or jsonb_array_length(b->'items')>30 then raise exception 'Invalid items'; end if;
    for i in select value from jsonb_array_elements(b->'items') loop
     if jsonb_typeof(i)<>'object' or coalesce(length(trim(i->>'title')),0) not between 1 and 60 or jsonb_typeof(i->'start')<>'number' or jsonb_typeof(i->'end')<>'number'
        or (i->>'start')::numeric<570 or (i->>'end')::numeric>1560 or (i->>'end')::numeric<=(i->>'start')::numeric then raise exception 'Invalid item'; end if;
    end loop;
   end if;
   prev_end:=e;
  end loop;
  if wd=2 and calls<>1 then raise exception 'Tuesday call is required'; end if;
 end loop;
 return new; end $function$;
