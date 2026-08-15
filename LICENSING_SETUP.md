# RefinePilot Licensing Setup

This repository contains the Android licensing gate plus a Supabase-backed license service. The refining automation itself is unchanged.

## 1. Create a Supabase project

Install the Supabase CLI and log in, then link this repository to the project.

```bash
supabase login
supabase link --project-ref YOUR_PROJECT_REF
supabase db push
```

The migration creates `licenses`, `installations`, `audit_events`, and rate-limit state. RLS is enabled and client roles receive no direct table access.

## 2. Configure Edge Function secrets

Generate two strong random secrets locally. Do not commit them.

```bash
supabase secrets set LICENSE_PEPPER="YOUR_LONG_RANDOM_PEPPER"
supabase secrets set ADMIN_API_KEY="YOUR_LONG_RANDOM_ADMIN_KEY"
```

Deploy the API:

```bash
supabase functions deploy license-api --no-verify-jwt
```

The Android API base URL is:

```text
https://YOUR_PROJECT_REF.supabase.co/functions/v1/license-api
```

## 3. Configure GitHub repository variable

In GitHub:

`Settings → Secrets and variables → Actions → Variables → New repository variable`

Create:

```text
REFINEPILOT_LICENSE_API_URL=https://YOUR_PROJECT_REF.supabase.co/functions/v1/license-api
```

If this variable is absent, builds remain in development mode and skip license enforcement so existing RefinePilot testing is not locked out accidentally.

## 4. Configure permanent Android release signing

Create a permanent keystore once and keep an offline backup. Never regenerate it for normal updates.

Required GitHub Actions secrets:

```text
REFINEPILOT_KEYSTORE_B64
REFINEPILOT_KEYSTORE_PASSWORD
REFINEPILOT_KEY_ALIAS
REFINEPILOT_KEY_PASSWORD
```

`REFINEPILOT_KEYSTORE_B64` is the base64 representation of the permanent `.jks` file. Do not commit the keystore or passwords to this repository.

When all four signing secrets exist, GitHub Actions additionally produces `RefinePilot-v0.3-production-apk`. Otherwise it produces only the development APK.

## 5. Admin license management

The admin CLI uses only environment variables and Python's standard library.

```bash
export REFINEPILOT_LICENSE_API_URL="https://YOUR_PROJECT_REF.supabase.co/functions/v1/license-api"
export REFINEPILOT_ADMIN_API_KEY="YOUR_ADMIN_API_KEY"
```

Create licenses:

```bash
python admin/refinepilot_admin.py create --plan lifetime
python admin/refinepilot_admin.py create --plan monthly --days 30
python admin/refinepilot_admin.py create --plan trial --days 7
```

Reset a customer's registered device:

```bash
python admin/refinepilot_admin.py reset-device LICENSE_UUID
```

Suspend or revoke:

```bash
python admin/refinepilot_admin.py suspend LICENSE_UUID
python admin/refinepilot_admin.py revoke LICENSE_UUID
```

Extend an expiring license:

```bash
python admin/refinepilot_admin.py extend LICENSE_UUID --days 30
```

## 6. Customer flow

1. Customer pays.
2. Owner creates a unique license key.
3. Customer installs the production APK.
4. Customer enters `RP-XXXX-XXXX-XXXX` on first launch.
5. Server atomically registers the app-scoped installation to the license.
6. RefinePilot opens only after successful authorization.
7. A license already bound to its device limit is rejected on another installation.
8. Owner can reset the device association if the customer legitimately changes phones.

## 7. Offline behavior

Successful verification grants a 72-hour offline grace period. The Android cache is encrypted with an AES key held by Android Keystore. Grace timing uses monotonic elapsed time and the current boot count; after a reboot, an online verification is required instead of trusting a user-editable wall clock.

## 8. Production checklist

- Deploy migration.
- Set `LICENSE_PEPPER` and `ADMIN_API_KEY` as Supabase secrets.
- Deploy `license-api`.
- Set GitHub `REFINEPILOT_LICENSE_API_URL` variable.
- Configure permanent GitHub signing secrets.
- Create one trial license and verify activation on Phone A.
- Confirm the same license is rejected on Phone B.
- Reset the license and confirm Phone B can then activate.
- Test revoke, suspend, expiry, offline grace, app data clear, and reinstall.
- Regression-test RefinePilot Start, Pause/Resume, Reset, Stop, target selection, overlay, Accessibility, screen capture, and Fine Burr reload.
