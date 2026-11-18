# eSIM Tile

A Quick Settings tile that turns a downloaded eSIM on and off in one tap.

Android has this switch in Settings, but no tile for it, and ordinary apps aren't allowed to switch eSIMs.
eSIM Tile uses [Shizuku](https://github.com/RikkaApps/Shizuku) to perform the switch with adb shell privileges. It does not need root.

## Features

- Choose which downloaded eSIM the tile controls
- Finds the eSIM card and a free port automatically
- Optionally keeps data roaming off for that eSIM, including after activation
- Optionally shows the eSIM's phone number on the tile

## Requirements

- Android 13 or newer on a phone with eSIM support
- Shizuku installed and running

## Setup

1. Install and start Shizuku.
2. Open eSIM Tile and allow Shizuku access.
3. Choose an eSIM and any options you want.
4. Tap **Add tile to Quick Settings**.

Long-press the tile to return to its settings. Shizuku usually has to be started again after a reboot; until then the tile shows "Start Shizuku".

## Compatibility

Tested on a Pixel 10a running Android 17. Other phones are untested. The app relies on non-public Android telephony interfaces, which can differ between devices and may change between Android versions.

Switching eSIMs can interrupt service on that line. If every eSIM port is already in use, Android may replace the active eSIM.

## Privacy

The app has no internet permission. Settings and the optional cached phone number stay on the device and are excluded from Android backups. If you enable the phone number option, the number may be visible wherever Quick Settings is visible, including the lock screen.

## Build

Requires JDK 17 or newer and the Android SDK.

```bash
./gradlew assembleDebug
```

## License

MIT
