# CaptureRouter

CaptureRouter is an Android utility for keeping screenshots organized at the moment they are created. It watches a configurable screenshot folder, renames new screenshots with the foreground app name, and optionally routes screenshots from selected apps into dedicated folders.

It is designed for sideloaded Android devices where the user wants local, transparent screenshot management without root prompts or cloud services.

## Features

- Renames every new screenshot with a configurable filename template.
- Detects the foreground app through an Accessibility service, not root.
- Routes screenshots by filename, so rules continue to work after renaming.
- Supports single-app rules and app bundles.
- Offers two routing modes:
  - **Auto move immediately** moves matching screenshots as soon as they are detected.
  - **Queue for review** keeps screenshots visible until they are moved from CaptureRouter.
- Creates `.nomedia` in destination folders by default so moved screenshots stay out of media galleries.
- Provides a searchable app picker with icons and cached app loading.
- Lets the source screenshot folder be changed from the default Android screenshots path.
- Keeps a local activity log for rename, queue, move, and error events.
- Restores monitoring after reboot, app update, app resume, process death, and task dismissal where Android allows it.

## How It Works

CaptureRouter monitors the configured source folder. The default is:

```text
/sdcard/Pictures/Screenshots
```

When a new screenshot appears, the app waits until the file is stable, renames it using the current foreground app, and then checks routing rules against the filename.

Default filename template:

```text
Screenshot_{date}_{time}_{app}
```

Example output:

```text
Screenshot_20260411_194318_ChatGPT.png
```

Default destination for app rules:

```text
/sdcard/Pictures/.CaptureRouter/<App Name>
```

## Permissions

CaptureRouter does **not** use root.

Required Android permissions and settings:

- **Accessibility service**: identifies the foreground app around screenshot creation.
- **All files access**: watches and moves screenshots in shared storage.
- **Notification permission**: lets Android show the foreground-service notification on Android 13+.
- **Battery unrestricted mode**: recommended for reliable always-on monitoring.

Android requires a foreground-service notification for long-running monitoring. If notification permission or the notification channel is disabled, Android may hide the notification from the drawer, but the service can still appear in Android's active-service controls.

## Reliability Notes

CaptureRouter uses a foreground service, a boot receiver, package-update restoration, task-dismiss handling, and a periodic watchdog alarm. This covers normal reboot, update, task swipe-away, and process-kill cases.

Android can still stop any regular app after a manual **Force stop** from system settings. After a force stop, CaptureRouter must be opened once before monitoring can resume.

Screenshots can appear slightly delayed because the app waits for Android to finish writing the image before renaming or moving it. This avoids corrupting partially written files.

## Build

The project uses a lightweight Android SDK build script instead of Gradle.

Requirements:

- Android SDK with platform `android-36`
- Android build tools `36.0.0`
- JDK 21 or compatible Java toolchain

Build the debug APK:

```bash
./build.sh
```

Install and open on a connected device:

```bash
./install.sh
```

Manual install:

```bash
adb install -r build/capture-router-debug.apk
adb shell am start -n dev.dect.capturerouter/.MainActivity
```

## First Run

1. Open CaptureRouter.
2. Enable the CaptureRouter Accessibility service.
3. Grant All files access if prompted.
4. Allow notifications or accept that Android may only show active-service status.
5. Set battery usage to unrestricted for best reliability.
6. Add routing rules and start monitoring.

## Continuous Integration

GitHub Actions builds the debug APK on every push and pull request. The workflow uploads `capture-router-debug-apk` as a build artifact.

Workflow:

```text
.github/workflows/build.yml
```

## Limitations

- Hidden folders plus `.nomedia` prevent normal gallery indexing, but they do not encrypt image bytes.
- App attribution depends on Accessibility events near the screenshot timestamp. If Android withholds foreground state, screenshots may be named as `Unknown_App`.
- Existing screenshots are only routed if their filenames already contain a matching app slug.
