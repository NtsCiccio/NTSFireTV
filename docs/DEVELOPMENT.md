# Sviluppo di NTSFireTV

Guida per compilare, installare e debuggare NTSFireTV su una Fire TV reale.
Funziona allo stesso modo su macOS, Linux e Windows.

## Requisiti

- **JDK 17**
- **IntelliJ IDEA** con il plugin **Android** (JetBrains) oppure Android Studio
- **Android SDK** con: SDK Platform (ultima), Build-Tools, Platform-Tools

In IntelliJ i componenti si installano da Settings → Languages & Frameworks → **Android SDK**.

> **Nota su IntelliJ:** il plugin Android supporta le nuove versioni dell'Android Gradle Plugin (AGP)
> con un po' di ritardo rispetto ad Android Studio. Non aggiornare `agp` in
> `gradle/libs.versions.toml` oltre la versione supportata dal tuo IntelliJ, altrimenti il sync fallisce.

## `adb` nel PATH

`adb` si trova nella cartella `platform-tools` dell'SDK. Percorsi predefiniti:

| OS | Percorso |
|---|---|
| macOS | `~/Library/Android/sdk/platform-tools` |
| Linux | `~/Android/Sdk/platform-tools` |
| Windows | `%LOCALAPPDATA%\Android\Sdk\platform-tools` |

Se hai scelto un'altra cartella, la vedi in IntelliJ nella pagina Android SDK.

macOS / Linux (`~/.zshrc` o `~/.bashrc`):

```bash
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"   # macOS
export PATH="$PATH:$HOME/Android/Sdk/platform-tools"           # Linux
```

Windows: aggiungi il percorso alla variabile d'ambiente `Path` dell'utente.

Verifica con `adb version`.

## Preparare la Fire TV

1. Impostazioni → La mia Fire TV → **Opzioni sviluppatore**
2. Attiva **Debug ADB** e **App da origini sconosciute**

Se "Opzioni sviluppatore" non compare: Impostazioni → La mia Fire TV → Informazioni,
premi 7 volte sul nome del dispositivo.

## Trovare l'IP della Fire TV

**Dalla TV:** Impostazioni → La mia Fire TV → Informazioni → **Rete** → "Indirizzo IP".

**Dal router:** cerca il dispositivo nella lista DHCP (di solito si chiama `amazon-...`).

> **Consiglio:** imposta una **prenotazione DHCP** sul router per la Fire TV,
> così l'IP non cambia e non devi ricontrollarlo ogni volta.

## Connessione

```bash
adb connect <IP-FIRE-TV>:5555
adb devices
```

Alla prima connessione la TV mostra il popup di autorizzazione: scegli **Consenti**
e spunta "Consenti sempre da questo computer". `adb devices` deve mostrare `device`
(non `unauthorized` o `offline`).

## Build e installazione

Il modo più rapido è il task Gradle che installa, concede tutti i permessi e apre NTSFireTV:

```bash
./gradlew installStick                              # macOS / Linux
gradlew.bat installStick                            # Windows
./gradlew installStick -Pstick=<IP-FIRE-TV>:5555    # si collega prima alla stick indicata
```

Solo installazione, senza permessi:

```bash
./gradlew installDebug
adb shell am start -n it.ntsciccio.ntsfiretv/.home.HomeActivity
```

Da IntelliJ: run configuration **Android App** con la Fire TV selezionata come dispositivo.

### Task Gradle per la Fire TV

Tutti nel gruppo **fire tv** (`./gradlew tasks --group "fire tv"`), usano l'`adb` dell'SDK.
Con `-Pstick=IP:5555` si collegano prima alla stick e la usano come dispositivo.

