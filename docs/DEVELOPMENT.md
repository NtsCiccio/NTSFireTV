# Developing NTSFireTV

Guide to building, installing and debugging NTSFireTV on a real Fire TV.
Works the same way on macOS, Linux and Windows.

## Requirements

- **JDK 17**
- **IntelliJ IDEA** with the **Android** plugin (JetBrains), or Android Studio
- **Android SDK** with: SDK Platform (latest), Build-Tools, Platform-Tools

In IntelliJ, components are installed from Settings → Languages & Frameworks → **Android SDK**.

> **Note on IntelliJ:** the Android plugin supports new Android Gradle Plugin (AGP) versions
> somewhat later than Android Studio. Don't bump `agp` in
> `gradle/libs.versions.toml` beyond the version supported by your IntelliJ, or the sync fails.

## `adb` in the PATH

`adb` lives in the SDK's `platform-tools` folder. Default locations:

| OS | Path |
|---|---|
| macOS | `~/Library/Android/sdk/platform-tools` |
| Linux | `~/Android/Sdk/platform-tools` |
| Windows | `%LOCALAPPDATA%\Android\Sdk\platform-tools` |

If you chose a different folder, IntelliJ shows it on the Android SDK page.

macOS / Linux (`~/.zshrc` or `~/.bashrc`):

```bash
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"   # macOS
export PATH="$PATH:$HOME/Android/Sdk/platform-tools"           # Linux
```

Windows: add the path to the user's `Path` environment variable.

Check with `adb version`.

## Preparing the Fire TV

1. Settings → My Fire TV → **Developer Options**
2. Turn on **ADB Debugging** and **Apps from Unknown Sources**

If "Developer Options" doesn't appear: Settings → My Fire TV → About,
press the device name 7 times.

## Finding the Fire TV's IP

**From the TV:** Settings → My Fire TV → About → **Network** → "IP Address".

**From the router:** look for the device in the DHCP list (usually named `amazon-...`).

> **Tip:** set up a **DHCP reservation** on the router for the Fire TV,
> so the IP doesn't change and you don't have to check it every time.

## Connecting

```bash
adb connect <IP-FIRE-TV>:5555
adb devices
```

On the first connection the TV shows an authorization popup: choose **Allow**
and tick "Always allow from this computer". `adb devices` must show `device`
(not `unauthorized` or `offline`).

## Build and install

The quickest way is the Gradle task that installs, grants all permissions and opens NTSFireTV:

```bash
./gradlew installStick                              # macOS / Linux
gradlew.bat installStick                            # Windows
./gradlew installStick -Pstick=<IP-FIRE-TV>:5555    # connects to the given stick first
```

Install only, without permissions:

```bash
./gradlew installDebug
adb shell am start -n it.ntsciccio.ntsfiretv/.home.HomeActivity
```

From IntelliJ: an **Android App** run configuration with the Fire TV selected as the device.

### Gradle tasks for the Fire TV

All in the **fire tv** group (`./gradlew tasks --group "fire tv"`); they use the SDK's `adb`.
With `-Pstick=IP:5555` they connect to the stick first and use it as the device.

