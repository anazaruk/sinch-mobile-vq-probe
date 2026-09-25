#!/system/bin/sh
# Sourced by the existing Magisk installer; never invoke exit here.
[ "$BOOTMODE" = true ] || abort "Install from running Android using Magisk; recovery installation is unsupported."
[ "${MAGISK_VER_CODE:-0}" -ge 24000 ] || abort "Magisk 24 or newer must already be installed."
[ "${API:-0}" -ge 31 ] || abort "Phase B privileged engineering package requires Android 12 / API 31 or newer."
[ "${API:-0}" -le 34 ] || ui_print "Android newer than 14: platform compatibility is unverified; run all acceptance checks."
[ -s "$MODPATH/system/priv-app/SinchVQ/SinchVQ.apk" ] || abort "APK missing: build the module with tools/build_privileged_module.py."
[ -s "$MODPATH/system/etc/permissions/privapp-permissions-com.sinch.vqprobe.xml" ] || abort "Privilege allowlist missing."
(cd "$MODPATH" && sha256sum -c apk.sha256) || abort "APK checksum failed."
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
ui_print "Sinch APK and same-partition privileged allowlist staged."
ui_print "No vendor, audio-policy, IMS, boot image, runtime permission or AppOps changes were made."
ui_print "Reboot manually, unlock, then run tools/verify_privileged_install.sh."
