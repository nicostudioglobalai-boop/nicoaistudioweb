# SwiftCut — video editor for low-RAM phones (v1.1)

**v1.1 — new: visual timeline + preview.** CapCut-style filmstrip: thumbnails of your video, pink handles you drag to trim, playhead you tap to scrub, and an in-app video preview with play/pause.

An original, CapCut-style video editor built from scratch for the itel A50 / Android 14 Go (2GB RAM) class of phones. No root, no setup, no account.

## Tools inside

| Tool | What it does | Speed |
|------|-------------|-------|
| **Trim** | Cut any part of a video with two sliders — no re-encoding, zero quality loss | Near-instant |
| **Mute** | Remove the sound track, keep video | Near-instant |
| **Extract audio** | Save the sound as a file (Music/SwiftCut) | Near-instant |
| **Smart-Cut AI** | Scans loudness on-device and auto-keeps the most energetic ~20s as a highlight clip | A few seconds |
| **Compress** | Re-encodes to a much smaller file (same resolution, ~900kbps) for WhatsApp sharing | 1-3 min per video |

Everything you save lands in **Movies/SwiftCut** (audio in **Music/SwiftCut**) and appears in your Gallery.

## Install
1. Download SwiftCut-v1.0.apk (raw link in the repo)
2. Allow "install from this source" when asked
3. Open → SELECT VIDEO → use any tool

## Notes & honesty
- This app was built entirely from scratch (MediaExtractor / MediaCodec / MediaMuxer). It does not contain or copy any CapCut code, and no third-party app was reverse-engineered.
- Trim == copied track, so it cannot change speed or resolution.
- Compress re-encodes on a Unisoc T603, so expect roughly real-time speed (a 3-min video takes a few minutes).
- Smart-Cut analyses audio energy only — it is a helper, not generative AI.

## Rebuild
`./build.sh` (same toolchain as the other Swift apps; javac uses build-tools core-lambda-stubs.jar).
