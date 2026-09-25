#!/usr/bin/env bash
set -euo pipefail
VQ_TOOL_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$VQ_TOOL_DIR/../privileged-module/host-common.sh"
VQ_SERIAL= VQ_ROOT=0 VQ_OUTPUT= VQ_LOGCAT_LINES=4000
usage() { printf '%s\n' 'Usage: tools/capture_audio_debug.sh --output NEW_DIR [--serial SERIAL] [--root] [--logcat-lines N]' 'Read-only, bounded snapshot for an active TX/RX test. Captures audio policy/mixer XML, dumpsys, audio/telecom logs and SELinux denials. Root additionally allows read-only tinymix/dmesg.' 'Evidence may contain phone numbers and device identifiers; retain it within the engineering team.'; }
while (($#)); do
  case "$1" in
    --serial) [[ $# -ge 2 ]] || vq_fail '--serial requires a value'; VQ_SERIAL=$2; shift 2 ;;
    --output) [[ $# -ge 2 ]] || vq_fail '--output requires a value'; VQ_OUTPUT=$2; shift 2 ;;
    --root) VQ_ROOT=1; shift ;;
    --logcat-lines) [[ $# -ge 2 ]] || vq_fail '--logcat-lines requires a value'; VQ_LOGCAT_LINES=$2; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; vq_fail "Unknown argument: $1" ;;
  esac
done
[[ -n "$VQ_OUTPUT" ]] || vq_fail '--output NEW_DIR is required.'
[[ "$VQ_LOGCAT_LINES" =~ ^[0-9]+$ && "$VQ_LOGCAT_LINES" -ge 100 && "$VQ_LOGCAT_LINES" -le 20000 ]] || vq_fail '--logcat-lines must be 100..20000.'
VQ_ARGS=(--output "$VQ_OUTPUT")
[[ -z "$VQ_SERIAL" ]] || VQ_ARGS+=(--serial "$VQ_SERIAL")
[[ "$VQ_ROOT" == 0 ]] || VQ_ARGS+=(--root)
bash "$VQ_TOOL_DIR/check_device_capabilities.sh" "${VQ_ARGS[@]}" >/dev/null
vq_setup
VQ_OUTPUT=$(cd "$VQ_OUTPUT" && pwd)
vq_capture audio-flinger.txt dumpsys media.audio_flinger
vq_capture telecom.txt dumpsys telecom
vq_capture telephony-registry.txt dumpsys telephony.registry
vq_capture recording-services.txt dumpsys activity services "$VQ_PACKAGE"
vq_capture sensor-privacy.txt dumpsys sensor_privacy
vq_capture audio-telecom-logcat.txt logcat -b all -d -v threadtime -t "$VQ_LOGCAT_LINES" \
  'AudioFlinger:V' 'AudioPolicyManager:V' 'AudioPolicyService:V' 'AudioRecord:V' 'AudioTrack:V' \
  'Telecom:V' 'Telephony:V' 'SinchVQ:V' '*:S'
if [[ "$VQ_ROOT" == 1 ]]; then
  vq_capture avc-source.tmp su -c "logcat -b all -d -v threadtime -t $VQ_LOGCAT_LINES"
  vq_capture kernel-source.tmp su -c 'dmesg | tail -n 4000'
  # tinymix without control/value arguments ONLY lists current controls; there are no writes.
  vq_capture mixer-controls.txt su -c 'if command -v tinymix >/dev/null 2>&1; then tinymix; elif [ -x /vendor/bin/tinymix ]; then /vendor/bin/tinymix; else printf "tinymix unavailable\n"; fi'
else
  vq_capture avc-source.tmp logcat -b all -d -v threadtime -t "$VQ_LOGCAT_LINES"
fi
python3 - "$VQ_OUTPUT" <<'PY'
from pathlib import Path
import datetime, hashlib, json, sys
root = Path(sys.argv[1])
denials = []
for filename in ('avc-source.tmp', 'kernel-source.tmp'):
    path = root / filename
    if path.exists():
        denials.extend(line for line in path.read_text(errors='replace').splitlines()
                       if 'avc:' in line.lower() or 'selinux' in line.lower())
        path.unlink()  # Keep relevant denials, not the unfiltered all-app log snapshot.
(root / 'selinux-denials.txt').write_text('\n'.join(denials) + '\n')
files = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(root.iterdir()) if p.is_file()}
(root / 'evidence-manifest.json').write_text(json.dumps({'captured_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'read_only': True, 'route_or_far_end_validation': False, 'sha256': files}, indent=2) + '\n')
print('Evidence captured: ' + str(root))
print('Inspect capabilities.json, audio-policy.txt, audio-flinger.txt, policy-and-mixer-config.txt and selinux-denials.txt.')
PY
