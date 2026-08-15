#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def call(endpoint, payload):
    base = os.environ.get("REFINEPILOT_LICENSE_API_URL", "").rstrip("/")
    admin_key = os.environ.get("REFINEPILOT_ADMIN_API_KEY", "")
    if not base or not admin_key:
        raise SystemExit("Set REFINEPILOT_LICENSE_API_URL and REFINEPILOT_ADMIN_API_KEY first.")

    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        f"{base}/{endpoint}",
        data=data,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "x-admin-key": admin_key,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise SystemExit(f"Admin request failed ({exc.code}): {body}") from exc


def main():
    parser = argparse.ArgumentParser(description="RefinePilot license administration")
    sub = parser.add_subparsers(dest="command", required=True)

    create = sub.add_parser("create")
    create.add_argument("--plan", choices=["lifetime", "monthly", "trial"], default="lifetime")
    create.add_argument("--days", type=int)
    create.add_argument("--devices", type=int, default=1)

    for name in ("reset-device", "revoke", "suspend"):
        cmd = sub.add_parser(name)
        cmd.add_argument("license_id")

    extend = sub.add_parser("extend")
    extend.add_argument("license_id")
    extend.add_argument("--days", type=int, default=30)

    args = parser.parse_args()

    if args.command == "create":
        payload = {"plan": args.plan, "device_limit": args.devices}
        if args.days is not None:
            payload["duration_days"] = args.days
        result = call("admin-create", payload)
    elif args.command == "reset-device":
        result = call("admin-reset-device", {"license_id": args.license_id})
    elif args.command == "revoke":
        result = call("admin-revoke", {"license_id": args.license_id})
    elif args.command == "suspend":
        result = call("admin-suspend", {"license_id": args.license_id})
    else:
        result = call("admin-extend", {"license_id": args.license_id, "days": args.days})

    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
