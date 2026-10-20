# SBL (Slightly Better Launcher)

A home screen for Android TV. Your favourite apps sit in one row. Focus one and the home screen shows what you were
watching in it. The APK is under 500 KB. No ads, no account, no analytics.

![SBL home screen](docs/home.png)

Version 0.1. Android 8.0 or newer. Package name `com.bbu.sbl`.

## Features

- **Favourites row.** 4 to 8 apps, 6 by default. Connected HDMI and AV inputs are listed under it by name. Other apps
  stay hidden until you press Down.
- **Watch Next.** Focus a favourite and SBL shows what that app publishes: continue watching, up next, new and
  featured items, with title, episode, minutes left and a poster. Press Up to browse them, OK to open one. Android
  asks once whether SBL may read TV listings. Without it, this part stays empty and the rest works.
- **Top bar.** Time, date, optional weather, the AirPlay name, the inputs menu, the network and settings.
- **Sorting.** Alphabetical, recently used, most used or manual. Hold OK on a tile to move, hide or favourite it.
- **Appearance.** Dark, light or automatic (follows sunset at the TV's position).
- **Languages.** English, Turkish, Spanish, French, German, Russian, Ukrainian, Portuguese, Czech and Polish. SBL
  follows the TV's language and uses English if it has no translation. You can also pick one in the settings.
- **Backup.** One small JSON file with all settings, favourites and order. It is written to a `SlightlyBetterLauncher`
  folder on the TV and on USB drives, and survives uninstalling. It needs Android's "All files access".
- **Module cards.** An app can offer its own labelled tile after you agree to it. See
  [`launcher/CARDS.md`](launcher/CARDS.md).

## Install

Copy `SlightlyBetterLauncher.apk` from [`dist/`](dist/) to the TV, open it from a file manager and allow installs from
that app. If the TV asks which launcher to use when you press Home, pick SBL and choose Always.

With ADB:

```bash
adb connect <tv-ip>:5555
adb install -r SlightlyBetterLauncher.apk
adb shell am start -n com.bbu.sbl/.MainActivity
adb shell pm grant com.bbu.sbl android.permission.READ_TV_LISTINGS
adb shell appops set com.bbu.sbl MANAGE_EXTERNAL_STORAGE allow
```

The last two lines grant the permissions from the computer instead of on the TV.

### Making SBL the Home screen with ADB

Only needed if pressing Home does not offer SBL. Disabling the TV maker's launcher can remove things you may want.

```bash
# which app owns Home now (if it says "android", pick SBL in the chooser and stop here)
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
# disable it for your user (nothing is deleted) and set SBL as Home
adb shell pm disable-user --user 0 <that-package>
adb shell cmd package set-home-activity --user 0 com.bbu.sbl/.MainActivity
# to go back at any time
adb shell pm enable --user 0 <that-package>
```

## Build from source

You need JDK 17 or newer and the Android SDK (`ANDROID_HOME`).

```bash
cd launcher
./gradlew assembleRelease            # app/build/outputs/apk/release/SlightlyBetterLauncher.apk
TV_HOST=192.168.1.50 ./install.sh    # build, install and start it on a TV (install.ps1 -TvHost ... on Windows)
```

The release build is signed with the key named in `launcher/keystore.properties`, which is not committed:

```properties
storeFile=/path/to/sbl-release.jks
storePassword=...
keyAlias=sbl
keyPassword=...
```

Without that file it uses the Android debug key. A TV only accepts an update signed with the same key as the installed
app. With a different key you must uninstall first, which deletes the app's settings, so back up before.

The APK in `dist/` is signed with the certificate whose SHA-256 fingerprint is
`5A:7B:1F:73:8F:3C:C0:99:5C:E6:6B:91:9D:64:20:E3:E1:76:E5:0C:8B:3F:60:08:BB:53:D4:ED:7D:B2:6E:CC`.

## Limits

- Tried on one TV, a TCL running Android TV 11. Watch Next, input names and switching the Home screen differ between
  makers.
- Many TVs draw the interface at 1080p even on a 4K panel. SBL cannot be sharper than the system allows.

## Privacy

Any internet request shows the TV's IP address to the server that answers. SBL makes only these:

- **Weather** (off by default). SBL asks once on first start whether to turn it on, and you can change it in
  Settings, Home screen, Weather. When on, it gets the weather from Open-Meteo, which sees the TV's IP address and a
  rounded position.
- **Position.** SBL uses the TV's last known location. If there is none, it asks geojs.io for a rough position of the
  TV's IP address. This is used for the weather and for automatic appearance, so automatic appearance can make this
  request even with the weather off. Dark and Light never use the network.
- **Posters.** Some apps publish Watch Next posters as web addresses. SBL loads them from the address the app gave,
  usually the app's own server or CDN, which then sees the TV's IP address.

Nothing else leaves the TV.

## Licence

Copyright (c) 2026 BBU. MIT licence, see [`LICENSE`](LICENSE). The file also carries the copyright notice of the code
SBL is built on, as the licence requires. The Outfit typeface is under the SIL Open Font License 1.1
([`launcher/FONT-LICENSE-OFL.txt`](launcher/FONT-LICENSE-OFL.txt)).
