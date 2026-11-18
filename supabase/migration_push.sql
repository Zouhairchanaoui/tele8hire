create extension if not exists pg_net with schema extensions;

create table if not exists public.device_tokens (
  token text primary key,
  user_id uuid not null references public.profiles(id) on delete cascade,
  platform text not null default 'android',
  updated_at timestamptz not null default now()
);
create index if not exists device_tokens_user_idx on public.device_tokens(user_id);
alter table public.device_tokens enable row level security;

create table if not exists public.push_log (
  message_id uuid primary key,
  created_at timestamptz not null default now()
);
alter table public.push_log enable row level security;

create or replace function public.register_device_token(t text, p text default 'android')
returns void language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  if t is null or length(t) < 20 or length(t) > 4096 then raise exception 'invalid_token'; end if;
  insert into public.device_tokens(token, user_id, platform, updated_at)
  values (t, auth.uid(), coalesce(p,'android'), now())
  on conflict (token) do update set user_id = auth.uid(), platform = excluded.platform, updated_at = now();
end $$;

create or replace function public.unregister_device_token(t text)
returns void language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return; end if;
  delete from public.device_tokens where token = t and user_id = auth.uid();
end $$;

revoke all on function public.register_device_token(text,text) from public, anon;
revoke all on function public.unregister_device_token(text) from public, anon;
grant execute on function public.register_device_token(text,text) to authenticated;
grant execute on function public.unregister_device_token(text) to authenticated;

create or replace function public.notify_new_message()
returns trigger language plpgsql security definer set search_path = public, extensions as $$
begin
  begin
    perform net.http_post(
      url := 'https://dtstsjsgujtfejpogufp.supabase.co/functions/v1/push-message',
      headers := '{"Content-Type":"application/json"}'::jsonb,
      body := jsonb_build_object('message_id', new.id),
      timeout_milliseconds := 5000
    );
  exception when others then
    null;
  end;
  return new;
end $$;
revoke all on function public.notify_new_message() from public, anon, authenticated;

create or replace trigger trg_notify_new_message
  after insert on public.messages
  for each row execute function public.notify_new_message();
