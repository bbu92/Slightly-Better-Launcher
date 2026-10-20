# SBL (Slightly Better Launcher)

A home screen for Android TV. The apps you use most sit in one row. Focus one and the home screen shows what you
were watching in it. Inputs, weather and settings are one press away. The APK is about 480 KB, there are no ads, no
account and no analytics.

![SBL home screen](docs/home.png)

Version 0.1. Android 8.0 or newer (API 26). Package name `com.bbu.sbl`.

## What it does

**The home screen**

The top bar has the time, the date and the weather for where the TV is. On the right there are four buttons: the
TV's AirPlay name (when an AirPlay receiver is running), the inputs menu, the network (Wi-Fi name or Ethernet, with
a VPN badge when a VPN is up) and settings.

Under it is the favourites row. It holds 4 to 8 apps (6 by default) in a rounded band. Under the row, connected HDMI
and AV inputs are listed by the names the TV gives them. All your other apps stay hidden until you press Down, so the
home screen stays quiet.

**Watch Next**

When you focus a favourite, SBL reads the Watch Next and featured items that app publishes and shows up to four of
them above the row: the app, what kind of item it is (continue watching, up next, new, watchlist, featured), the
title, season and episode, minutes left, a progress bar, a description and the poster. Items you are partway through
come first, then TV episodes, then the most recently watched, one episode per series. The button reads Continue,
Watch, Listen for music apps or Play for games.

The items turn by themselves every 6, 10 or 30 seconds, or never. Press Up from the row to take over: Left and Right
change the item, OK opens it in its app, Down goes back to the row. Apps that publish nothing just show their name.
Android asks once, on first start, whether SBL may read TV listings. Without that permission this part stays empty
and everything else works.

**Apps**

Sorting is alphabetical, recently used, most used or manual. Hold OK on a tile to move it, open its App info, hide it
or add it to (or remove it from) the favourites. While moving, the arrow keys swap the tile with its neighbour, also
across the edge of the favourites row. Choosing a manual order or moving a tile switches sorting to manual.

**The gear button**

It opens a small menu: SBL settings and the TV's own system settings.

SBL settings has four pages:
- Home screen: language, appearance, number of columns, the inputs row, Watch Next (on or off, turning time),
  weather (on or off, Celsius or Fahrenheit) and module cards.
- Apps: sort order, hidden apps, clearing the usage counts that "recently used" and "most used" rely on.
- Permissions: what SBL can use, and a shortcut to Android's page for it.
- Backup, reset and about: back up, restore, reset the menu choices, version.

**Appearance and language**

Appearance is dark, light, or automatic. Automatic is light by day and dark from sunset to sunrise at the TV's
position. The sun's position is calculated on the TV, so it needs no network once the position is known.

SBL speaks English, Turkish, Spanish, French, German, Russian, Ukrainian, Portuguese, Czech and Polish. By default it
follows the TV's language and uses English when it has no translation for it. You can also pick a language in the
settings, whatever the TV itself is set to.

**Backup and restore**

A backup is one small JSON file with every menu setting, the favourites, the manual order, the hidden apps and the
usage counts. It is written to a `SlightlyBetterLauncher` folder on the TV's storage and on any USB drive that
accepts it, and the five newest are kept. It survives uninstalling the app, which makes it the way to move to a new
version or a new TV. Writing and reading there needs Android's "All files access", which the Permissions page opens.

**Smaller things**

- A splash screen that looks like the home screen instead of a white flash.
- Banners and icons are loaded at xxhdpi or higher and only scaled down, and decoded only for the tiles on screen.
- A clock screensaver you can pick in Android's screensaver settings.
- Module cards: an app can offer its own labelled tile, after you agree to it. See [`launcher/CARDS.md`](launcher/CARDS.md).

## Install

### Without ADB

1. Copy `SlightlyBetterLauncher.apk` from [`dist/`](dist/) to a USB drive, or download it on the TV.
2. Open it from a file manager on the TV and allow installs from that app when Android asks.
3. Start SBL. Allow the TV listings permission if you want Watch Next.

