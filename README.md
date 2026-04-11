# CaptureRouter

Private Android app that renames screenshots with the foreground app name and optionally routes selected apps into hidden folders.

## Current Behavior

- Watches `/sdcard/Pictures/Screenshots` from a foreground service.
- Uses an Accessibility service to infer the foreground app around screenshot creation without repeated root prompts.
- Lets you add app-to-folder rules from a searchable icon picker.
- Supports single-app rules and app bundles.
- Renames every new screenshot before routing.
- Lets you customize the filename template in the app.
- Supports two routing modes:
  - **Auto move immediately** moves matching screenshots as soon as they are detected.
  - **Queue for review** keeps matching screenshots in the normal screenshot folder until you press **Move now** or **Move all**.
- Creates `.nomedia` in destination folders by default when screenshots are moved.
- Moves screenshots with normal shared-storage file operations.
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

1. Enable the CaptureRouter Accessibility service.
2. Grant All files access if Android asks.
3. Add a rule with **Add routing rule**.
4. Start monitoring.

The default rule folder is:

```text
/sdcard/Pictures/.CaptureRouter/<App Name>
```

The default filename format is:

```text
Screenshot_{date}_{time}_{app}
```

For example:

```text
Screenshot_20260411_194318_ChatGPT.png
```

## Notes

- Android screenshots are created by SystemUI, so the app attributes screenshots to the foreground app at capture time through Accessibility events.
- CaptureRouter renames newly detected screenshots. Old screenshots can only be routed by filename if they already contain an app slug that matches a rule.
- The v1 sensitive-storage mode is hidden folders plus `.nomedia`; it does not encrypt image bytes.
- The app is intended for sideloaded personal use, not Play Store distribution.
- Android can still suppress apps that are force-stopped by the user. CaptureRouter handles normal reboot, update, task removal, and process death paths, but no regular app can restart itself after a manual force-stop until it is opened again.
