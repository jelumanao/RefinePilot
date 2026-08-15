create schema if not exists private_refinepilot;
revoke all on schema private_refinepilot from public, anon, authenticated;

create table if not exists private_refinepilot.config (
    singleton boolean primary key default true check (singleton),
    license_pepper text not null,
    created_at timestamptz not null default now()
);

insert into private_refinepilot.config(singleton, license_pepper)
values (true, encode(gen_random_bytes(32), 'hex'))
on conflict (singleton) do nothing;

revoke all on private_refinepilot.config from public, anon, authenticated;

create or replace function public.rp_get_license_pepper()
returns text
language sql
security definer
set search_path = ''
as $$
    select license_pepper from private_refinepilot.config where singleton = true;
$$;

revoke execute on function public.rp_get_license_pepper() from public, anon, authenticated;
grant execute on function public.rp_get_license_pepper() to service_role;
