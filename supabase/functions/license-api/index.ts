import { createClient } from 'npm:@supabase/supabase-js@2'

const jsonHeaders = { 'Content-Type': 'application/json; charset=utf-8' }
const GRACE_SECONDS = 72 * 60 * 60

function response(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), { status, headers: jsonHeaders })
}

function normalizeLicenseKey(value: unknown) {
  return String(value ?? '').trim().toUpperCase()
}

function validLicenseFormat(value: string) {
  return /^RP-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(value)
}

function randomSegment(length = 4) {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  const bytes = crypto.getRandomValues(new Uint8Array(length))
  return Array.from(bytes, b => alphabet[b % alphabet.length]).join('')
}

function randomLicenseKey() {
  return `RP-${randomSegment()}-${randomSegment()}-${randomSegment()}`
}

function randomToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(32))
  return btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')
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

function safeCodeMessage(code: string) {
  switch (code) {
    case 'invalid_license': return 'Invalid activation key.'
    case 'device_limit': return 'This license is already activated on another device.'
    case 'expired': return 'This RefinePilot license has expired.'
    case 'revoked': return 'This RefinePilot license is disabled.'
    case 'suspended': return 'This RefinePilot license is temporarily suspended.'
    case 'invalid_installation': return 'This installation is not authorized.'
    default: return 'Unable to verify RefinePilot license.'
  }
}