| Task | Cosa fa |
|---|---|
| `installStick` | `installDebug` + browser (se manca) + permessi + notification listener + salvaschermo orologio + debloat (tutti i gruppi + blocco aggiornamenti obbligatori; `-Pdebloat=false` lo salta) + avvio della home |
| `installStickRelease` | Come `installStick`, ma con la release firmata (serve `keystore.properties`, vedi Release firmata) |
| `installBrowser` | Installa **TV Bro** se manca: APK ufficiale da GitHub (variante WebView, la più leggera), scaricato una volta in `app/build/external/` e verificato con SHA-256 |
| `enableServices` | Solo permessi e notification listener (es. dopo una reinstallazione) |
| `pushWallpaper -Pimage=foto.jpg` | Copia un'immagine in `/sdcard/NTSFireTV/wallpapers/` |
| `pushTheme -Ptheme=tema.json` | Copia un tema in `/sdcard/NTSFireTV/themes/` (vedi Temi) |
| `enableScreensaver` | Imposta il salvaschermo orologio (vedi Salvaschermo) |
| `restoreScreensaver` | Rimette il salvaschermo di Fire OS salvato prima di sostituirlo |
| `logs` | `adb logcat -s NTSFireTV` (Ctrl+C per uscire) |
| `debloat -Pgruppo=nome` / `restoreBloat` | Disattiva / riattiva i servizi Amazon di `tools/debloat.txt` (vedi tools/README.md) |
| `connectStick -Pstick=IP:5555` | Solo `adb connect` |

## Permessi da concedere via ADB

Fire OS non mostra le schermate per concedere questi permessi: si danno una volta da ADB
(`./gradlew enableServices` li dà tutti). Si perdono disinstallando l'app.

| Funzione | Comando | Verifica |
|---|---|---|
| Tasto Home (M4) | `adb shell appops set it.ntsciccio.ntsfiretv GET_USAGE_STATS allow` | `adb shell appops get it.ntsciccio.ntsfiretv GET_USAGE_STATS` → `allow` |
| Sfondi e video (M5/M6) | `adb shell pm grant it.ntsciccio.ntsfiretv android.permission.READ_EXTERNAL_STORAGE` (e `WRITE_EXTERNAL_STORAGE`) | Impostazioni di NTSFireTV → "Accesso a memoria e USB" |
| Widget In riproduzione (M8) | vedi sotto | `adb shell settings get secure enabled_notification_listeners` |

Lo stato di tutti i permessi si vede anche in **NTSFireTV → Impostazioni**: su ogni voce non attiva,
OK mostra il comando ADB. Il permesso della memoria si può concedere anche dall'app (popup alla prima apertura).

## Tasto Home

Senza root, e con l'accessibilità di terze parti bloccata da Fire OS 6.7, il tasto Home è gestito da
`HomeWatcherService`: un foreground service leggero che ogni 100 ms controlla quale activity è in primo piano
(`UsageStatsManager`) e, se è la home di Amazon (`com.amazon.tv.launcher.ui.HomeActivity_vNext`), riporta
a NTSFireTV spostando in primo piano il suo task (`AppTask.moveToFront()`).

> Avviare l'activity con `startActivity` non basta: dopo il tasto Home Android blocca per ~5 s
> le activity avviate in background. `moveToFront()` non è un avvio di activity e non subisce il blocco.
> Idea presa dal launcher open source [dumbtv](https://github.com/humblemuzzu/dumbtv).

All'accensione il servizio parte con `LOCKED_BOOT_COMPLETED` (su Fire OS `BOOT_COMPLETED` arriva
con minuti di ritardo), aspetta lo sblocco e apre NTSFireTV dopo ~2,5 s.

Limiti noti:

- Per un attimo si vede la home Amazon (rilevamento in ~20-380 ms): Fire OS non avvisa le app
  del tasto Home (`CLOSE_SYSTEM_DIALOGS`), quindi non si può anticipare.
- Disattivare la home Amazon (`pm disable-user`) non è possibile: è un pacchetto protetto.

Controllo del servizio:

```bash
adb shell dumpsys activity services it.ntsciccio.ntsfiretv
```

Per disattivarlo:

```bash
adb shell appops set it.ntsciccio.ntsfiretv GET_USAGE_STATS default
adb shell am force-stop it.ntsciccio.ntsfiretv
```

## Home: recenti, dock personalizzata, meteo

- **Continua a guardare**: se c'è un video lasciato a metà in VLC e il file è raggiungibile (chiavetta collegata),
  è la prima tile della riga Recenti; OK lo riprende dal punto salvato.
- **Chiavetta USB**: con una chiavetta o un disco collegato compare in alto a sinistra un pannello con nome e
  spazio libero; OK apre il file browser dei video direttamente lì. Sparisce quando la si stacca.
