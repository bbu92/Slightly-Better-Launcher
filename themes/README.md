# Themes

A theme is a folder with a `config.json` and, optionally, a `wallpaper.jpg`. Installing one changes the
launcher's dock, layout and look on the TV without rebuilding the APK: the launcher reads
`config.json` as an overlay over its built-in `config.xml` the next time it starts.

Install a theme from SBL settings → Theme: from a folder on the TV or a USB drive (`TVThemes` or `Themes`),
or from a web address (a `.zip` or a `config.json`). You can also push one by hand:

```bash
adb push themes/aurora/config.json /sdcard/Android/data/com.bbu.sbl/files/config.json
adb push themes/aurora/wallpaper.jpg /sdcard/Android/data/com.bbu.sbl/files/wallpaper.jpg
```

| Theme | What it does |
|---|---|
| `default/` | The built-in look written out as JSON, as a starting point for your own |
| `aurora/` | A dark aurora wallpaper (teal and violet on navy, darker at the bottom) with a matching background colour |
| `six-columns/` | Six smaller tiles per row and one grid row visible under the dock |

## Keys

Every key is optional; a missing key keeps the value from `config.xml`, an unknown or wrongly typed key
is ignored, and a file that doesn't parse is ignored as a whole, so a theme can never break Home.

| Key | Type | Meaning |
|---|---|---|
| `dock_apps` | list of package names | The dock row, in order; missing apps are skipped |
| `hidden_apps` | list of package names | Never shown as tiles |
| `live_tv_package`, `live_tv_action` | string | The Live TV app and, if it has no leanback entry, the action to start it with |
| `source_panel_service`, `source_panel_action` | string | The TV's own source panel, for sets with no TvInputManager |
| `input_action` | string | Action sent with an HDMI/AV row's channel URI when nothing handles the standard `VIEW` (XGIMI: `com.xgimi.action.hdmiPlayer`) |
| `columns` | 4 to 8 | Tiles per row (6 by default); tiles shrink to fit from 5 up |
| `rows_below_dock` | 0 to 3 | Grid rows left visible under the dock |
| `sort_mode` | `alpha`, `recent`, `most`, `manual` | How the apps below the dock are ordered |
| `show_inputs_row` | bool | The row of connected inputs under the dock |
| `show_featured` | bool | The continue-watching shelf above the focused favourite |
| `dock_color` | `#RRGGBB` or `#AARRGGBB` | Colour of the band behind the dock |
| `module_cards` | bool | Look for module cards and ask about them (`true`); `false` turns the feature off |
| `vpn_app` | string | App the network pill opens when a VPN is configured; also shows a red "VPN off" badge while no VPN is up |
| `show_home_settings_shortcut` | bool | The "Default home" tile, where the TV has a home picker |
| `background_color` | `#RRGGBB` | Colour behind everything; black by default |
| `wallpaper` | bool | Show `wallpaper.jpg` from the theme folder (`true`), or ignore it (`false`) |

## Wallpapers

`wallpaper.jpg` is drawn full screen behind the launcher, centre-cropped. Keep it dark, 1920×1080 and
under about 300 KB: the text, the hero name and the dock band are designed for black, and a 1 GB TV
decodes the picture into memory on every Home start. Pictures larger than the screen are downsampled.