SBL is an ordinary app until you make it the Home screen, so you can try it and press Home to leave. If your TV
asks which launcher to use when you press Home, pick SBL and choose Always.

### With ADB

```bash
adb connect <tv-ip>:5555
adb install -r SlightlyBetterLauncher.apk
adb shell am start -n com.bbu.sbl/.MainActivity
```

The two permissions can also be granted from the computer instead of on the TV:

```bash
adb shell pm grant com.bbu.sbl android.permission.READ_TV_LISTINGS
adb shell appops set com.bbu.sbl MANAGE_EXTERNAL_STORAGE allow
```

## Making SBL the Home screen with ADB

Only needed if pressing Home does not offer SBL. Disabling the TV maker's launcher can remove things you may want, so
read the last step first.

1. See which app owns Home now:

   ```bash
   adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
   ```

   Typical answers are `com.google.android.tvlauncher` (Android TV), `com.google.android.apps.tv.launcherx`
   (Google TV) or the maker's own launcher. If the answer is `android`, Android is showing a chooser: pick SBL there
   and you are done.

2. Disable that launcher for your user. Nothing is deleted:

   ```bash
   adb shell pm disable-user --user 0 <package-from-step-1>
   ```

3. Make SBL the Home app and check it:

   ```bash
   adb shell cmd package set-home-activity --user 0 com.bbu.sbl/.MainActivity
   adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
   ```

   The second command should now name `com.bbu.sbl`. On Google TV the setup wizard
   (`com.google.android.tungsten.setupwraith`) can also claim Home and keep winning; if the answer is not SBL, disable
   it the same way as in step 2.

4. To go back to the original launcher at any time:

   ```bash
   adb shell pm enable --user 0 <package-from-step-1>
   ```

   If a TV ever ends up without a Home screen, that one command is enough.

## Build from source

You need JDK 17 or newer and the Android SDK (`ANDROID_HOME`).

```bash
cd launcher
./gradlew assembleRelease            # app/build/outputs/apk/release/SlightlyBetterLauncher.apk
TV_HOST=192.168.1.50 ./install.sh    # build, install and start it on a TV (install.ps1 -TvHost ... on Windows)
```

### Signing

The release build is signed with the key named in `launcher/keystore.properties`, which is not committed:

```properties
storeFile=/path/to/sbl-release.jks
storePassword=...
keyAlias=sbl
keyPassword=...
```

Without that file it falls back to the Android debug key, so anyone can still build and sideload it. A key is made
with:

```bash
keytool -genkeypair -keystore sbl-release.jks -alias sbl -keyalg RSA -keysize 4096 -validity 36500
```

A TV only accepts an update signed with the same key as the installed app. With a different key you have to
uninstall first, which deletes the app's settings, so make a backup before.

The APK in `dist/` is signed with the certificate whose SHA-256 fingerprint is
`5A:7B:1F:73:8F:3C:C0:99:5C:E6:6B:91:9D:64:20:E3:E1:76:E5:0C:8B:3F:60:08:BB:53:D4:ED:7D:B2:6E:CC`.

## Network use

SBL uses the network for the weather (Open-Meteo, with the position taken from the TV's last known location or, when
there is none, from your IP address through ipwho.is or geojs) and for posters that apps publish as web addresses.
Nothing else leaves the TV. Watch Next data is read on the TV from the system's TV provider and stays there.

## Limits

- Developed and tried on one TV, a TCL running Android TV 11. Other TVs should work, but Watch Next, input names and
  switching the Home screen differ between makers.
- Android composes the interface of many TVs at 1080p even on a 4K panel (`adb shell wm size` shows it). SBL cannot
  draw sharper than the system allows.
- Themes (a folder with a `config.json` and a wallpaper, see [`themes/`](themes/README.md)) exist in the code but are
  switched off in the settings for now.

## Licence

Copyright (c) 2026 BBU. Released under the MIT licence, see [`LICENSE`](LICENSE). The file also carries the copyright
notice of the code SBL is built on, as the licence requires. The Outfit typeface is under the SIL Open Font
License 1.1 ([`launcher/FONT-LICENSE-OFL.txt`](launcher/FONT-LICENSE-OFL.txt)).
