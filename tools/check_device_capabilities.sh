#!/usr/bin/env bash
set -euo pipefail
VQ_TOOL_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$VQ_TOOL_DIR/../privileged-module/host-common.sh"
VQ_SERIAL= VQ_ROOT=0 VQ_OUTPUT="device-capabilities-$(date -u +%Y%m%dT%H%M%SZ)"
usage() { printf '%s\n' 'Usage: tools/check_device_capabilities.sh [--serial SERIAL] [--output NEW_DIR] [--root]' 'Read-only evidence snapshot. --root permits Magisk read access to vendor audio policy; it never changes routing, mixer, firmware or permissions.'; }
while (($#)); do
  case "$1" in
    --serial) [[ $# -ge 2 ]] || vq_fail '--serial requires a value'; VQ_SERIAL=$2; shift 2 ;;
    --output) [[ $# -ge 2 ]] || vq_fail '--output requires a value'; VQ_OUTPUT=$2; shift 2 ;;
    --root) VQ_ROOT=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; vq_fail "Unknown argument: $1" ;;
  esac
done
vq_setup
[[ "$VQ_ROOT" == 0 ]] || vq_require_root
vq_output_dir
vq_capture properties.txt getprop
vq_capture package.txt dumpsys package "$VQ_PACKAGE"
vq_capture audio.txt dumpsys audio
vq_capture audio-policy.txt dumpsys media.audio_policy
vq_capture record-appop.txt cmd appops get --user 0 "$VQ_PACKAGE" RECORD_AUDIO
vq_capture selinux.txt getenforce
# Enumerate and read fixed device configuration paths. Never evaluate XML contents.
VQ_POLICY_SCRIPT='for root in /system/etc /system_ext/etc /vendor/etc /odm/etc /product/etc; do
  [ -d "$root" ] || continue
  find "$root" -maxdepth 3 -type f \( -iname "*audio*policy*.xml" -o -iname "*audio*policy*.conf" -o -iname "mixer_paths*.xml" \) 2>/dev/null
done | sort -u | head -n 80 | while IFS= read -r path; do
  printf "\nFILE: %s\n" "$path"
  head -c 262144 "$path" 2>/dev/null || printf "UNREADABLE\n"
  printf "\n"
done'
if [[ "$VQ_ROOT" == 1 ]]; then
  vq_capture policy-and-mixer-config.txt su -c "$VQ_POLICY_SCRIPT"
else
  vq_capture policy-and-mixer-config.txt sh -c "$VQ_POLICY_SCRIPT"
fi
python3 - "$VQ_OUTPUT" <<'PY'
from pathlib import Path
import datetime, json, re, sys
root = Path(sys.argv[1])
def read(name): return (root / name).read_text(errors='replace')
props = dict(re.findall(r'^\[([^]]+)\]: \[([^]]*)\]$', read('properties.txt'), re.M))
package = read('package.txt').split('Hidden system packages:')[0]
permissions = {name: bool(re.search(r'^\s*android\.permission\.' + name + r':\s*granted=true\b', package, re.M))
               for name in ('MODIFY_PHONE_STATE', 'CAPTURE_AUDIO_OUTPUT', 'READ_PRECISE_PHONE_STATE', 'READ_PRIVILEGED_PHONE_STATE')}
policy = read('policy-and-mixer-config.txt')
dump = read('audio-policy.txt')
observed = lambda pattern, text: True if re.search(pattern, text, re.I) else None
report = {
 'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
 'model': props.get('ro.product.model'), 'device': props.get('ro.product.device'),
 'manufacturer': props.get('ro.product.manufacturer'), 'android_release': props.get('ro.build.version.release'),
 'android_api': props.get('ro.build.version.sdk'), 'fingerprint': props.get('ro.build.fingerprint'),
 'soc_manufacturer': props.get('ro.soc.manufacturer'), 'soc_model': props.get('ro.soc.model'),
 'selinux': read('selinux.txt').strip(),
 'privileged_app_flag': bool(re.search(r'privateFlags=\[[^\]]*\b(?:PRIVATE_FLAG_)?PRIVILEGED\b', package)),
 'privileged_permissions': permissions, 'privileged_permissions_ok': all(permissions.values()),
 'policy_telephony_tx_declared': observed(r'Telephony[ _]Tx|AUDIO_DEVICE_OUT_TELEPHONY_TX', policy),
 'policy_incall_music_declared': observed(r'incall_music(?:_uplink)?|AUDIO_OUTPUT_FLAG_INCALL_MUSIC', policy),
 'audio_policy_dump_telephony_tx_seen': observed(r'AUDIO_DEVICE_OUT_TELEPHONY_TX|Telephony[ _]Tx', dump),
 'telephony_tx_available': None, 'voice_downlink_capture_available': None, 'incall_music_supported': None,
 'capability_reason': 'Policy/dumpsys evidence is not app runtime routing or far-end proof. Obtain GET_AUDIO_DIAGNOSTICS during an ACTIVE owned call and run TX/RX acceptance tests.',
 'null_policy_reason': 'Not observed in this bounded readable snapshot; absence is not proof of unsupported hardware.',
 'policy_snapshot_limits': 'At most 80 XML/conf files, first 256 KiB each; exact paths and contents in policy-and-mixer-config.txt',
}
(root / 'capabilities.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
PY
printf 'Evidence saved: %s\n' "$VQ_OUTPUT"