Deno.serve(async (req) => {
  if (req.method !== 'POST') return response(405, { ok: false, code: 'method_not_allowed' })

  const supabaseUrl = Deno.env.get('SUPABASE_URL')
  const serviceRole = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')
  const pepper = Deno.env.get('LICENSE_PEPPER')
  const adminKey = Deno.env.get('ADMIN_API_KEY')
  if (!supabaseUrl || !serviceRole || !pepper || !adminKey) {
    return response(503, { ok: false, code: 'server_configuration_error', message: 'License service unavailable.' })
  }

  const supabase = createClient(supabaseUrl, serviceRole, {
    auth: { persistSession: false, autoRefreshToken: false },
  })
  const path = new URL(req.url).pathname.split('/').filter(Boolean).at(-1) ?? ''
  const body = await req.json().catch(() => ({} as Record<string, unknown>))
  const now = new Date()

  if (path === 'activate') {
    const licenseKey = normalizeLicenseKey(body.license_key)
    const installationId = String(body.installation_id ?? '').trim()
    if (!validLicenseFormat(licenseKey) || installationId.length < 16 || installationId.length > 128) {
      return response(400, { ok: false, code: 'invalid_request', message: 'Invalid activation request.' })
    }

    const forwarded = req.headers.get('x-forwarded-for')?.split(',')[0]?.trim() || 'unknown'
    const subjectHash = await hmacHex(pepper, `rate:${forwarded}`)
    const { data: allowed, error: rateError } = await supabase.rpc('rp_consume_rate_limit', {
      p_subject_hash: subjectHash,
      p_window_seconds: 900,
      p_max_attempts: 10,
    })
    if (rateError) return response(503, { ok: false, code: 'server_error', message: 'License service unavailable.' })
    if (!allowed) return response(429, { ok: false, code: 'rate_limited', message: 'Too many activation attempts. Please try again later.' })

    const licenseHash = await hmacHex(pepper, `license:${licenseKey}`)
    const installationHash = await hmacHex(pepper, `installation:${installationId}`)
    const rawToken = randomToken()
    const tokenHash = await hmacHex(pepper, `token:${rawToken}`)

    const { data, error } = await supabase.rpc('rp_activate_license', {
      p_license_hash: licenseHash,
      p_installation_hash: installationHash,
      p_token_hash: tokenHash,
    })
    if (error) return response(503, { ok: false, code: 'server_error', message: 'License service unavailable.' })
    const result = data as Record<string, unknown>
    if (!result?.ok) {
      const code = String(result?.code ?? 'invalid_license')
      return response(code === 'device_limit' ? 409 : 403, { ok: false, code, message: safeCodeMessage(code) })
    }

    return response(200, {
      ok: true,
      code: 'active',
      status: 'active',
      plan: result.plan,
      expires_at: result.expires_at ?? null,
      installation_token: rawToken,
      server_time_ms: now.getTime(),
      grace_seconds: GRACE_SECONDS,
    })
  }

  if (path === 'verify') {
    const installationId = String(body.installation_id ?? '').trim()
    const rawToken = String(body.installation_token ?? '').trim()
    if (installationId.length < 16 || installationId.length > 128 || rawToken.length < 32 || rawToken.length > 256) {
      return response(400, { ok: false, code: 'invalid_request', message: 'Invalid verification request.' })
    }

    const installationHash = await hmacHex(pepper, `installation:${installationId}`)
    const tokenHash = await hmacHex(pepper, `token:${rawToken}`)
    const { data, error } = await supabase.rpc('rp_verify_installation', {
      p_installation_hash: installationHash,
      p_token_hash: tokenHash,
    })
    if (error) return response(503, { ok: false, code: 'server_error', message: 'License service unavailable.' })
    const result = data as Record<string, unknown>
    if (!result?.ok) {
      const code = String(result?.code ?? 'invalid_installation')
      return response(403, { ok: false, code, message: safeCodeMessage(code) })
    }

    return response(200, {
      ok: true,
      code: 'active',
      status: 'active',
      plan: result.plan,
      expires_at: result.expires_at ?? null,
      server_time_ms: now.getTime(),
      grace_seconds: GRACE_SECONDS,
    })
  }

  if (!path.startsWith('admin-')) return response(404, { ok: false, code: 'not_found' })
  if (req.headers.get('x-admin-key') !== adminKey) return response(401, { ok: false, code: 'unauthorized' })

  if (path === 'admin-create') {
    const plan = String(body.plan ?? 'lifetime').toLowerCase()
    if (!['lifetime', 'monthly', 'trial'].includes(plan)) return response(400, { ok: false, code: 'invalid_plan' })
    const deviceLimit = Math.min(Math.max(Number(body.device_limit ?? 1), 1), 10)
    const durationDays = plan === 'lifetime' ? null : Math.min(Math.max(Number(body.duration_days ?? (plan === 'monthly' ? 30 : 7)), 1), 3650)

    for (let attempt = 0; attempt < 5; attempt++) {
      const key = randomLicenseKey()
      const licenseHash = await hmacHex(pepper, `license:${key}`)
      const { data, error } = await supabase.from('licenses').insert({
        license_hash: licenseHash,
        license_type: plan,
        status: 'unused',
        duration_days: durationDays,
        device_limit: deviceLimit,
      }).select('id, license_type, status, device_limit, created_at').single()
      if (!error && data) {
        await supabase.from('audit_events').insert({ license_id: data.id, action: 'create_license' })
        return response(200, { ok: true, license_key: key, ...data })
      }
      if (error?.code !== '23505') return response(503, { ok: false, code: 'server_error' })
    }
    return response(503, { ok: false, code: 'generation_failed' })
  }

  const licenseId = String(body.license_id ?? '').trim()
  if (!/^[0-9a-f-]{36}$/i.test(licenseId)) return response(400, { ok: false, code: 'invalid_license_id' })

  if (path === 'admin-reset-device') {
    await supabase.from('installations').update({ revoked_at: now.toISOString() }).eq('license_id', licenseId).is('revoked_at', null)
    await supabase.from('licenses').update({ status: 'unused', activated_at: null, expires_at: null, last_verified_at: null }).eq('id', licenseId).neq('status', 'revoked')
    await supabase.from('audit_events').insert({ license_id: licenseId, action: 'reset_device' })
    return response(200, { ok: true })
  }

  if (path === 'admin-revoke' || path === 'admin-suspend') {
    const status = path === 'admin-revoke' ? 'revoked' : 'suspended'
    await supabase.from('licenses').update({ status }).eq('id', licenseId)
    if (status === 'revoked') await supabase.from('installations').update({ revoked_at: now.toISOString() }).eq('license_id', licenseId).is('revoked_at', null)
    await supabase.from('audit_events').insert({ license_id: licenseId, action: status })
    return response(200, { ok: true, status })
  }

  if (path === 'admin-extend') {
    const days = Math.min(Math.max(Number(body.days ?? 30), 1), 3650)
    const { data: license, error } = await supabase.from('licenses').select('expires_at').eq('id', licenseId).single()
    if (error || !license) return response(404, { ok: false, code: 'not_found' })
    const base = license.expires_at ? Math.max(new Date(license.expires_at).getTime(), now.getTime()) : now.getTime()
    const newExpiry = new Date(base + days * 86400000).toISOString()
    await supabase.from('licenses').update({ expires_at: newExpiry, status: 'active' }).eq('id', licenseId)
    await supabase.from('audit_events').insert({ license_id: licenseId, action: 'extend', details: { days } })
    return response(200, { ok: true, expires_at: newExpiry })
  }

  return response(404, { ok: false, code: 'not_found' })
})
