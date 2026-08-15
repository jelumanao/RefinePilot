create extension if not exists pgcrypto;

create table if not exists public.licenses (
    id uuid primary key default gen_random_uuid(),
    license_hash text not null unique,
    license_type text not null check (license_type in ('lifetime','monthly','trial')),
    status text not null default 'unused' check (status in ('unused','active','expired','suspended','revoked')),
    duration_days integer null check (duration_days is null or duration_days > 0),
    created_at timestamptz not null default now(),
    activated_at timestamptz null,
    expires_at timestamptz null,
    device_limit integer not null default 1 check (device_limit > 0),
    last_verified_at timestamptz null
);

create table if not exists public.installations (
    id uuid primary key default gen_random_uuid(),
    license_id uuid not null references public.licenses(id) on delete cascade,
    installation_identifier_hash text not null,
    installation_token_hash text not null unique,
    activated_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    revoked_at timestamptz null,
    unique (license_id, installation_identifier_hash)
);

create index if not exists installations_license_active_idx
    on public.installations (license_id)
    where revoked_at is null;

create table if not exists public.audit_events (
    id bigint generated always as identity primary key,
    license_id uuid null references public.licenses(id) on delete set null,
    action text not null,
    details jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table if not exists public.license_rate_limits (
    subject_hash text primary key,
    window_started_at timestamptz not null,
    attempts integer not null default 0
);

alter table public.licenses enable row level security;
alter table public.installations enable row level security;
alter table public.audit_events enable row level security;
alter table public.license_rate_limits enable row level security;

revoke all on public.licenses from anon, authenticated;
revoke all on public.installations from anon, authenticated;
revoke all on public.audit_events from anon, authenticated;
revoke all on public.license_rate_limits from anon, authenticated;

create or replace function public.rp_consume_rate_limit(
    p_subject_hash text,
    p_window_seconds integer default 900,
    p_max_attempts integer default 10
) returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_now timestamptz := clock_timestamp();
    v_row public.license_rate_limits%rowtype;
begin
    insert into public.license_rate_limits(subject_hash, window_started_at, attempts)
    values (p_subject_hash, v_now, 1)
    on conflict (subject_hash) do update set
        window_started_at = case
            when public.license_rate_limits.window_started_at + make_interval(secs => p_window_seconds) <= v_now then v_now
            else public.license_rate_limits.window_started_at
        end,
        attempts = case
            when public.license_rate_limits.window_started_at + make_interval(secs => p_window_seconds) <= v_now then 1
            else public.license_rate_limits.attempts + 1
        end
    returning * into v_row;

    return v_row.attempts <= p_max_attempts;
end;
$$;

create or replace function public.rp_activate_license(
    p_license_hash text,
    p_installation_hash text,
    p_token_hash text
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_license public.licenses%rowtype;
    v_existing public.installations%rowtype;
    v_active_count integer;
    v_now timestamptz := clock_timestamp();
begin
    select * into v_license
    from public.licenses
    where license_hash = p_license_hash
    for update;

    if not found then
        return jsonb_build_object('ok', false, 'code', 'invalid_license');
    end if;

    if v_license.status = 'revoked' then return jsonb_build_object('ok', false, 'code', 'revoked'); end if;
    if v_license.status = 'suspended' then return jsonb_build_object('ok', false, 'code', 'suspended'); end if;

    if v_license.expires_at is not null and v_license.expires_at <= v_now then
        update public.licenses set status = 'expired' where id = v_license.id;
        return jsonb_build_object('ok', false, 'code', 'expired');
    end if;

    select * into v_existing
    from public.installations
    where license_id = v_license.id
      and installation_identifier_hash = p_installation_hash
      and revoked_at is null
    limit 1;

    if found then
        update public.installations
        set installation_token_hash = p_token_hash, last_seen_at = v_now
        where id = v_existing.id;
        update public.licenses set last_verified_at = v_now where id = v_license.id;
        return jsonb_build_object(
            'ok', true,
            'code', 'active',
            'plan', v_license.license_type,
            'status', 'active',
            'expires_at', v_license.expires_at
        );
    end if;

    select count(*) into v_active_count
    from public.installations
    where license_id = v_license.id and revoked_at is null;

    if v_active_count >= v_license.device_limit then
        return jsonb_build_object('ok', false, 'code', 'device_limit');
    end if;

    if v_license.activated_at is null then
        v_license.activated_at := v_now;
        if v_license.license_type in ('monthly','trial') and v_license.expires_at is null then
            v_license.expires_at := v_now + make_interval(days => coalesce(v_license.duration_days, case when v_license.license_type = 'monthly' then 30 else 7 end));
        end if;
    end if;

    insert into public.installations(
        license_id, installation_identifier_hash, installation_token_hash, activated_at, last_seen_at
    ) values (
        v_license.id, p_installation_hash, p_token_hash, v_now, v_now
    );

    update public.licenses
    set status = 'active',
        activated_at = coalesce(activated_at, v_license.activated_at),
        expires_at = v_license.expires_at,
        last_verified_at = v_now
    where id = v_license.id;

    insert into public.audit_events(license_id, action, details)
    values (v_license.id, 'activate', jsonb_build_object('installation_hash', p_installation_hash));

    return jsonb_build_object(
        'ok', true,
        'code', 'active',
        'plan', v_license.license_type,
        'status', 'active',
        'expires_at', v_license.expires_at
    );
end;
$$;

create or replace function public.rp_verify_installation(
    p_installation_hash text,
    p_token_hash text
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_install public.installations%rowtype;
    v_license public.licenses%rowtype;
    v_now timestamptz := clock_timestamp();
begin
    select * into v_install
    from public.installations
    where installation_identifier_hash = p_installation_hash
      and installation_token_hash = p_token_hash
      and revoked_at is null
    limit 1;

    if not found then return jsonb_build_object('ok', false, 'code', 'invalid_installation'); end if;

    select * into v_license from public.licenses where id = v_install.license_id for update;
    if not found then return jsonb_build_object('ok', false, 'code', 'invalid_license'); end if;
    if v_license.status = 'revoked' then return jsonb_build_object('ok', false, 'code', 'revoked'); end if;
    if v_license.status = 'suspended' then return jsonb_build_object('ok', false, 'code', 'suspended'); end if;

    if v_license.expires_at is not null and v_license.expires_at <= v_now then
        update public.licenses set status = 'expired' where id = v_license.id;
        return jsonb_build_object('ok', false, 'code', 'expired');
    end if;

    update public.installations set last_seen_at = v_now where id = v_install.id;
    update public.licenses set last_verified_at = v_now, status = 'active' where id = v_license.id;

    return jsonb_build_object(
        'ok', true,
        'code', 'active',
        'plan', v_license.license_type,
        'status', 'active',
        'expires_at', v_license.expires_at
    );
end;
$$;

revoke execute on function public.rp_consume_rate_limit(text, integer, integer) from public, anon, authenticated;
revoke execute on function public.rp_activate_license(text, text, text) from public, anon, authenticated;
revoke execute on function public.rp_verify_installation(text, text) from public, anon, authenticated;
grant execute on function public.rp_consume_rate_limit(text, integer, integer) to service_role;
grant execute on function public.rp_activate_license(text, text, text) to service_role;
grant execute on function public.rp_verify_installation(text, text) to service_role;
