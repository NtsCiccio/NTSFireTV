# Fire TV tools

Helper commands, run from the computer with `adb` connected to the stick.
They work the same on macOS, Linux and Windows (they are Gradle tasks, no `.sh`/`.ps1` scripts).

## Debloat

`tools/debloat.txt` lists, in groups, the Amazon services and apps that can be disabled
to free memory and remove ads and tracking. Packages are only **disabled**
(`pm disable-user --user 0`), never uninstalled: one command turns them back on.

| Group | What it disables |
|---|---|
| `telemetria` | statistics, metrics and logs sent to Amazon |
| `pubblicita` | ads, sponsored content, shopping, the "Live" tab |
| `app-amazon` | Amazon Kids, Music, Photos, Silk, tutorials, multi-room audio |
| `alexa` | Alexa extras (voice search with the remote keeps working) |
| `notifiche` | system notices and tips |
| `inutili` | services a stick doesn't use: phone operators, SIM settings, a factory test service |
| `streaming` | preinstalled streaming apps: all **commented out**, remove the `#` only from the ones you don't use |

(Group ids are Italian: *telemetria* = telemetry, *pubblicita* = ads, *notifiche* = notifications, *inutili* = unused.)

`com.amazon.whisperjoin.middleware.np` is no longer disabled: it is needed to pair new remotes and devices.
If an older debloat disabled it: `adb shell pm enable com.amazon.whisperjoin.middleware.np`.

Result on the test stick (2026-10-30): telemetria, pubblicita, app-amazon, alexa and notifiche all accepted
(48 packages), available memory from ~510 to ~560 MB.

**During installation:** `./gradlew installStick` (and `installStickRelease`) already applies all the regular groups
plus `opz-aggiornamenti`. Running it again is harmless: packages already disabled stay disabled.
To install without touching the Amazon packages: `./gradlew installStick -Pdebloat=false`.

By hand, **one group at a time** is recommended, checking after each one that the remote, apps and home still work.

```bash
./gradlew debloat -Pstick=<IP-FIRE-TV>:5555 -Pgruppo=telemetria
./gradlew debloat -Pstick=<IP-FIRE-TV>:5555 -Pgruppo=pubblicita
./gradlew debloat -Pstick=<IP-FIRE-TV>:5555 -Pgruppo=app-amazon
./gradlew debloat -Pstick=<IP-FIRE-TV>:5555 -Pgruppo=alexa
./gradlew debloat -Pstick=<IP-FIRE-TV>:5555 -Pgruppo=notifiche
./gradlew debloat -Pstick=<IP-FIRE-TV>:5555                       # all groups at once
```

For each package the task prints whether it was disabled, refused by Fire OS (protected) or not
installed, plus the available memory before and after. Disabled packages are recorded in
`.ntsfiretv/debloat-disattivati.txt` (outside git).

To re-enable everything:

```bash
./gradlew restoreBloat -Pstick=<IP-FIRE-TV>:5555
```

A single package: `adb shell pm enable <package>`.

Good to know:

- A **Fire OS update** may re-enable some packages: just run `debloat` again.
- The **sponsored row and banner** of the Amazon home come from its launcher and from `amazon-adsystem.com`:
  with NTSFireTV you don't see them; for everything else you need an ad-blocking DNS (e.g. AdGuard Home).
