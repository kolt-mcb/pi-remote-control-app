# Pi Remote Control — Android app

Android companion app for [`pi-remote-control`](https://github.com/kolt-mcb/pi-remote-control),
a Pi extension that exposes a Pi agent over a LAN WebSocket. Scan the QR code the
extension prints on `session_start`, and drive the agent from your phone.

This is the **app** half. The Pi extension (host side) lives in its own repo:
`pi install git:github.com/kolt-mcb/pi-remote-control`.

## Install

Side-load the APK from [Releases](../../releases). CI publishes a versioned debug
APK on every push to `master`; the rolling **`latest`** pre-release always points at
the most recent build. The in-app updater checks that `latest` release and offers to
download newer builds.

First launch requests:
- **Camera** — for the QR scanner on the connect screen.
- **Notifications** — for the foreground-service indicator while connected, and
  "pi is ready" alerts when a turn finishes in the background.

## Build from source

```bash
git clone https://github.com/kolt-mcb/pi-remote-control-app
cd pi-remote-control-app
./gradlew :app:assembleDebug   # or :app:assembleRelease
```

Output: `app/build/outputs/apk/{debug,release}/`.

`versionCode` = git commit count + 100 (the `+100` offset preserves monotonicity
across the split from the parent `pi-remote-control` repo, whose builds had reached
versionCode 89). `versionName` = short commit SHA. See `app/build.gradle.kts`.

## Versioning note

The `versionCode` offset lives in two places that must stay in sync:
`VERSION_CODE_OFFSET` in `app/build.gradle.kts` and the matching `+ 100` in
`.github/workflows/build-apk.yml`.
