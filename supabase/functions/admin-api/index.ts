import { createClient } from 'npm:@supabase/supabase-js@2'

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'content-type, x-admin-key',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
}
const jsonHeaders = {
  'Content-Type': 'application/json; charset=utf-8',
  'Cache-Control': 'no-store',
  ...corsHeaders,
}

function response(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), { status, headers: jsonHeaders })
}

function randomSegment(length = 4) {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  const bytes = crypto.getRandomValues(new Uint8Array(length))
  return Array.from(bytes, b => alphabet[b % alphabet.length]).join('')
}

function randomLicenseKey() {
  return `RP-${randomSegment()}-${randomSegment()}-${randomSegment()}`
}

async function sha256Hex(value: string) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('')
}

async function hmacHex(secret: string, value: string) {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  )
  const signature = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(value))
  return Array.from(new Uint8Array(signature), b => b.toString(16).padStart(2, '0')).join('')
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: corsHeaders })
  }
  if (req.method !== 'POST') return response(405, { ok: false, code: 'method_not_allowed' })

  const supabaseUrl = Deno.env.get('SUPABASE_URL')
  const serviceRole = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')
  if (!supabaseUrl || !serviceRole) return response(503, { ok: false, code: 'server_configuration_error' })

  const supabase = createClient(supabaseUrl, serviceRole, {
    auth: { persistSession: false, autoRefreshToken: false },
  })

  const suppliedAdminKey = req.headers.get('x-admin-key')?.trim() ?? ''
  if (!suppliedAdminKey || suppliedAdminKey.length < 20 || suppliedAdminKey.length > 128) {
    return response(401, { ok: false, code: 'unauthorized' })
  }

  const configuredAdminKey = Deno.env.get('ADMIN_API_KEY')?.trim() ?? ''
  let authorized = false
  if (configuredAdminKey) {
    authorized = suppliedAdminKey === configuredAdminKey
  } else {
    const adminHash = await sha256Hex(suppliedAdminKey)
    const { data, error } = await supabase.rpc('rp_verify_admin_key_hash', { p_hash: adminHash })
    authorized = !error && data === true
  }
  if (!authorized) return response(401, { ok: false, code: 'unauthorized' })

  let pepper = Deno.env.get('LICENSE_PEPPER') ?? ''
  if (!pepper) {
    const { data, error } = await supabase.rpc('rp_get_license_pepper')
    if (error || typeof data !== 'string' || data.length < 32) {
      return response(503, { ok: false, code: 'server_configuration_error' })
    }
    pepper = data
  }

  const path = new URL(req.url).pathname.split('/').filter(Boolean).at(-1) ?? ''
  const body = await req.json().catch(() => ({} as Record<string, unknown>))
  const now = new Date()

  if (path === 'list') {
    const search = String(body.search ?? '').trim().slice(0, 160)
    const { data, error } = await supabase.rpc('rp_admin_list_licenses', { p_search: search })
    if (error) return response(503, { ok: false, code: 'server_error' })
    return response(200, { ok: true, licenses: data ?? [] })
  }

  if (path === 'create') {
    const plan = String(body.plan ?? 'lifetime').toLowerCase()
    if (!['lifetime', 'monthly', 'trial'].includes(plan)) return response(400, { ok: false, code: 'invalid_plan' })

    const customerName = String(body.customer_name ?? '').trim().slice(0, 120)
    const customerNote = String(body.customer_note ?? '').trim().slice(0, 1000)
    const deviceLimit = Math.min(Math.max(Math.trunc(Number(body.device_limit ?? 1)) || 1, 1), 10)
    const durationDays = plan === 'lifetime'
      ? null
      : Math.min(Math.max(Math.trunc(Number(body.duration_days ?? (plan === 'monthly' ? 30 : 7))) || 1, 1), 3650)

    for (let attempt = 0; attempt < 5; attempt++) {
      const key = randomLicenseKey()
      const licenseHash = await hmacHex(pepper, `license:${key}`)
      const { data, error } = await supabase.from('licenses').insert({
        license_hash: licenseHash,
        license_type: plan,
        status: 'unused',
        duration_days: durationDays,
        device_limit: deviceLimit,
        customer_name: customerName || null,
        customer_note: customerNote || null,
      }).select('id, license_type, status, device_limit, created_at, customer_name, customer_note').single()

      if (!error && data) {
        const { data: secretSaved, error: secretError } = await supabase.rpc('rp_admin_set_license_secret', {
          p_license_id: data.id,
          p_license_key: key,
        })
        if (secretError || secretSaved !== true) {
          await supabase.from('licenses').delete().eq('id', data.id)
          return response(503, { ok: false, code: 'server_error' })
        }
        await supabase.from('audit_events').insert({
          license_id: data.id,
          action: 'create_license',
          details: { customer_name: customerName, plan },
        })
        return response(200, { ok: true, license_key: key, ...data })
      }
      if (error?.code !== '23505') return response(503, { ok: false, code: 'server_error' })
    }
    return response(503, { ok: false, code: 'generation_failed' })
  }

  const licenseId = String(body.license_id ?? '').trim()
  if (!/^[0-9a-f-]{36}$/i.test(licenseId)) return response(400, { ok: false, code: 'invalid_license_id' })

  if (path === 'reset-device') {
    await supabase.from('installations').update({ revoked_at: now.toISOString() }).eq('license_id', licenseId).is('revoked_at', null)
    await supabase.from('licenses')
      .update({ status: 'unused', activated_at: null, expires_at: null, last_verified_at: null })
      .eq('id', licenseId)
      .neq('status', 'revoked')
    await supabase.from('audit_events').insert({ license_id: licenseId, action: 'reset_device' })
    return response(200, { ok: true })
  }

  if (path === 'suspend') {
    await supabase.from('licenses').update({ status: 'suspended' }).eq('id', licenseId).neq('status', 'revoked')
    await supabase.from('audit_events').insert({ license_id: licenseId, action: 'suspended' })
    return response(200, { ok: true, status: 'suspended' })
  }

  if (path === 'revoke') {
    await supabase.from('licenses').update({ status: 'revoked' }).eq('id', licenseId)
    await supabase.from('installations').update({ revoked_at: now.toISOString() }).eq('license_id', licenseId).is('revoked_at', null)
    await supabase.from('audit_events').insert({ license_id: licenseId, action: 'revoked' })
    return response(200, { ok: true, status: 'revoked' })
  }

  return response(404, { ok: false, code: 'not_found' })
})