- **Stato della stick** (Impostazioni): IP (con il comando `adb connect` pronto), Wi-Fi e segnale,
  memoria e spazio liberi, versione di sistema.
- **Recenti**: sopra la dock le ultime 5 app usate (esclude quelle già nella dock). Usa lo stesso permesso
  del tasto Home; senza permesso la riga non compare.
- **Tutte le app**: app divise in sezioni (Video, Musica, Giochi, Strumenti, Altre; categoria indovinata dal
  pacchetto), pulsante **Ordina** (A–Z, Più usate, Recenti: usano l'appop `GET_USAGE_STATS`, senza restano A–Z)
  e campo **Cerca** (tastiera della TV) che filtra per nome.
- **Scorciatoie** sotto orologio e meteo: Rete, Telecomandi e Bluetooth, Applicazioni, La mia Fire TV
  (pagine di Fire OS; se una non si apre, le impostazioni generali). Schermo e suoni, Preferenze e
  Accessibilità non si possono aprire da un'app: richiedono il permesso Amazon `LAUNCHER_SETTINGS`.
- **Tutte le app → tasto Menu ≡** su un'app: aggiungi/togli dalla dock, sposta in categoria, scegli un banner, nascondi.
  Le app nascoste si rimettono da **Impostazioni → App nascoste**. Nel riordino della dock, Menu su
  un'app aggiunta la toglie (le 6 tile predefinite restano). Con molte tile la dock scorre.
- **Data** sotto l'orologio, nella lingua della stick.
- **Meteo** (facoltativo): **Impostazioni → Meteo**, scrivi la città. Dati da [Open-Meteo](https://open-meteo.com)
  (gratuito, senza account): si invia solo la città, poi le coordinate ogni 30 minuti. Unico uso di internet
  dell'app (permesso `INTERNET`); spento finché non si sceglie una città.
- **Backup**: **Impostazioni → Salva le impostazioni** scrive tema, temi utente, dock, app nascoste, sfondo,
  banner in `NTSFireTV/backup/` nella memoria e su ogni chiavetta USB collegata. La città del meteo
  non è inclusa (la posizione resterebbe leggibile da altre app): dopo un ripristino va riscritta.
  **Ripristina** li rilegge (prima dalla chiavetta). Le posizioni dei video non sono incluse.

## Memoria

- L'elenco delle app e le loro immagini restano in una cache (max 8 MB), svuotata quando la home
  non si vede; l'elenco si aggiorna da solo quando si installa o disinstalla un'app.
- Se il sistema chiede memoria mentre si usa un'altra app, la home libera anche lo sfondo e lo ricarica al ritorno.

## Sfondi

Il tasto **Menu (≡)** del telecomando, nella home, apre il selettore sfondi (anche da Impostazioni → Sfondo).
Le immagini (JPG, PNG, WebP) vengono cercate in:

- `/sdcard/NTSFireTV/wallpapers/` (creata dall'app al primo avvio del selettore)
- radice e cartella `wallpapers/` di ogni chiavetta o disco USB

Copiare un'immagine dal computer:

```bash
adb shell mkdir -p /sdcard/NTSFireTV/wallpapers
adb push foto.jpg /sdcard/NTSFireTV/wallpapers/
# oppure
./gradlew pushWallpaper -Pimage=foto.jpg
```

L'immagine scelta viene ritagliata a 16:9, ridotta a 1920×1080 e salvata come copia interna
(`files/wallpaper.jpg`): resta anche se si stacca la chiavetta. Scegliendo un'immagine si decide se usarla
**sempre** oppure **solo di notte** (dalle 20 alle 7, `files/wallpaper_night.jpg`): la home cambia sfondo da sola.
"Nero" mostra uno schermo nero (ideale per TV OLED); "Predefinito" torna a `wallpaper_default`.

### Banner personalizzati

In **Tutte le app**, il tasto **Menu ≡** su un'app apre lo stesso selettore immagini per sceglierne il banner
(utile per le app installate a mano, che spesso hanno solo una piccola icona). Il banner viene ritagliato a 16:9
e salvato in `files/banners/<package>.jpg`; vale anche nella dock. "Originale" torna al banner dell'app.

> Nel repository ci sono solo sfondi originali. Immagini con personaggi o marchi protetti
> vanno tenute in locale (cartella della stick o chiavetta), non nel repo.

## Temi

**Impostazioni → Tema** elenca il tema Predefinito, il tema integrato **Retrò** e quelli utente,
più **Nuovo tema…** (editor nella TV, parte da una copia del tema attuale) e **Modifica** per i temi utente.

Nell'editor ogni voce si cambia con ◀ ▶ (OK passa al valore successivo) e l'anteprima a sinistra
si aggiorna subito. "Salva e usa questo tema" scrive un file JSON in `/sdcard/NTSFireTV/themes/`.

Un tema si può anche scrivere a mano sul computer e copiare sulla stick:

```bash
./gradlew pushTheme -Ptheme=mio-tema.json
# oppure
adb shell mkdir -p /sdcard/NTSFireTV/themes
adb push mio-tema.json /sdcard/NTSFireTV/themes/
```

Formato (tutte le chiavi sono facoltative: quelle mancanti prendono il valore del tema Predefinito).
I colori sono `#RRGGBB` oppure `#AARRGGBB` (AA = opacità, `FF` piena, `00` trasparente):

| Chiave | Cosa colora |
|---|---|
| `name` | Nome mostrato in Impostazioni → Tema |
| `accent` | Selezione delle liste, pulsanti del widget |
| `tile` / `tileFocused` | Fondo delle tile, normale e selezionata |
| `tileBorder` / `focusBorder` | Bordo delle tile, normale e selezionata |
| `cornerRadius` | Angoli in dp (0–40) |
| `panel` | Pannello del widget "In riproduzione" |
| `overlay` | Velo delle schermate sopra la home (Tutte le app, Video, Impostazioni…) |
| `textSecondary` | Sottotitoli e messaggi |
| `clockFont` / `titleFont` | `light`, `regular`, `medium`, `bold`, `black`, `condensed`, `casual` |
| `clockSize` | Dimensione dell'orologio in sp (20–96) |
| `clockShadow` | Ombra/alone dell'orologio (`#00000000` = nessuno) |
| `tileStyle` | `modern` (angoli del tema), `capsule` (a pillola), `classic` (squadrate, senza bordo), `premium` (alone colorato al focus) |
| `doubleFocus` | `true`: al focus bordo esterno nel colore accento + bordo interno bianco, visibile su qualunque sfondo |
| `homeScrim` | `false` toglie la sfumatura scura sotto la dock (utile su sfondi neri) |
| `wallpaper` | Sfondo generato dai colori (vedi sotto); se manca resta lo sfondo scelto dall'utente |

Lo sfondo del tema si attiva quando si sceglie il tema e resta finché non si sceglie un'immagine,
"Nero" o "Predefinito" dal selettore (tasto Menu); la tile **Sfondo del tema** lo rimette.
Viene disegnato una volta a 1920×1080 e tenuto in cache (`files/wallpaper_theme.png`):

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

`base` è la sfumatura di fondo (angolo come in CSS: 0 verso l'alto, 90 verso destra); ogni `blob` è una macchia
sfocata con centro `x`,`y` e raggio `r` in % dello schermo; `stripes` (facoltativo) sono righe orizzontali in basso.

Esempio minimo (le chiavi non indicate restano quelle del Predefinito):

```json
{
  "name": "Il mio tema",
  "accent": "#FF4DA3FF",
  "tileStyle": "capsule",
  "clockFont": "medium"
}
```

## Salvaschermo

`ClockDreamService` è il salvaschermo nello stile del tema attivo: lo sfondo del tema con le macchie di
colore che si spostano lentamente (circa 15 fotogrammi al secondo, attenuato da un velo scuro; con Retrò
anche le righe di tramonto) e orologio grande, data e meteo con il font del tema, che cambiano posizione
ogni 40 secondi con un movimento lento, per non lasciare segni sullo schermo. Fire OS usa il salvaschermo Amazon:
il nostro si imposta via ADB ed è **attivo di default**: lo impostano `installStick`, `installStickRelease`,
`enableServices` ed `enableScreensaver`. Prima di sostituirlo, il task salva il salvaschermo di Fire OS in
`.ntsfiretv/salvaschermo-originale.txt` (nel progetto, escluso da git; solo la prima volta).
Per tornare a quello di Amazon: `./gradlew restoreScreensaver`. A mano:

```bash
adb shell settings get secure screensaver_components     # annota il valore, serve per tornare indietro
adb shell settings put secure screensaver_components it.ntsciccio.ntsfiretv/it.ntsciccio.ntsfiretv.system.ClockDreamService
adb shell settings put secure screensaver_enabled 1
```

Per tornare al salvaschermo Amazon a mano: `settings put secure screensaver_components <valore annotato>`.
Il tempo prima che parta si regola nelle impostazioni di Fire TV (Display e suoni → Salvaschermo).
**Da verificare** sulla stick che Fire OS rispetti l'impostazione (potrebbe ignorarla o annullarla).

### Crediti

Il tema Retrò usa i colori della palette [Gruvbox](https://github.com/morhetz/gruvbox) (licenza MIT);
lo sfondo è generato, nessuna immagine esterna.

## Video da USB e VLC

La tile **Video** apre il file browser: memorie → cartelle → file video
(mkv, mp4, m4v, avi, mov, webm, ts). Indietro risale di una cartella.

- **Hardware:** serve un adattatore **OTG con alimentazione passante** (la stick ha una sola micro-USB).
  Gli **HDD meccanici** assorbono troppa corrente: usali solo con un **hub USB alimentato**.
- **File system:** FAT32 ed exFAT dovrebbero funzionare; NTFS spesso no su Fire OS. Da verificare
  sulla stick con una chiavetta per tipo.
- Se Fire OS propone di **formattare** la chiavetta come memoria interna, rispondi di no.

I video si aprono con **VLC** (`org.videolan.vlc`, dall'Appstore Amazon), tramite un ContentProvider
interno in sola lettura (`content://it.ntsciccio.ntsfiretv.files/...`). Alla chiusura VLC
restituisce la posizione: nella lista compare "In corso · 12:34 di 1:45:00" e alla
riapertura si sceglie **Riprendi** o **Dall'inizio**. Un video visto quasi fino alla fine
viene considerato finito.

## Widget "In riproduzione"

Sopra la dock compaiono titolo e artista di quello che suona (Spotify o altri player
con una MediaSession), con i pulsanti precedente / play-pausa / successivo raggiungibili col D-pad.

Per leggere le sessioni multimediali Android richiede un **notification listener** abilitato.
`./gradlew enableServices` lo aggiunge senza togliere quelli già attivi. A mano:

```bash
adb shell settings get secure enabled_notification_listeners
```

- se risponde `null` o è vuoto:

  ```bash
  adb shell settings put secure enabled_notification_listeners it.ntsciccio.ntsfiretv/it.ntsciccio.ntsfiretv.system.NowPlayingListenerService
  ```

- altrimenti aggiungi il nostro componente alla lista esistente, separato da `:`.

Subito dopo, ripeti il `get`: se Fire OS ha annullato l'impostazione (come per l'accessibilità),
il widget non può funzionare. Il listener non legge il contenuto delle notifiche.

## Release firmata

1. Crea il keystore (una volta, conservalo fuori dal repo e fanne un backup):

   ```bash
   keytool -genkeypair -v -keystore ~/ntsfiretv.jks -alias ntsfiretv -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Crea `keystore.properties` nella root del progetto (è in `.gitignore`):

   ```properties
   storeFile=/percorso/assoluto/ntsfiretv.jks
   storePassword=...
   keyAlias=ntsfiretv
   keyPassword=...
   ```

3. Compila: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
   Senza `keystore.properties` esce `app-release-unsigned.apk`, non installabile.

4. Passaggio dalla debug alla release sulla stick (una volta sola, le firme sono diverse):

   ```bash
   # prima, sulla TV: NTSFireTV → Impostazioni → Salva le impostazioni
   adb uninstall it.ntsciccio.ntsfiretv              # toglie la debug (si perdono tema, dock, sfondo scelto)
   ./gradlew installStickRelease -Pstick=<IP>:5555   # installa la release, ridà i permessi, apre la home
   ```

   Poi **Impostazioni → Ripristina le impostazioni** per riavere tema, dock, sfondo e banner.
   Da lì in avanti si aggiorna sempre con `installStickRelease` (niente più disinstallazioni).

La release usa R8 e la rimozione delle risorse inutilizzate (APK di ~150 KB contro ~1 MB della debug).

> La debug e la release hanno firme diverse: per passare dall'una all'altra serve
> `adb uninstall it.ntsciccio.ntsfiretv`, e poi vanno ridati i permessi (`./gradlew enableServices`).

Pubblicazione su GitHub Releases: crea un tag `vX.Y`, poi una release dal tag allegando l'APK firmato
(rinominato `NTSFireTV.apk`, come indicato nel README).

## Misure (RAM e avvio)

```bash
adb shell dumpsys meminfo it.ntsciccio.ntsfiretv            # "TOTAL PSS": memoria usata
adb shell am force-stop it.ntsciccio.ntsfiretv
adb shell am start -W -n it.ntsciccio.ntsfiretv/.home.HomeActivity   # "TotalTime": avvio a freddo in ms
```

Misure del 30/10/2026 (debug): avvio a freddo 358 ms, CPU del controllo tasto Home ~0%, RAM ~120 MB prima delle riduzioni.

Accorgimenti già presenti: foto di sfondo a 1920×1080 in RGB_565 (~4 MB), sfondo del tema a 960×540 (~2 MB), cache immagini app 4 MB, miniature a 256×144,
caricamenti fuori dal thread UI, polling del tasto Home sospeso a schermo spento,
widget "In riproduzione" attivo solo con la home visibile.

## Test

```bash
./gradlew test        # test sul computer, pochi secondi, non serve la stick
```

Report: `app/build/reports/tests/testDebugUnitTest/index.html`. Coprono le parti senza classi Android:
lettura dei colori, temi e sfondi in JSON (tutti i temi integrati salvati e riletti), descrizioni del meteo,
formato dei tempi e regola del "Riprendi", nomi file dei temi. Usano JUnit 4 e org.json solo per i test
(non finiscono nell'APK).

Le funzioni sulla stick (tasto Home, temi, salvaschermo…) si verificano a mano o con `adb` + screenshot:
`adb exec-out screencap -p > schermo.png`. Nota: il tasto Menu simulato (`input keyevent KEYCODE_MENU`)
non arriva alle app su Fire OS, va premuto sul telecomando vero.

## Log

```bash
./gradlew logs                                                  # solo i log di NTSFireTV
adb logcat --pid=$(adb shell pidof -s it.ntsciccio.ntsfiretv)   # macOS / Linux
adb logcat *:E                                                  # solo errori, ovunque
```

## Disinstallazione

```bash
adb uninstall it.ntsciccio.ntsfiretv
```

## Problemi comuni

| Problema | Soluzione |
|---|---|
| `unauthorized` | Accetta il popup sulla TV. Se non compare: `adb kill-server`, poi riconnetti |
| `failed to connect` / `offline` | Verifica IP e che Debug ADB sia attivo; stessa rete di PC e TV |
| `more than one device/emulator` | Indica il dispositivo: `adb -s <IP>:5555 ...` oppure `-Pstick=<IP>:5555` nei task |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Firma diversa: `adb uninstall it.ntsciccio.ntsfiretv` e reinstalla |
| Sync Gradle fallisce su AGP | Versione AGP non supportata da IntelliJ: abbassala in `gradle/libs.versions.toml` |
| Il tasto Home porta alla home Amazon | Permesso `GET_USAGE_STATS` perso (es. dopo una disinstallazione): `./gradlew enableServices` |
| Nessuna chiavetta nel file browser | Alimentazione OTG insufficiente o file system NTFS; prova un'altra chiavetta FAT32/exFAT |

## Convenzioni

- Ogni file sorgente inizia con `// SPDX-License-Identifier: GPL-3.0-or-later`
- Solo View del framework (`android.app.Activity`), niente AppCompat/Material
- Package per funzionalità: `home`, `apps`, `wallpaper`, `media`, `system`
