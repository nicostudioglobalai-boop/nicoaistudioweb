# SwiftVault — file offload system (phone + PC)

Keeps your itel A50 (A667L) storage from piling up when you're handling paid projects.

## Part 1 — Phone app (SwiftVault-v1.0.apk)

Moves your biggest photos and videos from phone memory to your SD card so internal storage never fills up.

Install: download the APK below (allow "install from this source"), open the app, then:

1. **CHOOSE SD CARD FOLDER** — pick a folder on your SD card (once; it's remembered)
2. **SCAN BIGGEST FILES** — lists your 20 biggest photos + 20 biggest videos
3. **MOVE TICKED FILES TO SD** — tick what you want, tap move, confirm the delete dialog. Files are copied to the SD card first, then deleted from phone memory.

Requires an SD card to be inserted (32GB+ recommended).

## Part 2 — PC companion (SwiftVault-PC.jar)

For your PC: organises a projects folder, compresses files older than 90 days into ZIPs, and finds exact duplicates.

1. Install Java (free): https://adoptium.net/temurin/releases
2. Double-click `SwiftVault-PC.jar` (or run `java -jar SwiftVault-PC.jar`)
3. Choose your projects folder, then use the 5 buttons:
   - **Organise by type** — files go into `Images/`, `Videos/`, `Audio/`, `Documents/`, `Archives/`, `Other/` with `year-month` subfolders
   - **Compress old files** — files untouched for 90+ days are zipped into `Archive/` and the originals deleted
   - **Find duplicates** — writes `duplicates-report.txt` (size + MD5, safe from false matches)
   - **Free-space report** — drive usage

Command line also works: `java -jar SwiftVault-PC.jar "C:\Users\you\Projects" 90`

## Downloads

- Phone APK: `releases/SwiftVault-v1.0.apk`
- PC jar: `pc/SwiftVault-PC.jar`

## Building

- Phone: `./build.sh` (needs the same toolchain as the other Swift apps)
- PC: `javac --release 8 -d out DesktopVault.java && jar cfe SwiftVault-PC.jar DesktopVault -C out .`
- PC self-test: `java -jar SwiftVault-PC.jar --selftest`

Note: documents (PDFs, DOCX) don't show in the app's media scan — move those with the Files app or send them to your PC and use the jar.
