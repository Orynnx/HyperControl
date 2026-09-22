package org.orynnx.hypercontrol;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** Bridges the settings Activity to API 102 remote preferences. */
final class RemoteSettings {
    private static final AtomicReference<XposedService> SERVICE = new AtomicReference<>();
    private static final AtomicReference<SharedPreferences> REMOTE = new AtomicReference<>();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final CopyOnWriteArrayList<Runnable> CALLBACKS = new CopyOnWriteArrayList<>();

    private RemoteSettings() {}

    static void connect(Runnable callback) {
        CALLBACKS.add(callback);
        if (!REGISTERED.compareAndSet(false, true)) {
            if (REMOTE.get() != null) callback.run();
            return;
        }
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService service) {
                SERVICE.set(service);
                try { REMOTE.set(service.getRemotePreferences(TargetRuntime.PREFERENCES)); }
                catch (Throwable ignored) { REMOTE.set(null); }
                for (Runnable callback : CALLBACKS) {
                    try { callback.run(); } catch (Throwable ignored) { }
                }
            }
            @Override public void onServiceDied(XposedService service) {
                SERVICE.compareAndSet(service, null);
                REMOTE.set(null);
                for (Runnable callback : CALLBACKS) {
                    try { callback.run(); } catch (Throwable ignored) { }
                }
            }
        });
    }

    static SharedPreferences preferences(SharedPreferences fallback) {
        SharedPreferences remote = REMOTE.get();
        return remote != null ? remote : fallback;
    }

    static void putBoolean(SharedPreferences fallback, String key, boolean value) {
        SharedPreferences remote = REMOTE.get();
        if (remote != null) remote.edit().putBoolean(key, value).apply();
        fallback.edit().putBoolean(key, value).apply();
    }

    static boolean isHooked() {
        XposedService service = SERVICE.get();
        if (service == null) { Log.d("HyperControl", "status: service=null"); return false; }
        try {
            List<io.github.libxposed.service.HookedTarget> targets = service.getRunningTargets();
            Log.d("HyperControl", "status: targets=" + targets.size());
            for (io.github.libxposed.service.HookedTarget target : targets) {
                String process = target.getProcessName();
                Log.d("HyperControl", "status: " + process + " state=" + target.getState());
                if (process != null && (process.equals("com.android.systemui")
                        || process.startsWith("com.android.systemui:"))) {
                    // STALE means the target still has the previous generation loaded;
                    // it is nevertheless an active injected process. The restart
                    // button (or a normal SystemUI restart) brings it up to date.
                    return target.getState() == io.github.libxposed.service.HookedTarget.State.UP_TO_DATE
                            || target.getState() == io.github.libxposed.service.HookedTarget.State.STALE;
                }
            }
        } catch (Throwable failure) { Log.w("HyperControl", "status query failed", failure); }
        return false;
    }
}
