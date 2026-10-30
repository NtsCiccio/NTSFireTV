# Strumenti per la Fire TV

Comandi di supporto, da lanciare dal computer con `adb` collegato alla stick.
Funzionano uguali su macOS, Linux e Windows (sono task Gradle, niente script `.sh`/`.ps1`).

## Debloat

`tools/debloat.txt` elenca, a gruppi, i servizi e le app Amazon che si possono disattivare
per liberare memoria e togliere pubblicità e statistiche. I pacchetti vengono solo **disattivati**
(`pm disable-user --user 0`), mai disinstallati: si riattivano con un comando.

| Gruppo | Cosa disattiva |
|---|---|
| `telemetria` | statistiche, metriche e log inviati ad Amazon |
| `pubblicita` | pubblicità, sponsorizzati, shopping, scheda "Live" |
| `app-amazon` | Amazon Kids, Music, Photos, Silk, tutorial, audio multi-stanza |
| `alexa` | extra di Alexa (la ricerca vocale col telecomando resta) |
| `notifiche` | avvisi e note di sistema |
| `streaming` | app di streaming preinstallate: tutte **spente**, togli il `#` solo da quelle che non usi |

Risultato del 30/10/2026 sulla stick: telemetria, pubblicita, app-amazon, alexa e notifiche tutti accettati
(48 pacchetti), memoria disponibile da ~510 a ~560 MB.

**Con l'installazione:** `./gradlew installStick` (e `installStickRelease`) applica già tutti i gruppi normali
più `opz-aggiornamenti`. Rilanciarlo è innocuo: i pacchetti già disattivati restano tali.
Per installare senza toccare i pacchetti Amazon: `./gradlew installStick -Pdebloat=false`.

A mano, consigliato **un gruppo alla volta**, controllando dopo ognuno che telecomando, app e home funzionino.

```bash
./gradlew debloat -Pstick=<IP-FIRE-TV>:5555 -Pgruppo=telemetria
./gradlew debloat -Pstick=<IP-FIRE-TV>:5555 -Pgruppo=pubblicita
./gradlew debloat -Pstick=<IP-FIRE-TV>:5555 -Pgruppo=app-amazon
./gradlew debloat -Pstick=<IP-FIRE-TV>:5555 -Pgruppo=alexa
./gradlew debloat -Pstick=<IP-FIRE-TV>:5555 -Pgruppo=notifiche
./gradlew debloat -Pstick=<IP-FIRE-TV>:5555                       # tutti i gruppi insieme
```

Il task stampa per ogni pacchetto se è stato disattivato, se Fire OS lo ha rifiutato (protetto) o se non è
installato, e la memoria disponibile prima e dopo. I pacchetti disattivati vengono annotati in
`.ntsfiretv/debloat-disattivati.txt` (fuori da git).

Per riattivare tutto:

```bash
./gradlew restoreBloat -Pstick=<IP-FIRE-TV>:5555
```

Un singolo pacchetto: `adb shell pm enable <pacchetto>`.

Da sapere:

- Un **aggiornamento di Fire OS** può riattivare alcuni pacchetti: basta rilanciare `debloat`.
- La **riga sponsorizzata e il banner** della home Amazon arrivano dal launcher e da `amazon-adsystem.com`:
  con NTSFireTV non si vedono, e per il resto serve un DNS che blocca la pubblicità (es. AdGuard Home).
- Pacchetti da **non** disattivare mai e pacchetti protetti sono elencati in testa a `debloat.txt`.
- Lista di partenza: progetto [CleanYourTV](https://github.com/Gathin23/cleanyourtv), provata su questa stick.

## Blocco degli aggiornamenti OTA

Gruppo **facoltativo** `opz-aggiornamenti` di `debloat.txt`: non parte con `debloat` normale, va chiesto per nome.

```bash
./gradlew debloat -Pstick=<IP-FIRE-TV>:5555 -Pgruppo=opz-aggiornamenti
```

- **Pro:** la stick resta su Fire OS 6.7.1.1, la versione provata con NTSFireTV; un aggiornamento non può
  rompere tasto Home, widget o salvaschermo, né riattivare i pacchetti del debloat.
- **Contro:** niente correzioni di sicurezza da Amazon. Le app continuano ad aggiornarsi dall'Appstore.
- **Verificato il 30/10/2026 su Fire OS 6.7.1.1:** `com.amazon.tv.forcedotaupdater.v2` (aggiornamenti obbligatori)
  si disattiva; `com.amazon.device.software.ota` e `.ota.override` sono **protetti** ("Cannot disable a protected
  package"). Gli aggiornamenti normali restano quindi possibili.
- Secondo CleanYourTV su Fire OS 6.7 questi pacchetti sono **protetti**: se il task li segna "rifiutati"
  dalla stick non si può fare, e l'alternativa è bloccare i domini degli aggiornamenti con un DNS filtrante
  (es. AdGuard Home): `softwareupdates.amazon.com`, `updates.amazon.com`, `amzdigitaldownloads.edgesuite.net`
  (domini da verificare nel registro delle query).
- Per sbloccare: `./gradlew restoreBloat` (riattiva tutto ciò che il debloat ha disattivato).

Un aggiornamento di Fire OS potrebbe anche bloccare i permessi usati da NTSFireTV (`GET_USAGE_STATS`,
notification listener): dopo ogni aggiornamento controlla **Impostazioni → stato dei servizi** in NTSFireTV.
