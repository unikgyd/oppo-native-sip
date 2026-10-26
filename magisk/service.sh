#!/system/bin/sh
# Magisk late_start service; scoped to this package only.
PKG=dev.mysip
MODDIR=${0%/*}
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 3; done
sleep 8
[ -e "$MODDIR/disable" ] && exit 0
[ -e "$MODDIR/remove" ] && exit 0
pm path "$PKG" >/dev/null 2>&1 || exit 0
dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1
cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
cmd appops set "$PKG" RUN_IN_BACKGROUND allow >/dev/null 2>&1
# Optional one-way SIP inbox copy. The app separately checks its user-facing switch.
cmd appops set "$PKG" WRITE_SMS allow >/dev/null 2>&1
for permission in RECORD_AUDIO CALL_PHONE READ_PHONE_STATE POST_NOTIFICATIONS BLUETOOTH_CONNECT; do
    pm grant "$PKG" "android.permission.$permission" >/dev/null 2>&1
done
# persistent=true lets Android supervise the process. No root SIP engine or process-priority hacks.
# Start the normal app service; it checks the saved enabled flag before registering.
am start-foreground-service -n "$PKG/.SipRuntimeService" >/dev/null 2>&1
