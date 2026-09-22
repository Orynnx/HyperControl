# HyperControl

HyperControl is an open-source libxposed API 102 module for HyperOS Control Center.

It provides optional media/control swapping, independent brightness/volume order, horizontal brightness and media-volume bars, left-aligned slider icons, Material 3 edge-to-edge settings, and verified SystemUI restart handling for persistent HyperOS processes.

The tested target is a Nubia NX667J running HyperOS/Android 14. Runtime structural discovery avoids a hard-coded HyperOS build gate, but other versions require their own verification.

## Build

Use JDK 17 and Android SDK 35:

```powershell
./gradlew.bat :app:assembleRelease :app:lintRelease
```

The signed release APK is written to `app/build/outputs/apk/release/`.

## Installation

Install the APK, enable HyperControl in LSPosed API 102, keep `com.android.systemui` and `miui.systemui.plugin` in scope, then configure the switches in the Activity.

## License

MIT. HyperOS/SystemUI classes and resources are runtime targets and are not redistributed here.
