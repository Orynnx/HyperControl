package org.orynnx.hypercontrol;

import io.github.libxposed.api.XposedModule;

/** API 102 entry point. The plugin has no bootstrap Activity requirement. */
public final class XposedInit extends XposedModule {
    private boolean systemUiProcess;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        String process = param.getProcessName();
        systemUiProcess = !param.isSystemServer()
                && ("com.android.systemui".equals(process)
                || process.startsWith("com.android.systemui:"));
        log(4, "HyperControl", "[MODULE_LOADED] api=" + getApiVersion()
                + " process=" + process + " target=" + systemUiProcess);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        if (!"com.android.systemui".equals(packageName)
                && !"miui.systemui.plugin".equals(packageName)) return;
        try {
            log(4, "HyperControl", "[PACKAGE_READY] package=" + packageName
                    + " processTarget=" + systemUiProcess);
            TargetRuntime.start(this, param.getClassLoader(), packageName);
        } catch (Throwable failure) {
            log(6, "HyperControl", "[BOOTSTRAP_FAILED] package=" + packageName, failure);
        }
    }
}
