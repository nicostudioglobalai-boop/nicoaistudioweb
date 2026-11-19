# SwiftBoost — Android Speed & Battery (no root)

A lightweight Android app (pure Java, no dependencies, **zero background services**) that makes your phone feel faster and saves battery. Built for Android 7.0+ (API 24–34).

## Download
Grab `releases/SwiftBoost-v1.0.apk`, copy it to your phone and install it ("allow from this source").

## Features
- **Dashboard** — battery %, temperature, voltage, health, charging state, RAM & storage usage (with low-storage warning), device info.
- **One-Tap RAM Boost** — finds apps used in the last 24 h via UsageStats and closes their background processes (`KILL_BACKGROUND_PROCESSES`, no root).
- **Animation Turbo** — presets 1x / 0.5x / 0.25x / Off for the three system animation scales (window / transition / animator). Requires the one-time ADB grant below.
- **Tips** — 10 research-backed optimizations (auto-sync, bloatware, storage, refresh rate, Battery Saver, dark theme...).

## Unlock Turbo Mode (one time, no root)
Android only lets apps change these global settings with `WRITE_SECURE_SETTINGS`, grantable once over ADB from a computer:
1. Enable Developer Options (tap Settings > About phone > Build number 7 times) and USB debugging.
2. Connect the phone and run:
```
adb shell pm grant com.swiftboost.app android.permission.WRITE_SECURE_SETTINGS
```
Then open SwiftBoost > Boost tab — the animation presets work.

## Build from source
Requires JDK 17 and Android build-tools 34 + platform android-34:
```
./build.sh
```
(adjust `ROOT` paths inside for your SDK layout). Output: `SwiftBoost-v1.0.apk`.

`swiftboost.keystore` is the signing keystore used by `build.sh` (test-purpose password documented in the script).
