#!/system/bin/sh
# SystemUI is persistent; force-stop returning zero does not prove it exited.
old_ui=$(pidof com.android.systemui)
[ -n "$old_ui" ] || { echo 'ERROR: SystemUI process not found'; exit 2; }
echo "BEFORE SystemUI=$old_ui"
# HyperOS protects the persistent SystemUI PID from kill(2). Android's supported
# crash/restart path asks ActivityManager to tear down the persistent service and
# start a fresh injected process. The plug-in is loaded in that host process.
am force-stop miui.systemui.plugin >/dev/null 2>&1 || true
am crash com.android.systemui >/dev/null 2>&1 || { echo 'ERROR: am crash rejected'; exit 4; }
# Bounded readiness wait: check actual process turnover, not a fixed success delay.
attempt=0
while [ "$attempt" -lt 75 ]; do
    new_ui=$(pidof com.android.systemui)
    if [ -n "$new_ui" ] && [ "$new_ui" != "$old_ui" ] && [ ! -e "/proc/$old_ui" ]; then
        echo "RESTART_OK SystemUI=$old_ui -> $new_ui; plugin reloads in its host"
        exit 0
    fi
    attempt=$((attempt + 1))
    sleep 0.2
done
echo "ERROR: restart verification timed out; old=$old_ui new=$new_ui"
exit 5
