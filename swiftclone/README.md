# SwiftClone — App Cloner + Fast Mode (Android, no root)

`com.swiftclone.app`, v1.0, Android 7.0+ (API 24–34). Pure Java, no dependencies, no background services.

## Download
Install `releases/SwiftClone-v1.0.apk` on your phone ("allow from this source").

## What it does

### Clone Space (duplicate any app)
Creates a **managed profile** (the same sandbox Android uses for work profiles — official API, no root) and installs your selected apps into it. Two instances of WhatsApp, Instagram, a game, etc., running side by side with separate data.

### Fast Mode (make every app respond faster)
- Raises the **standby priority of all installed apps to "Active"** so Android stops throttling background app starts and syncs
- **Closes background processes** of apps used in the last 24 h and reports freed RAM
- **System animation speed presets** (1x / 0.5x / 0.25x / Off) — the biggest perceived-speed lever, applies to every app

## One-time setup (no root)
1. Enable Developer Options (Build number × 7) + USB debugging
2. From a computer with [Google platform-tools](https://developer.android.com/tools/releases/platform-tools):
```
adb shell dpm set-device-owner com.swiftclone.app/.AdminReceiver
```
3. *(Optional, for animation presets)*
```
adb shell pm grant com.swiftclone.app android.permission.WRITE_SECURE_SETTINGS
```
Notes: `dpm set-device-owner` works best right after a fresh setup or with accounts temporarily removed (then re-added). Some heavily-skinned OEMs restrict profiles; on stock Android / Pixel it is reliable.

## Build from source
`./build.sh` (adjust SDK/JDK paths at top; JDK 17 + build-tools 34 + platform android-34). Output: `SwiftClone-v1.0.apk`.

## limitations
- Cloned apps run inside a separate profile, not literally the same user id — this is how all no-root cloners work
- Banking apps may refuse to run in profiles (their choice, for security)
- Fast Mode's "Active priority" boost uses a privileged API; if your ROM denies it, the app degrades gracefully and still kills background processes
