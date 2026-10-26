#!/system/bin/sh
ui_print "MySIP system phone provider"
[ "$ARCH" = arm64 ] || abort "MySIP requires an ARM64 device."
ui_print "Installing into system/app; existing SIM/IMS services are retained."
ui_print "Reboot after installation. Enable the MySIP calling account in Settings."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

# Shamiko whitelist mode unmounts module APK paths in non-root app processes.
# Keep a package-manager update under /data/app so the persistent system app
# can load its own code without changing Shamiko or granting the app root.
if [ -f /data/adb/shamiko/whitelist ] && [ -d /data/adb/modules/zygisk_shamiko ] && [ ! -e /data/adb/modules/zygisk_shamiko/disable ]; then
    [ "$BOOTMODE" = true ] || abort "Shamiko whitelist: install this module from a running Android system."
    [ "$API" -ge 29 ] || abort "Shamiko whitelist compatibility requires Android 10 or newer."
    ui_print "Shamiko whitelist detected: staging companion APK update."
    # Keep Binder-forwarded descriptors on pipes: system_server cannot use
    # files under Magisk's protected directories as shell command stdin/stdout.
    APK_PATH="$MODPATH/system/app/MySIP/MySIP.apk"
    APK_BYTES=$(wc -c < "$APK_PATH")
    if ! STAGE_RESULT=$(cat "$APK_PATH" | pm install -r --staged --staged-ready-timeout 0 -S "$APK_BYTES" 2>&1); then
        ui_print "$STAGE_RESULT"
        abort "Could not stage companion APK; module installation stopped."
    fi
    echo "$STAGE_RESULT" | grep -q '^Success' || abort "Companion APK update was not accepted."
    ui_print "Companion APK staged. Reboot to apply both updates."
fi
