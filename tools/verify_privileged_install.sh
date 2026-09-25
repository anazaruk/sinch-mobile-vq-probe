#!/usr/bin/env bash
set -euo pipefail
VQ_TOOL_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$VQ_TOOL_DIR/../privileged-module/host-common.sh"
VQ_SERIAL= VQ_GRANT=0 VQ_APPOP=0
usage() { printf '%s\n' 'Usage: tools/verify_privileged_install.sh [--serial SERIAL] [--grant-runtime] [--allow-record-appop]' 'Read-only by default. Owner user 0 only. --grant-runtime explicitly grants the declared phone, recording, location and notification runtime permissions.' '--allow-record-appop explicitly sets only this package RECORD_AUDIO AppOp to allow; normally leave it unchanged.'; }
while (($#)); do
  case "$1" in
    --serial) [[ $# -ge 2 ]] || vq_fail '--serial requires a value'; VQ_SERIAL=$2; shift 2 ;;
    --grant-runtime) VQ_GRANT=1; shift ;;
    --allow-record-appop) VQ_APPOP=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; vq_fail "Unknown argument: $1" ;;
  esac
done
vq_setup
VQ_TMP=$(mktemp -d)
trap 'rm -rf -- "$VQ_TMP"' EXIT
vq_shell dumpsys package "$VQ_PACKAGE" >"$VQ_TMP/package.txt"
python3 - "$VQ_TMP/package.txt" <<'PY'
import re, sys
text = open(sys.argv[1]).read().split('Hidden system packages:')[0]
checks = {
    'package_present': bool(re.search(r'Package \[com\.sinch\.vqprobe\]', text)),
    'system_flag': bool(re.search(r'(?:pkgFlags|flags)=\[[^\]]*\bSYSTEM\b', text)),
    'privileged_flag': bool(re.search(r'privateFlags=\[[^\]]*\b(?:PRIVATE_FLAG_)?PRIVILEGED\b', text)),
    'installed_for_user_0': bool(re.search(r'User 0:[^\n]*\binstalled=true\b', text)),
}
for short in ('MODIFY_PHONE_STATE', 'CAPTURE_AUDIO_OUTPUT', 'READ_PRECISE_PHONE_STATE', 'READ_PRIVILEGED_PHONE_STATE'):
    checks[short] = bool(re.search(r'^\s*android\.permission\.' + short + r':\s*granted=true\b', text, re.M))
for check, ok in checks.items():
    print(('PASS ' if ok else 'FAIL ') + check)
if not all(checks.values()):
    print('ERROR: Privileged installation is not verified. Reboot after module installation, inspect the same-partition allowlist and active package flags. Runtime pm grant cannot grant signature|privileged permissions.', file=sys.stderr)
    raise SystemExit(1)
PY
if [[ "$VQ_GRANT" == 1 ]]; then
  printf '%s\n' 'Applying explicitly requested runtime grants to com.sinch.vqprobe, user 0.'
  for permission in CALL_PHONE READ_PHONE_STATE RECORD_AUDIO ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION; do
    vq_shell pm grant --user 0 "$VQ_PACKAGE" "android.permission.$permission" || vq_fail "Runtime grant failed: $permission"
  done
  VQ_API=$(vq_shell getprop ro.build.version.sdk | tr -d '\r')
  if [[ "$VQ_API" -ge 33 ]]; then
    vq_shell pm grant --user 0 "$VQ_PACKAGE" android.permission.POST_NOTIFICATIONS || vq_fail 'Notification permission grant failed.'
  fi
fi
if [[ "$VQ_APPOP" == 1 ]]; then
  printf '%s\n' 'Applying explicit package-specific RECORD_AUDIO AppOp allow. No other AppOp is modified.'
  vq_shell cmd appops set --user 0 "$VQ_PACKAGE" RECORD_AUDIO allow || vq_fail 'RECORD_AUDIO AppOp change failed.'
fi
vq_shell dumpsys package "$VQ_PACKAGE" >"$VQ_TMP/package.txt"
VQ_STATUS=0
python3 - "$VQ_TMP/package.txt" <<'PY' || VQ_STATUS=$?
import re, sys
text = open(sys.argv[1]).read().split('Hidden system packages:')[0]
match = re.search(r'^\s*User 0:.*?(?=^\s*User \d+:|\Z)', text, re.M | re.S)
user = match.group(0) if match else ''
ok = True
for short in ('CALL_PHONE', 'READ_PHONE_STATE', 'RECORD_AUDIO'):
    granted = bool(re.search(r'^\s*android\.permission\.' + short + r':\s*granted=true\b', user, re.M))
    print(('PASS ' if granted else 'FAIL ') + short + ' runtime permission for user 0')
    ok &= granted
for short in ('ACCESS_FINE_LOCATION', 'ACCESS_BACKGROUND_LOCATION', 'POST_NOTIFICATIONS'):
    granted = bool(re.search(r'^\s*android\.permission\.' + short + r':\s*granted=true\b', user, re.M))
    print(('PASS ' if granted else 'NOTE ') + short + (' granted' if granted else ' not granted/not applicable; configure in app/settings if needed'))
if not ok:
    print('Grant required runtime permissions in the app, or rerun with --grant-runtime for the dedicated owner device.', file=sys.stderr)
    raise SystemExit(2)
PY
vq_shell cmd appops get --user 0 "$VQ_PACKAGE" RECORD_AUDIO >"$VQ_TMP/appop.txt" 2>&1 || VQ_STATUS=2
cat "$VQ_TMP/appop.txt"
if grep -Eq 'RECORD_AUDIO:[[:space:]]*(ignore|deny|errored)' "$VQ_TMP/appop.txt"; then
  printf '%s\n' 'FAIL RECORD_AUDIO AppOp blocks recording. Review Android permission settings; --allow-record-appop changes only this operation explicitly.'
  VQ_STATUS=2
fi
vq_shell cmd role get-role-holders --user 0 android.app.role.DIALER >"$VQ_TMP/dialer.txt" 2>&1 || true
if grep -Fxq "$VQ_PACKAGE" "$VQ_TMP/dialer.txt"; then
  printf '%s\n' 'PASS default dialer role'
else
  printf '%s\n' 'FAIL default dialer role: open Sinch Probe and tap Request Default Dialer Role.'
  VQ_STATUS=2
fi
printf '%s\n' 'Permission success is a prerequisite only. It does not prove Telephony TX routing, non-silent downlink capture, or far-end digital audio.'
exit "$VQ_STATUS"