| Task | What it does |
|---|---|
| `installStick` | `installDebug` + browser (installed or updated) + permissions + notification listener + clock screensaver + debloat (all groups + blocking of forced updates) + privacy settings (`-Pdebloat=false` skips both) + launches the home |
| `installStickRelease` | Like `installStick`, but with the signed release (needs `keystore.properties`, see Signed release) |
| `installBrowser` | Installs **TV Bro**, or updates it when the version pinned in `app/build.gradle.kts` differs: official APK from GitHub (WebView variant, the lightest), downloaded once into `app/build/external/` and verified with SHA-256 |
| `enableServices` | Permissions and notification listener only (e.g. after a reinstall) |
| `pushWallpaper -Pimage=foto.jpg` | Copies an image to `/sdcard/NTSFireTV/wallpapers/` |
| `pushTheme -Ptheme=tema.json` | Copies a theme to `/sdcard/NTSFireTV/themes/` (see Themes) |
| `enableScreensaver` | Sets the clock screensaver (see Screensaver) |
| `restoreScreensaver` | Restores the Fire OS screensaver saved before replacing it |
| `logs` | `adb logcat -s NTSFireTV` (Ctrl+C to exit) |
| `debloat -Pgruppo=nome` / `restoreBloat` | Disables / re-enables the Amazon services in `tools/debloat.txt`; without `-Pgruppo` also applies / restores the privacy settings (see tools/README.md) |
| `privacy` / `restorePrivacy` | Ad tracking and usage data off, protected Amazon packages stopped in the background (`tools/privacy.txt`); original values saved in `.ntsfiretv/` |
| `dnsFilter` / `restoreDnsFilter` | Installs / removes the on-device DNS filter (personalDNSfilter): blocks Amazon telemetry, updates and ads plus common ad domains, for every app (see tools/README.md) |
| `verifyStick` | Checks permissions, services, browser, debloat, privacy and DNS filter, and says what to run for anything missing |
| `connectStick -Pstick=IP:5555` | `adb connect` only |

## Permissions to grant via ADB

Fire OS doesn't show the screens for granting these permissions: they are granted once via ADB
(`./gradlew enableServices` grants them all). They are lost when the app is uninstalled.

| Feature | Command | Check |
|---|---|---|
| Home button (M4) | `adb shell appops set it.ntsciccio.ntsfiretv GET_USAGE_STATS allow` | `adb shell appops get it.ntsciccio.ntsfiretv GET_USAGE_STATS` → `allow` |
| Wallpapers and videos (M5/M6) | `adb shell pm grant it.ntsciccio.ntsfiretv android.permission.READ_EXTERNAL_STORAGE` (and `WRITE_EXTERNAL_STORAGE`) | NTSFireTV settings → "Accesso a memoria e USB" (Storage and USB access) |
| "In riproduzione" (Now playing) widget (M8) | see below | `adb shell settings get secure enabled_notification_listeners` |

The status of all permissions is also shown in **NTSFireTV → Impostazioni** (Settings): on each inactive entry,
OK shows the ADB command. The storage permission can also be granted from the app (popup on first launch).

## Home button

Without root, and with third-party accessibility blocked by Fire OS 6.7, the Home button is handled by
`HomeWatcherService`: a lightweight foreground service that every 100 ms checks which activity is in the foreground
(`UsageStatsManager`) and, if it is the Amazon home (`com.amazon.tv.launcher.ui.HomeActivity_vNext`), brings
NTSFireTV back by moving its task to the front (`AppTask.moveToFront()`).

