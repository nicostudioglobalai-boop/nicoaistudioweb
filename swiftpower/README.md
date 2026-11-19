# SwiftPower — powers ordinary phones don't have

`com.swiftpower.app` v1.0, Android 7.0+ (target API 34). Pure Java, no dependencies.

Built with budget phones in mind (tested targets: Android 12/14 Go Edition devices like itel A50/A60). Everything works **out of the box — no ADB, no wizard, no root**.

## Features
- **⚡ Swift Boost tile** — a Quick Settings tile: pull down the notification shade anywhere, tap once, background apps close and RAM frees instantly.
- **🔋 Charging Doctor** — optional guard service that runs only while charging: detects slow cables/adapters (measures %/min), overheat alerts (≥40 °C), 80%/full unplug reminders, and stops itself when unplugged.
- **🗄 Storage Radar** — lists your biggest photos/videos (MediaStore), tap any to open & delete through the gallery. Reclaims real space on 32/64 GB phones.
- **📶 Network Doctor** — ping (ms), DNS resolution check, and a true ~2 MB download speed measurement + Wi-Fi link speed/signal quality — to tell "network slow" from "phone slow".

## Build
`./build.sh` (JDK 17 + build-tools 34 + platform android-34; adjust paths at top). Output: `SwiftPower-v1.0.apk`.

## Permissions it asks for (all runtime, all optional)
- Usage access → to find background apps for Boost (Power tab explains)
- Notifications → for Charging Doctor alerts
- Photos/videos → for Storage Radar
- Internet → for the speed test
