#!/usr/bin/env bash
set -euo pipefail
VQ_TOOL_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$VQ_TOOL_DIR/../privileged-module/host-common.sh"
VQ_SERIAL= VQ_ZIP= VQ_APKSIGNER= VQ_AAPT2=
usage() { printf '%s\n' 'Usage: tools/install_privileged_module.sh --module ZIP [--serial SERIAL] [--apksigner PATH] [--aapt2 PATH]' 'Stages the validated overlay using already-installed Magisk. Checks APK identity, permissions, version and signer. No rooting, unlock, uninstall, data clearing or automatic reboot.'; }
while (($#)); do
  case "$1" in
    --serial) [[ $# -ge 2 ]] || vq_fail '--serial requires a value'; VQ_SERIAL=$2; shift 2 ;;
    --module) [[ $# -ge 2 ]] || vq_fail '--module requires a value'; VQ_ZIP=$2; shift 2 ;;
    --apksigner) [[ $# -ge 2 ]] || vq_fail '--apksigner requires a value'; VQ_APKSIGNER=$2; shift 2 ;;
    --aapt2) [[ $# -ge 2 ]] || vq_fail '--aapt2 requires a value'; VQ_AAPT2=$2; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; vq_fail "Unknown argument: $1" ;;
  esac
done
[[ -n "$VQ_ZIP" && -f "$VQ_ZIP" ]] || vq_fail '--module must name the generated ZIP.'
vq_setup
vq_require_root
VQ_MAGISK=$(vq_shell su -c 'magisk -V' | tr -d '\r')
[[ "$VQ_MAGISK" =~ ^[0-9]+$ && "$VQ_MAGISK" -ge 24000 ]] || vq_fail 'Magisk 24+ must already be installed.'
VQ_API=$(vq_shell getprop ro.build.version.sdk | tr -d '\r')
[[ "$VQ_API" =~ ^[0-9]+$ && "$VQ_API" -ge 31 ]] || vq_fail 'Android 12 / API 31 or newer is required for this engineering module.'
VQ_TMP=$(mktemp -d)
trap 'rm -rf -- "$VQ_TMP"' EXIT
# Verify archive identity and ensure it contains no boot scripts, policies, traversal or symlinks.
python3 - "$VQ_ZIP" "$VQ_TMP" "$VQ_TOOL_DIR" "$VQ_APKSIGNER" "$VQ_AAPT2" <<'PY'
import hashlib, importlib.util, json, pathlib, stat, sys, zipfile
zip_path, temp, tools, signer, aapt = sys.argv[1:]
spec = importlib.util.spec_from_file_location('vq_build', pathlib.Path(tools) / 'build_privileged_module.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
allowed = {'module.prop', 'customize.sh', 'apk.sha256', 'build-info.json', module.APK_PATH,
           'system/etc/permissions/privapp-permissions-com.sinch.vqprobe.xml'}
try:
    with zipfile.ZipFile(zip_path) as archive:
        if set(archive.namelist()) != allowed or len(archive.infolist()) != len(allowed):
            raise ValueError('Unexpected archive contents; rebuild using the supplied packaging tool')
        for entry in archive.infolist():
            if stat.S_ISLNK(entry.external_attr >> 16) or entry.file_size > 100 * 1024 * 1024:
                raise ValueError('Unsafe archive entry')
        info = json.loads(archive.read('build-info.json'))
        data = archive.read(module.APK_PATH)
        if info['package'] != module.PACKAGE or hashlib.sha256(data).hexdigest() != info['apk_sha256']:
            raise ValueError('APK identity/checksum mismatch')
        # Only install the reviewed installer and allowlist from this source tree.
        template = pathlib.Path(tools).parent / 'privileged-module'
        for name in ('customize.sh', 'system/etc/permissions/privapp-permissions-com.sinch.vqprobe.xml'):
            if archive.read(name) != (template / name).read_bytes():
                raise ValueError(f'{name} differs from this source release; use the matching source and module')
        prop = archive.read('module.prop').decode()
        lines = [line for line in prop.splitlines() if line and not line.startswith('#')]
        pairs = [line.split('=', 1) for line in lines]
        if any(len(pair) != 2 for pair in pairs) or len({pair[0] for pair in pairs}) != len(pairs):
            raise ValueError('Malformed/duplicate module properties')
        properties = dict(pairs)
        if (set(properties) != {'id', 'name', 'version', 'versionCode', 'author', 'description'} or
                properties['id'] != 'sinch_vq_privileged' or
                properties['version'] != 'v' + info['version_name'] or
                properties['versionCode'] != str(info['version_code'])):
            raise ValueError('Wrong module identity or version')
        if archive.read('apk.sha256').decode() != info['apk_sha256'] + '  ' + module.APK_PATH + '\n':
            raise ValueError('Module checksum manifest mismatch')
        apk = pathlib.Path(temp) / 'module.apk'; apk.write_bytes(data)
        signer_tool = module.sdk_tool('apksigner', signer or None)
        aapt_tool = module.sdk_tool('aapt2', aapt or None)
        actual = module.inspect_apk(apk, aapt_tool, signer_tool)
        if any(actual[key] != info[key] for key in ('package', 'version_code', 'version_name', 'signer_sha256', 'apk_sha256')):
            raise ValueError('Module APK metadata/signature mismatch')
        (pathlib.Path(temp) / 'metadata.json').write_text(json.dumps(info))
        (pathlib.Path(temp) / 'apksigner-path').write_text(signer_tool)
        (pathlib.Path(temp) / 'aapt2-path').write_text(aapt_tool)
except Exception as error:
    raise SystemExit('ERROR: ' + str(error))
PY
VQ_INSTALLED=$(vq_shell pm path --user 0 "$VQ_PACKAGE" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -n 1 || true)
if [[ -n "$VQ_INSTALLED" ]]; then
  [[ "$VQ_INSTALLED" =~ ^/[A-Za-z0-9_./=+~:-]+$ ]] || vq_fail 'Unexpected installed APK path; inspect manually.'
  vq_adb "${VQ_ADB[@]}" pull "$VQ_INSTALLED" "$VQ_TMP/installed.apk" >/dev/null || vq_fail 'Unable to read installed APK for signer comparison.'
  python3 - "$VQ_TMP" <<'PY'
import json, pathlib, re, subprocess, sys
temp = pathlib.Path(sys.argv[1]); expected = json.loads((temp / 'metadata.json').read_text())
output = subprocess.run([(temp / 'apksigner-path').read_text(), 'verify', '--print-certs', str(temp / 'installed.apk')], text=True, capture_output=True, timeout=90)
signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', output.stdout, re.M)
if output.returncode or len(signers) != 1 or signers[0].lower() != expected['signer_sha256']:
    raise SystemExit('ERROR: Installed app signer differs. Rebuild with the original signing key. No automatic uninstall/data loss is performed.')
badge = subprocess.run([(temp / 'aapt2-path').read_text(), 'dump', 'badging', str(temp / 'installed.apk')], text=True, capture_output=True, timeout=90)
version = re.search(r"^package: name='com\.sinch\.vqprobe' versionCode='(\d+)'", badge.stdout, re.M)
if badge.returncode or not version or int(version[1]) > expected['version_code']:
    raise SystemExit('ERROR: Installed package version cannot be verified or exceeds module version; no downgrade is performed.')
print('PASS installed APK signature matches module APK; enrollment data can be retained.')
PY
fi
VQ_SHA=$(python3 - "$VQ_ZIP" <<'PY'
import hashlib, sys
print(hashlib.sha256(open(sys.argv[1], 'rb').read()).hexdigest())
PY
)
VQ_REMOTE="/data/local/tmp/sinch-vq-module-${VQ_SHA:0:16}.zip"
vq_adb "${VQ_ADB[@]}" push "$VQ_ZIP" "$VQ_REMOTE" >/dev/null
VQ_DEVICE_SHA=$(vq_shell sha256sum "$VQ_REMOTE" | tr -d '\r' | awk '{print $1}')
[[ "$VQ_DEVICE_SHA" == "$VQ_SHA" ]] || vq_fail 'Transferred module checksum differs; installation was not attempted.'
printf '%s\n' 'Installing the Sinch APK/permission overlay with existing Magisk. No vendor or firmware files are included.'
vq_shell su -c "magisk --install-module $VQ_REMOTE" || vq_fail 'Magisk module staging failed; inspect its output before rebooting.'
vq_shell rm -f "$VQ_REMOTE"
printf '%s\n' 'Module staged. Reboot manually when ready: adb reboot' 'After unlock: tools/verify_privileged_install.sh --grant-runtime (add --serial if needed).' 'Choose the default dialer role in the app. Runtime grants are optional here and are applied only when explicitly requested.' 'Rollback only this module: adb shell su -c "touch /data/adb/modules/sinch_vq_privileged/disable"; then reboot manually.'