- Packages that must **never** be disabled, and protected packages, are listed at the top of `debloat.txt`.
- Starting list: the [CleanYourTV](https://github.com/Gathin23/cleanyourtv) project, tested on this stick.

## Privacy settings

`tools/privacy.txt` has two parts, applied by `./gradlew privacy` and also by `debloat` (without `-Pgruppo`) and `installStick`:

- **Settings** (`settings put`): ad tracking limited, interest-based ads off, usage data upload off,
  pop-up notifications and autoplaying videos in the Amazon settings off, shorter window animations.
- **Protected packages**: the ones Fire OS refuses to disable (metrics, crash and log upload, OTA…) lose background
  running (appop `RUN_IN_BACKGROUND ignore`) and are stopped.

The original values are saved once in `.ntsfiretv/privacy-originali.txt` (outside git):
`./gradlew restorePrivacy` (or `restoreBloat`) puts them back and frees the protected packages.

## On-device DNS filter

Some Amazon services can't be disabled and keep phoning home. `./gradlew dnsFilter` installs
[personalDNSfilter](https://github.com/IngoZenz/personaldnsfilter) (open source, from F-Droid, pinned version and SHA-256),
which runs on the stick as a local VPN that only handles DNS: blocked names answer "nowhere", everything else goes
to the router as before.

```bash
./gradlew dnsFilter -Pstick=<IP-FIRE-TV>:5555          # install, or update the list
./gradlew restoreDnsFilter -Pstick=<IP-FIRE-TV>:5555   # remove it
```

- **What it blocks**: [HaGeZi's Amazon tracker list](https://github.com/hagezi/dns-blocklists) + HaGeZi Light (the most
  common ad and tracking domains, also in the browser) + `tools/dns/extra-hosts.txt` (Fire OS updates, telemetry, Amazon ads).
- **What keeps working**: Prime Video (its servers are removed from the lists), Alexa voice search, the Appstore, YouTube, weather.
- The list is built on the computer and pushed to the stick: the stick downloads nothing. Run `dnsFilter` again
  now and then to refresh it.
- The task checks at the end that blocked names are blocked and the others resolve. After a reboot the filter starts
  by itself: `./gradlew verifyStick` confirms it.
- With a filtering DNS on the network (e.g. AdGuard Home on a Raspberry Pi) the filter on the stick is not needed.
- Procedure and extra hosts from the [dumbtv](https://github.com/humblemuzzu/dumbtv) project (MIT), tested on this stick.

## Checking everything

```bash
./gradlew verifyStick -Pstick=<IP-FIRE-TV>:5555
```

Checks the Home button permission and service, Now Playing widget, screensaver, storage permission, TV Bro version,
debloated packages, privacy settings, protected packages and DNS filter, then lists what to run for anything missing.
Useful after a Fire OS update.

## Blocking OTA updates

**Optional** group `opz-aggiornamenti` in `debloat.txt`: it doesn't run with a plain `debloat`, you have to ask for it by name.

```bash
./gradlew debloat -Pstick=<IP-FIRE-TV>:5555 -Pgruppo=opz-aggiornamenti
```

- **Pros:** the stick stays on Fire OS 6.7.1.1, the version tested with NTSFireTV; an update can't
  break the Home button, widget or screensaver, nor re-enable the debloated packages.
- **Cons:** no security fixes from Amazon. Apps keep updating from the Appstore.
- **Verified on 2026-10-30 on Fire OS 6.7.1.1:** `com.amazon.tv.forcedotaupdater.v2` (forced updates)
  can be disabled; `com.amazon.device.software.ota` and `.ota.override` are **protected** ("Cannot disable a protected
  package"). Regular updates therefore remain possible.
- According to CleanYourTV these packages are **protected** on Fire OS 6.7: if the task marks them "refused",
  it can't be done from the stick, and the alternative is blocking the update domains with a filtering DNS
  (e.g. AdGuard Home): `softwareupdates.amazon.com`, `updates.amazon.com`, `amzdigitaldownloads.edgesuite.net`
  (domains to be confirmed in the query log). The on-device DNS filter (`./gradlew dnsFilter`) already blocks the
  update servers.
- To undo: `./gradlew restoreBloat` (re-enables everything the debloat disabled).

A Fire OS update could also block the permissions NTSFireTV uses (`GET_USAGE_STATS`,
notification listener): after every update run `./gradlew verifyStick`, or check **Impostazioni** (Settings) in NTSFireTV.
