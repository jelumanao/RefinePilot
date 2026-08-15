alter table public.licenses add column if not exists customer_name text;
alter table public.licenses add column if not exists customer_note text;
alter table public.licenses add column if not exists license_key_encrypted bytea;

alter table private_refinepilot.config add column if not exists admin_key_hash text;

create or replace function public.rp_verify_admin_key_hash(p_hash text)
returns boolean
language sql
security definer
set search_path = ''
as $$
    select coalesce((select admin_key_hash = p_hash from private_refinepilot.config where singleton = true), false);
$$;

create or replace function public.rp_admin_set_license_secret(p_license_id uuid, p_license_key text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_pepper text;
begin
    select license_pepper into v_pepper from private_refinepilot.config where singleton = true;
    if v_pepper is null then return false; end if;
    update public.licenses
       set license_key_encrypted = extensions.pgp_sym_encrypt(p_license_key, v_pepper, 'cipher-algo=aes256,compress-algo=0')
     where id = p_license_id;
    return found;
end;
$$;

create or replace function public.rp_admin_list_licenses(p_search text default '')
returns table (
    id uuid,
    license_key text,
    customer_name text,
    customer_note text,
    license_type text,
    status text,
    created_at timestamptz,
    activated_at timestamptz,
    expires_at timestamptz,
    device_limit integer,
    device_count bigint,
    last_verified_at timestamptz
)
language sql
security definer
set search_path = ''
as $$
    with cfg as (
        select license_pepper from private_refinepilot.config where singleton = true
    ), base as (
        select
            l.*,
            case
                when l.license_key_encrypted is null then null
                else extensions.pgp_sym_decrypt(l.license_key_encrypted, cfg.license_pepper)
            end as decrypted_key,
            case
                when l.status not in ('revoked','suspended') and l.expires_at is not null and l.expires_at <= clock_timestamp() then 'expired'
                else l.status
            end as effective_status
        from public.licenses l cross join cfg
    )
    select
        b.id,
        b.decrypted_key,
        coalesce(b.customer_name, ''),
        coalesce(b.customer_note, ''),
        b.license_type,
        b.effective_status,
        b.created_at,
        b.activated_at,
        b.expires_at,
        b.device_limit,
        count(i.id) filter (where i.revoked_at is null) as device_count,
        b.last_verified_at
    from base b
    left join public.installations i on i.license_id = b.id
    where trim(coalesce(p_search, '')) = ''
       or coalesce(b.customer_name, '') ilike '%' || trim(p_search) || '%'
       or coalesce(b.customer_note, '') ilike '%' || trim(p_search) || '%'
       or coalesce(b.decrypted_key, '') ilike '%' || upper(trim(p_search)) || '%'
       or b.license_type ilike '%' || trim(p_search) || '%'
       or b.effective_status ilike '%' || trim(p_search) || '%'
    group by b.id, b.decrypted_key, b.customer_name, b.customer_note, b.license_type, b.effective_status, b.created_at, b.activated_at, b.expires_at, b.device_limit, b.last_verified_at
    order by b.created_at desc;
$$;

revoke execute on function public.rp_verify_admin_key_hash(text) from public, anon, authenticated;
revoke execute on function public.rp_admin_set_license_secret(uuid, text) from public, anon, authenticated;
revoke execute on function public.rp_admin_list_licenses(text) from public, anon, authenticated;
grant execute on function public.rp_verify_admin_key_hash(text) to service_role;
grant execute on function public.rp_admin_set_license_secret(uuid, text) to service_role;
grant execute on function public.rp_admin_list_licenses(text) to service_role;