> Starting the activity with `startActivity` isn't enough: after the Home button Android blocks
> activities started from the background for ~5 s. `moveToFront()` is not an activity start and isn't blocked.
> Idea taken from the open-source launcher [dumbtv](https://github.com/humblemuzzu/dumbtv).

At power-on the service starts on `LOCKED_BOOT_COMPLETED` (on Fire OS `BOOT_COMPLETED` arrives
minutes late), waits for the unlock and opens NTSFireTV after ~2.5 s.

Known limitations:

- The Amazon home is visible for a moment (detection in ~20-380 ms): Fire OS doesn't notify apps
  of the Home button (`CLOSE_SYSTEM_DIALOGS`), so it can't be anticipated.
- Disabling the Amazon home (`pm disable-user`) isn't possible: it is a protected package.

Checking the service:

```bash
adb shell dumpsys activity services it.ntsciccio.ntsfiretv
```

To disable it:

```bash
adb shell appops set it.ntsciccio.ntsfiretv GET_USAGE_STATS default
adb shell am force-stop it.ntsciccio.ntsfiretv
```

## Home: recents, custom dock, weather

- **Continua a guardare** (Continue watching): if a video was left half-watched in VLC and the file is reachable (USB stick plugged in),
  it is the first tile of the Recenti (Recents) row; OK resumes it from the saved position.
- **USB stick**: with a USB stick or disk plugged in, a panel with its name and free space appears in the top left;
  OK opens the video file browser directly there. It disappears when the drive is unplugged.
- **Stato della stick** (Stick status, in Settings): IP (with the `adb connect` command ready), Wi-Fi and signal,
  free memory and storage, system version.
- **Recenti** (Recents): above the dock, the last 5 apps used (excluding those already in the dock). Uses the same permission
  as the Home button; without it the row doesn't appear.
- **Tutte le app** (All apps): apps split into sections (Video, Musica, Giochi, Strumenti, Altre — Video, Music, Games, Tools, Other; category guessed from the
  package name), an **Ordina** (Sort) button (A–Z, Più usate, Recenti — A–Z, Most used, Recent: these use the `GET_USAGE_STATS` appop, without it they stay A–Z)
  and a **Cerca** (Search) field (TV keyboard) that filters by name.
- **Shortcuts** below the clock and weather: Rete, Telecomandi e Bluetooth, Applicazioni, La mia Fire TV
  (Network, Controllers & Bluetooth, Applications, My Fire TV — Fire OS pages; if one doesn't open, the general settings do). Display & Sounds, Preferences and
  Accessibility can't be opened from an app: they require the Amazon `LAUNCHER_SETTINGS` permission.
- **Tutte le app → Menu button ≡** on an app: add to/remove from the dock, move to a category, choose a banner, hide.
  Hidden apps are restored from **Impostazioni → App nascoste** (Settings → Hidden apps). When reordering the dock, Menu on
  an added app removes it (the 6 default tiles stay). With many tiles the dock scrolls.
- **Date** below the clock, in the stick's language.
- **Memory meter** (optional): **Impostazioni → Memoria nella home** (Settings → Memory on the home) shows the free memory under
  the clock, with a bar of what is in use; it turns amber when Android reports low memory or less than 15% is free.
  It only shows the numbers: it frees nothing.
- **Back** on the home moves the focus to the first dock tile.
- **Blocco bambini** (Child lock, in Settings): hides the Settings tile, the Fire OS shortcuts and the app menus, so the home only
  opens apps. To unlock: hold **Menu (≡)** for 3 seconds on the home, then Settings → Blocco bambini. It is not a PIN:
  holding Home still opens the Fire OS quick menu.
- **Filtro DNS** (DNS filter, in Settings): shows whether the on-device filter is active and the command to install it.
- **Weather** (optional): **Impostazioni → Meteo** (Settings → Weather), type the city. Data from [Open-Meteo](https://open-meteo.com)
  (free, no account): only the city is sent, then the coordinates every 30 minutes. The app's only use of the internet
  (`INTERNET` permission); off until a city is chosen.
- **Backup**: **Impostazioni → Salva le impostazioni** (Settings → Save settings) writes theme, user themes, dock, hidden apps, wallpaper,
  banners, home options to `NTSFireTV/backup/` in internal storage and on every plugged-in USB stick. The weather city
  is not included (the location would be readable by other apps): it must be re-entered after a restore.
  **Ripristina** (Restore) reads them back (from the USB stick first). Video positions are not included.

## Memory

- The app list and its images are kept in a cache (max 8 MB), cleared when the home
  isn't visible; the list updates itself when an app is installed or uninstalled.
- If the system asks for memory while another app is in use, the home also frees the wallpaper and reloads it on return.

## Wallpapers

The remote's **Menu (≡)** button, on the home, opens the wallpaper picker (also from Impostazioni → Sfondo, Settings → Wallpaper).
Images (JPG, PNG, WebP) are looked up in:

- `/sdcard/NTSFireTV/wallpapers/` (created by the app the first time the picker opens)
- the root and the `wallpapers/` folder of every USB stick or disk

Copying an image from the computer:

```bash
adb shell mkdir -p /sdcard/NTSFireTV/wallpapers
adb push foto.jpg /sdcard/NTSFireTV/wallpapers/
# or
./gradlew pushWallpaper -Pimage=foto.jpg
```

The chosen image is cropped to 16:9, scaled down to 1920×1080 and saved as an internal copy
(`files/wallpaper.jpg`): it stays even if the USB stick is unplugged. When choosing an image you decide whether to use it
**always** or **only at night** (from 20:00 to 7:00, `files/wallpaper_night.jpg`): the home switches wallpaper by itself.
"Nero" (Black) shows a black screen (ideal for OLED TVs); "Predefinito" (Default) goes back to `wallpaper_default`.

### Custom banners

In **Tutte le app**, the **Menu ≡** button on an app opens the same image picker to choose its banner
(useful for sideloaded apps, which often only have a small icon). The banner is cropped to 16:9
and saved in `files/banners/<package>.jpg`; it also applies in the dock. "Originale" (Original) goes back to the app's banner.

> The repository contains only original wallpapers. Images with protected characters or trademarks
> must be kept locally (stick folder or USB stick), not in the repo.

## Themes

**Impostazioni → Tema** (Settings → Theme) lists the Predefinito (Default) theme, the built-in **Retrò** theme and the user themes,
plus **Nuovo tema…** (New theme…, an on-TV editor that starts from a copy of the current theme) and **Modifica** (Edit) for user themes.

In the editor each entry is changed with ◀ ▶ (OK moves to the next value) and the preview on the left
updates immediately. "Salva e usa questo tema" (Save and use this theme) writes a JSON file to `/sdcard/NTSFireTV/themes/`.

A theme can also be written by hand on the computer and copied to the stick:

```bash
./gradlew pushTheme -Ptheme=mio-tema.json
# or
adb shell mkdir -p /sdcard/NTSFireTV/themes
adb push mio-tema.json /sdcard/NTSFireTV/themes/
```

Format (all keys are optional: missing ones take the value of the Predefinito theme).
Colors are `#RRGGBB` or `#AARRGGBB` (AA = opacity, `FF` opaque, `00` transparent):

| Key | What it colors |
|---|---|
| `name` | Name shown in Impostazioni → Tema |
| `accent` | List selection, widget buttons |
| `tile` / `tileFocused` | Tile background, normal and selected |
| `tileBorder` / `focusBorder` | Tile border, normal and selected |
| `cornerRadius` | Corners in dp (0–40) |
| `panel` | Panel of the "In riproduzione" widget |
| `overlay` | Veil of the screens above the home (Tutte le app, Video, Impostazioni…) |
| `textSecondary` | Subtitles and messages |
| `clockFont` / `titleFont` | `light`, `regular`, `medium`, `bold`, `black`, `condensed`, `casual` |
| `clockSize` | Clock size in sp (20–96) |
| `clockShadow` | Clock shadow/glow (`#00000000` = none) |
| `tileStyle` | `modern` (theme corners), `capsule` (pill-shaped), `classic` (square, no border), `premium` (colored glow on focus) |
| `doubleFocus` | `true`: on focus, outer border in the accent color + inner white border, visible on any wallpaper |
| `homeScrim` | `false` removes the dark gradient below the dock (useful on black wallpapers) |
| `wallpaper` | Wallpaper generated from colors (see below); if missing, the user's chosen wallpaper stays |

The theme wallpaper is activated when the theme is chosen and stays until an image,
"Nero" or "Predefinito" is chosen from the picker (Menu button); the **Sfondo del tema** (Theme wallpaper) tile brings it back.
It is drawn once at 1920×1080 and cached (`files/wallpaper_theme.png`):

```json
"wallpaper": {
  "base": ["#2E3440", "#242933"],
  "angle": 160,
  "blobs": [
    { "color": "#5E81AC", "alpha": 0.55, "x": 18, "y": 22, "r": 60 },
    { "color": "#88C0D0", "alpha": 0.35, "x": 82, "y": 28, "r": 48 }
  ],
  "stripes": ["#FABD2F", "#FE8019"]
}
```

`base` is the background gradient (angle as in CSS: 0 up, 90 right); each `blob` is a blurred
spot with center `x`,`y` and radius `r` in % of the screen; `stripes` (optional) are horizontal stripes at the bottom.

Minimal example (keys not given stay as in Predefinito):

```json
{
  "name": "Il mio tema",
  "accent": "#FF4DA3FF",
  "tileStyle": "capsule",
  "clockFont": "medium"
}
```

## Screensaver

`ClockDreamService` is the screensaver in the style of the active theme: the theme wallpaper with the color
spots slowly moving (about 15 frames per second, dimmed by a dark veil; with Retrò
also the sunset stripes) and a large clock, date and weather in the theme font, which change position
every 40 seconds with a slow movement, to avoid leaving marks on the screen. Fire OS uses the Amazon screensaver:
ours is set via ADB and is **on by default**: `installStick`, `installStickRelease`,
`enableServices` and `enableScreensaver` set it. Before replacing it, the task saves the Fire OS screensaver to
`.ntsfiretv/salvaschermo-originale.txt` (in the project, excluded from git; only the first time).
To go back to Amazon's: `./gradlew restoreScreensaver`. By hand:

```bash
adb shell settings get secure screensaver_components     # note the value, needed to go back
adb shell settings put secure screensaver_components it.ntsciccio.ntsfiretv/it.ntsciccio.ntsfiretv.system.ClockDreamService
adb shell settings put secure screensaver_enabled 1
```

To go back to the Amazon screensaver by hand: `settings put secure screensaver_components <valore annotato>`.
The delay before it starts is set in the Fire TV settings (Display & Sounds → Screensaver).
**To be verified** on the stick that Fire OS honors the setting (it might ignore or reset it).

### Credits

The Retrò theme uses the colors of the [Gruvbox](https://github.com/morhetz/gruvbox) palette (MIT license);
the wallpaper is generated, no external images.

## USB videos and VLC

The **Video** tile opens the file browser: storage → folders → video files
(mkv, mp4, m4v, avi, mov, webm, ts). Back goes up one folder.

- **Hardware:** you need an **OTG adapter with power passthrough** (the stick has a single micro-USB port).
  **Mechanical HDDs** draw too much current: use them only with a **powered USB hub**.
- **File system:** FAT32 and exFAT should work; NTFS often doesn't on Fire OS. To be verified
  on the stick with one USB stick per type.
- If Fire OS offers to **format** the USB stick as internal storage, say no.

Videos open with **VLC** (`org.videolan.vlc`, from the Amazon Appstore), through an internal read-only
ContentProvider (`content://it.ntsciccio.ntsfiretv.files/...`). When closed, VLC
returns the position: the list shows "In corso · 12:34 di 1:45:00" (In progress · 12:34 of 1:45:00) and on
reopening you choose **Riprendi** (Resume) or **Dall'inizio** (From the start). A video watched almost to the end
is considered finished.

## "In riproduzione" widget

Above the dock appear the title and artist of what is playing (Spotify or other players
with a MediaSession), with previous / play-pause / next buttons reachable with the D-pad.

To read media sessions Android requires an enabled **notification listener**.
`./gradlew enableServices` adds it without removing those already active. By hand:

```bash
adb shell settings get secure enabled_notification_listeners
```

- if it returns `null` or is empty:

  ```bash
  adb shell settings put secure enabled_notification_listeners it.ntsciccio.ntsfiretv/it.ntsciccio.ntsfiretv.system.NowPlayingListenerService
  ```

- otherwise append our component to the existing list, separated by `:`.

Right after, repeat the `get`: if Fire OS reset the setting (as with accessibility),
the widget can't work. The listener doesn't read notification content.

## Signed release

1. Create the keystore (once; keep it outside the repo and back it up):

   ```bash
   keytool -genkeypair -v -keystore ~/ntsfiretv.jks -alias ntsfiretv -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Create `keystore.properties` in the project root (it is in `.gitignore`):

   ```properties
   storeFile=/absolute/path/ntsfiretv.jks
   storePassword=...
   keyAlias=ntsfiretv
   keyPassword=...
   ```

3. Build: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
   Without `keystore.properties` you get `app-release-unsigned.apk`, which can't be installed.

4. Switching from debug to release on the stick (only once, the signatures differ):

   ```bash
   # first, on the TV: NTSFireTV → Impostazioni → Salva le impostazioni
   adb uninstall it.ntsciccio.ntsfiretv              # removes the debug build (theme, dock, chosen wallpaper are lost)
   ./gradlew installStickRelease -Pstick=<IP>:5555   # installs the release, re-grants permissions, opens the home
   ```

   Then **Impostazioni → Ripristina le impostazioni** (Settings → Restore settings) to get theme, dock, wallpaper and banners back.
   From then on, always update with `installStickRelease` (no more uninstalls).

The release uses R8 and unused resource shrinking (~150 KB APK versus ~1 MB for debug).

> Debug and release have different signatures: switching between them requires
> `adb uninstall it.ntsciccio.ntsfiretv`, and then the permissions must be granted again (`./gradlew enableServices`).

The signed release is only for your own stick: the project doesn't publish APKs
(it is always installed by building from source, see README).

## Measurements (RAM and startup)

```bash
adb shell dumpsys meminfo it.ntsciccio.ntsfiretv            # "TOTAL PSS": memory used
adb shell am force-stop it.ntsciccio.ntsfiretv
adb shell am start -W -n it.ntsciccio.ntsfiretv/.home.HomeActivity   # "TotalTime": cold start in ms
```

Measurements from 2026-10-30 (debug): cold start 358 ms, Home button watcher CPU ~0%, RAM ~120 MB before the reductions.

Measures already in place: wallpaper photo at 1920×1080 in RGB_565 (~4 MB), theme wallpaper at 960×540 (~2 MB), app image cache 4 MB, thumbnails at 256×144,
loading off the UI thread, Home button polling suspended while the screen is off,
"In riproduzione" widget active only while the home is visible.

## Tests

```bash
./gradlew test        # tests on the computer, a few seconds, no stick needed
```

Report: `app/build/reports/tests/testDebugUnitTest/index.html`. They cover the parts without Android classes:
color parsing, themes and wallpapers in JSON (all built-in themes saved and read back), weather descriptions,
time formatting and the "Riprendi" rule, theme file names. They use JUnit 4 and org.json for tests only
(not included in the APK).

Features on the stick (Home button, themes, screensaver…) are checked by hand or with `adb` + screenshots:
`adb exec-out screencap -p > schermo.png`. Note: a simulated Menu button (`input keyevent KEYCODE_MENU`)
doesn't reach apps on Fire OS; it must be pressed on the real remote.

## Logs

```bash
./gradlew logs                                                  # NTSFireTV logs only
adb logcat --pid=$(adb shell pidof -s it.ntsciccio.ntsfiretv)   # macOS / Linux
adb logcat *:E                                                  # errors only, everywhere
```

## Uninstalling

```bash
adb uninstall it.ntsciccio.ntsfiretv
```

## Troubleshooting

| Problem | Solution |
|---|---|
| `unauthorized` | Accept the popup on the TV. If it doesn't appear: `adb kill-server`, then reconnect |
| `failed to connect` / `offline` | Check the IP and that ADB Debugging is on; PC and TV on the same network |
| `more than one device/emulator` | Specify the device: `adb -s <IP>:5555 ...` or `-Pstick=<IP>:5555` in the tasks |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Different signature: `adb uninstall it.ntsciccio.ntsfiretv` and reinstall |
| Gradle sync fails on AGP | AGP version not supported by IntelliJ: lower it in `gradle/libs.versions.toml` |
| The Home button goes to the Amazon home | `GET_USAGE_STATS` permission lost (e.g. after an uninstall): `./gradlew enableServices` |
| No USB stick in the file browser | Insufficient OTG power or NTFS file system; try another FAT32/exFAT USB stick |

## Conventions

- Every source file starts with `// SPDX-License-Identifier: GPL-3.0-or-later`
- Framework Views only (`android.app.Activity`), no AppCompat/Material
- Packages by feature: `home`, `apps`, `wallpaper`, `media`, `system`
