#!/usr/bin/env bash
# Host-only helper. Excluded from the generated Magisk ZIP.
set -euo pipefail
VQ_PACKAGE=com.sinch.vqprobe
VQ_MODULE=sinch_vq_privileged
vq_fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
vq_adb() {
  python3 - "$@" <<'PY'
import subprocess, sys
try:
    raise SystemExit(subprocess.run(sys.argv[1:], timeout=90).returncode)
except subprocess.TimeoutExpired:
    print('ERROR: adb command exceeded 90 seconds; check USB and the Magisk root prompt.', file=sys.stderr)
    raise SystemExit(124)
PY
}
vq_setup() {
  command -v adb >/dev/null || vq_fail 'adb missing; install Android SDK Platform Tools.'
  command -v python3 >/dev/null || vq_fail 'python3 missing.'
  VQ_ADB=(adb)
  if [[ -n "${VQ_SERIAL:-}" ]]; then VQ_ADB+=(-s "$VQ_SERIAL"); fi
  [[ "$(vq_adb "${VQ_ADB[@]}" get-state 2>/dev/null | tr -d '\r')" == device ]] ||
    vq_fail 'No unique authorized Android device. Supply --serial, enable USB debugging, and authorize this host.'
  local current_user
  current_user=$(vq_adb "${VQ_ADB[@]}" shell am get-current-user | tr -d '\r')
  [[ "$current_user" == 0 ]] || vq_fail 'This engineering installer currently supports the dedicated owner user 0 only.'
}
vq_shell() {
  local command_text
  command_text=$(python3 - "$@" <<'PY'
import shlex, sys
print(shlex.join(sys.argv[1:]))
PY
)
  vq_adb "${VQ_ADB[@]}" shell "$command_text"
}
vq_require_root() {
  [[ "$(vq_shell su -c 'id -u' 2>/dev/null | tr -d '\r')" == 0 ]] ||
    vq_fail 'Existing Magisk root is required. Approve the shell root prompt on this dedicated handset; rooting is not performed.'
}
vq_output_dir() {
  umask 077
  [[ ! -e "$VQ_OUTPUT" ]] || vq_fail "Output already exists: $VQ_OUTPUT (choose a new evidence directory)."
  mkdir -p "$VQ_OUTPUT"
  VQ_OUTPUT=$(cd "$VQ_OUTPUT" && pwd)
}
vq_capture() {
  local destination=$1
  shift
  if ! vq_shell "$@" >"$VQ_OUTPUT/$destination" 2>"$VQ_OUTPUT/$destination.stderr"; then
    printf 'unavailable: %s\n' "$destination" >>"$VQ_OUTPUT/unavailable.txt"
  fi
}
