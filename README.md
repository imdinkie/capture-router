# CaptureRouter

Private Android app for rooted devices that moves new screenshots from selected apps into hidden per-app folders.

## Current Behavior

- Watches `/sdcard/Pictures/Screenshots` from a foreground service.
- Uses Magisk `su` and `dumpsys activity activities` to infer the foreground app around screenshot creation.
- Lets you add app-to-folder rules from a searchable icon picker.
- Supports single-app rules and app bundles.
- Supports two routing modes:
  - **Auto move immediately** moves matching screenshots as soon as they are detected.
  - **Queue for review** keeps matching screenshots in the normal screenshot folder until you press **Move now** or **Move all**.
- Creates `.nomedia` in destination folders by default when screenshots are moved.
- Moves screenshots with root `mv`.
- Keeps a capped in-app log of moves, ignored screenshots, and errors.
- Restarts monitoring after boot, app update, app resume, and via a periodic watchdog when monitoring is enabled.

## Build And Install

```bash
./build.sh
adb install -r build/capture-router-debug.apk
adb shell am start -n dev.dect.capturerouter/.MainActivity
```

Or:

```bash
./install.sh
```

## First Run

1. Grant Magisk root when prompted.
2. Grant All files access if Android asks.
3. Add a rule with **Add routing rule**.
4. Start monitoring.

The default rule folder is:

```text
/sdcard/Pictures/.CaptureRouter/<App Name>
```

## Notes

- Android screenshots are created by SystemUI, so the app attributes screenshots to the foreground app at capture time.
- The v1 sensitive-storage mode is hidden folders plus `.nomedia`; it does not encrypt image bytes.
- The app is intended for sideloaded/rooted personal use, not Play Store distribution.
- Android can still suppress apps that are force-stopped by the user. CaptureRouter handles normal reboot, update, task removal, and process death paths, but no regular app can restart itself after a manual force-stop until it is opened again.
