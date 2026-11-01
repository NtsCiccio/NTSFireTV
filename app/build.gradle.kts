// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
}

// Release signing: credentials in keystore.properties (excluded from git, see docs/DEVELOPMENT.md)
val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "it.ntsciccio.ntsfiretv"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "it.ntsciccio.ntsfiretv"
        minSdk = 25
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 + unused resource removal: smaller APK and less RAM for the code
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without keystore.properties the release APK is unsigned
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Only for tests on the computer (./gradlew test), not included in the APK.
    // org.json: the one in android.jar on the computer is a non-working stub.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

kotlin {
    jvmToolchain(17)
}

// ---------------------------------------------------------------------------------------------
// Fire TV tasks (group "fire tv"), cross-platform: they use the configured SDK's adb.
// With several devices, or to connect first: -Pstick=<IP-FIRE-TV>:5555
// ---------------------------------------------------------------------------------------------

/** Runs `adb` commands on the Fire TV in sequence. */
abstract class AdbTask : DefaultTask() {

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Internal
    abstract val adb: RegularFileProperty

    /** Stick address (IP:port): if set, runs `adb connect` and uses `-s`. */
    @get:Internal
    abstract val serial: Property<String>

    @get:Internal
    abstract val commands: ListProperty<List<String>>

    /** Component to add to the notification listeners, without removing the ones already enabled. */
    @get:Internal
    abstract val notificationListener: Property<String>

    /** External app to install if missing or at another version: package, version, APK URL, expected SHA-256 and cache file. */
    @get:Internal
    abstract val externalPackage: Property<String>

    @get:Internal
    abstract val externalVersion: Property<String>

    @get:Internal
    abstract val externalApkUrl: Property<String>

    @get:Internal
    abstract val externalApkSha256: Property<String>

    @get:Internal
    abstract val externalApkFile: RegularFileProperty

    /** Screensaver to set; the previous one is saved to [screensaverBackup]. */
    @get:Internal
    abstract val screensaverComponent: Property<String>

    /** Local file (outside git) with Fire OS's original screensaver. */
    @get:Internal
    abstract val screensaverBackup: RegularFileProperty

    /** `true`: restores the screensaver saved in [screensaverBackup]. */
    @get:Internal
    abstract val restoreScreensaver: Property<Boolean>

    /** Debloat list (tools/debloat.txt): if set, the task disables or re-enables the packages. */
    @get:Internal
    abstract val debloatList: RegularFileProperty

    /** Only this group of the list (e.g. "telemetria"); empty = all. */
    @get:Internal
    abstract val debloatGroup: Property<String>

    /** `true` = re-enable instead of disabling. */
    @get:Internal
    abstract val debloatRestore: Property<Boolean>

    /** Optional groups ("opz-...") to apply together with the normal ones (e.g. from the install task). */
    @get:Internal
    abstract val debloatExtraGroups: ListProperty<String>

    /** Packages actually disabled, so they can be re-enabled (outside git). */
    @get:Internal
    abstract val debloatRecord: RegularFileProperty

    /** Privacy list (tools/privacy.txt): settings to apply and protected packages to stop in the background. */
    @get:Internal
    abstract val privacyList: RegularFileProperty

    /** `true` = put back the original values saved in [privacyBackup]. */
    @get:Internal
    abstract val privacyRestore: Property<Boolean>

    /** Original values of the changed settings (outside git). */
    @get:Internal
    abstract val privacyBackup: RegularFileProperty

    /** On-device DNS filter: extra hosts file; if set, configures personalDNSfilter (installed via external*). */
    @get:Internal
    abstract val dnsExtraHosts: RegularFileProperty

    /** Where the blocklist and the config are prepared before pushing them. */
    @get:Internal
    abstract val dnsWorkDir: DirectoryProperty

    /** `true` = removes the DNS filter. */
    @get:Internal
    abstract val dnsRemove: Property<Boolean>

    /** Checks what installStick, debloat, privacy and dnsFilter set up: app id, listener, screensaver, browser. */
    @get:Internal
    abstract val verifyApp: Property<String>

    @get:Internal
    abstract val verifyListener: Property<String>

    @get:Internal
    abstract val verifyScreensaver: Property<String>

    @get:Internal
    abstract val verifyBrowser: Property<String>

    init {
        group = "fire tv"
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun runCommands() {
        serial.orNull?.let { run(listOf("connect", it), withSerial = false) }
        // verifyStick only reads: the lists it is given are not applied
        verifyApp.orNull?.let {
            verifyStick(it)
            return
        }
        externalPackage.orNull?.let(::installExternalApk)
        notificationListener.orNull?.let(::enableNotificationListener)
        screensaverComponent.orNull?.let(::enableScreensaver)
        if (restoreScreensaver.getOrElse(false)) restorePreviousScreensaver()
        if (debloatList.isPresent) {
            if (debloatRestore.getOrElse(false)) restoreDebloat() else applyDebloat()
        }
        if (privacyList.isPresent) {
            if (privacyRestore.getOrElse(false)) restorePrivacy() else applyPrivacy()
        }
        dnsExtraHosts.orNull?.let { setupDnsFilter(it.asFile) }
        if (dnsRemove.getOrElse(false)) removeDnsFilter()
        commands.getOrElse(emptyList()).forEach { run(it) }
    }

    /** Active packages of the list (lines without #), by group. */
    private fun debloatPackages(): Map<String, List<String>> {
        val groups = linkedMapOf<String, MutableList<String>>()
        var group = "senza-gruppo"
        debloatList.get().asFile.readLines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("[") -> group = line.substringAfter('[').substringBefore(']').trim()
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> groups.getOrPut(group) { mutableListOf() } += line.substringBefore('#').trim()
            }
        }
        return groups
    }

    private fun applyDebloat() {
        val wanted = debloatGroup.orNull?.takeIf { it.isNotBlank() }
        val groups = debloatPackages()
        if (wanted != null && wanted !in groups) {
            throw GradleException("Gruppo \"$wanted\" non trovato. Gruppi: ${groups.keys.joinToString()}")
        }
        val installed = capture(listOf("shell", "pm", "list", "packages"))
            .lines().map { it.trim().removePrefix("package:") }.toSet()
        logger.lifecycle("Memoria prima: ${memAvailable()}")

        val record = debloatRecord.get().asFile
        val done = if (record.exists()) record.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()
        var disabled = 0
        val skipped = mutableListOf<String>()
        val refused = mutableListOf<String>()
        // "opz-..." groups (e.g. update blocking) are applied only when requested by name
        val extra = debloatExtraGroups.getOrElse(emptyList())
        groups.filterKeys { if (wanted == null) !it.startsWith(OPTIONAL_PREFIX) || it in extra else it == wanted }.forEach { (group, packages) ->
            logger.lifecycle("[$group]")
            packages.forEach { pkg ->
                if (pkg !in installed) {
                    skipped += pkg
                    return@forEach
                }
                val output = capture(listOf("shell", "pm", "disable-user", "--user", "0", pkg), includeErrors = true)
                if ("new state" in output) {
                    disabled++
                    done += pkg
                    logger.lifecycle("  disattivato  $pkg")
                } else {
                    refused += pkg
                    logger.lifecycle("  rifiutato    $pkg  (${output.lines().lastOrNull { it.isNotBlank() }?.trim()})")
                }
            }
        }
        record.parentFile.mkdirs()
        record.writeText(done.sorted().joinToString("\n"))

        logger.lifecycle("")
        logger.lifecycle("Disattivati: $disabled · non installati (saltati): ${skipped.size} · rifiutati da Fire OS: ${refused.size}")
        logger.lifecycle("Memoria dopo: ${memAvailable()}")
        logger.lifecycle("Per riattivare tutto: ./gradlew restoreBloat")
    }

    private fun restoreDebloat() {
        val record = debloatRecord.get().asFile
        // Without a record, all packages in the list are re-enabled (non-installed ones just print an error)
        val packages = if (record.exists()) {
            record.readLines().filter { it.isNotBlank() }
        } else {
            debloatPackages().values.flatten()
        }
        packages.forEach { pkg ->
            val output = capture(listOf("shell", "pm", "enable", pkg), includeErrors = true)
            logger.lifecycle(if ("new state" in output) "  riattivato   $pkg" else "  saltato      $pkg")
        }
        if (record.exists()) record.delete()
        logger.lifecycle("Fatto. Se qualcosa non torna subito, riavvia la stick.")
    }

    /** Sections of tools/privacy.txt: "[impostazioni]" (namespace key value) and "[protetti]" (packages). */
    private fun privacySections(): Map<String, List<String>> {
        val sections = linkedMapOf<String, MutableList<String>>()
        var section = ""
        privacyList.get().asFile.readLines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("[") -> section = line.substringAfter('[').substringBefore(']').trim()
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> sections.getOrPut(section) { mutableListOf() } += line.substringBefore('#').trim()
            }
        }
        return sections
    }

    private fun privacySettings(): List<List<String>> =
        privacySections()[PRIVACY_SETTINGS].orEmpty().map { it.split(Regex("\\s+")) }.filter { it.size == 3 }

    private fun privacyProtected(): List<String> = privacySections()[PRIVACY_PROTECTED].orEmpty()

    /** Applies the settings (saving the original values only the first time) and stops the protected packages in the background. */
    private fun applyPrivacy() {
        val backup = privacyBackup.get().asFile
        val saved = if (backup.exists()) backup.readLines().filter { it.isNotBlank() }.toMutableList() else mutableListOf()
        val savedKeys = saved.map { it.split(' ').take(2).joinToString(" ") }.toMutableSet()
        logger.lifecycle("[impostazioni privacy]")
        privacySettings().forEach { (namespace, key, value) ->
            if ("$namespace $key" !in savedKeys) {
                saved += "$namespace $key ${capture(listOf("shell", "settings", "get", namespace, key))}"
                savedKeys += "$namespace $key"
            }
            capture(listOf("shell", "settings", "put", namespace, key, value))
            logger.lifecycle("  $namespace $key = $value")
        }
        backup.parentFile.mkdirs()
        backup.writeText(saved.joinToString("\n"))

        logger.lifecycle("[protetti: niente esecuzione in background]")
        privacyProtected().forEach { pkg ->
            val uid = packageUid(pkg)
            when {
                uid == null -> logger.lifecycle("  assente      $pkg")
                // A shared system uid would also hit other system packages: left alone
                uid < FIRST_APP_UID -> logger.lifecycle("  saltato      $pkg  (uid di sistema $uid)")
                else -> {
                    capture(listOf("shell", "appops", "set", pkg, "RUN_IN_BACKGROUND", "ignore"))
                    // Fire OS refuses to force-stop protected packages: the appop applies from the next start
                    val stop = capture(listOf("shell", "am", "force-stop", pkg), includeErrors = true)
                    logger.lifecycle(if ("SecurityException" in stop) "  bloccato     $pkg  (si ferma al prossimo riavvio)" else "  fermato      $pkg")
                }
            }
        }
        logger.lifecycle("Valori originali in ${backup.path}; per rimetterli: ./gradlew restorePrivacy")
    }

    private fun restorePrivacy() {
        val backup = privacyBackup.get().asFile
        if (backup.exists()) {
            backup.readLines().filter { it.isNotBlank() }.forEach { line ->
                val (namespace, key, value) = line.split(' ', limit = 3).let { Triple(it[0], it[1], it.getOrElse(2) { "null" }) }
                if (value == "null" || value.isEmpty()) {
                    capture(listOf("shell", "settings", "delete", namespace, key))
                } else {
                    capture(listOf("shell", "settings", "put", namespace, key, value))
                }
                logger.lifecycle("  ripristinato $namespace $key = $value")
            }
            backup.delete()
        } else {
            logger.lifecycle("Nessun valore originale salvato in ${backup.path}: impostazioni lasciate così")
        }
        privacyProtected().forEach { pkg ->
            if (packageUid(pkg) != null) capture(listOf("shell", "appops", "set", pkg, "RUN_IN_BACKGROUND", "allow"))
        }
        logger.lifecycle("Pacchetti protetti di nuovo liberi in background")
    }

    private fun packageUid(pkg: String): Int? =
        Regex("userId=(\\d+)").find(capture(listOf("shell", "dumpsys", "package", pkg)))?.groupValues?.get(1)?.toIntOrNull()

    /**
     * personalDNSfilter as a local DNS-only VPN, with the procedure tested by the dumbtv project on Fire OS 6:
     * blocklist and config are prepared here, pushed into the app's folder, then the app is started.
     */
    private fun setupDnsFilter(extraHosts: File) {
        val gateway = defaultGateway() ?: throw GradleException("Router non trovato (ip route): la stick è connessa alla rete?")
        // Every DNS server the stick knows goes into the tunnel, or lookups could go around the filter
        val routes = (listOf(gateway) + (1..4).map { capture(listOf("shell", "getprop", "net.dns$it")) }
            .filter { it.matches(Regex("[0-9a-fA-F.:]+")) && ('.' in it || ':' in it) && !it.startsWith("127.") && it != "::1" })
            .distinct()
        logger.lifecycle("Router $gateway · DNS instradati nel filtro: ${routes.joinToString()}")

        val work = dnsWorkDir.get().asFile.apply { mkdirs() }
        val hosts = linkedSetOf<String>()
        DNS_LISTS.forEach { url ->
            logger.lifecycle("Scarico $url")
            hosts += hostLines(URI(url).toURL().readText())
        }
        hosts += hostLines(extraHosts.readText())
        hosts.removeAll { host -> DNS_KEEP.any { it.containsMatchIn(host) } }
        if (hosts.size < DNS_MIN_ENTRIES) throw GradleException("Lista DNS troppo corta (${hosts.size} voci): download non riuscito?")
        val list = File(work, "FILTERHOSTS.TXT").apply { writeText(hosts.joinToString("\n", postfix = "\n")) }
        logger.lifecycle("Lista: ${hosts.size} domini")

        logger.lifecycle("Scarico la configurazione di personalDNSfilter")
        val template = URI(DNS_CONF_URL).toURL().readBytes()
        val templateHash = MessageDigest.getInstance("SHA-256").digest(template).joinToString("") { "%02x".format(it) }
        if (templateHash != DNS_CONF_SHA256) throw GradleException("SHA-256 di dnsfilter.conf non corrisponde: configurazione annullata")
        val conf = File(work, "dnsfilter.conf").apply {
            writeText(
                patchDnsConf(
                    String(template),
                    mapOf(
                        // No downloads on the stick: the list is pushed from here
                        "previousAutoUpdateURL" to "",
                        "detectDNS" to "false",
                        "fallbackDNS" to "#router; [$gateway]::53::UDP",
                        "routeIPs" to routes.joinToString("; "),
                        "routeUnderlyingDNS" to "true",
                        "showInitialInfoPopUp" to "false",
                        "reloadIntervalDays" to "3650",
                        "filterHostsFile" to "FILTERHOSTS.TXT",
                        "androidAppWhiteList" to "<none>",
                        "AUTOSTART" to "true",
                    ),
                ),
            )
        }
        val downloadInfo = File(work, "FILTERHOSTS.TXT.DLD_CNT").apply { writeText("${hosts.size}\n${System.currentTimeMillis()}\n") }

        val activity = "$DNS_PACKAGE/.DNSProxyActivity"
        val dir = "/sdcard/Android/data/$DNS_PACKAGE/files/PersonalDNSFilter"
        capture(listOf("shell", "pm", "enable", DNS_PACKAGE))
        // The VPN consent dialog is phone-sized and ignores the remote: the appop skips it
        capture(listOf("shell", "appops", "set", DNS_PACKAGE, "ACTIVATE_VPN", "allow"))
        capture(listOf("shell", "dumpsys", "deviceidle", "whitelist", "+$DNS_PACKAGE"))
        // The first start creates the folder and a default config; then the app is stopped and ours is pushed
        capture(listOf("shell", "am", "start", "-n", activity))
        val ready = (1..10).any {
            Thread.sleep(2000)
            capture(listOf("shell", "test -d $dir && echo ok")) == "ok"
        }
        if (!ready) throw GradleException("personalDNSfilter non ha creato $dir")
        capture(listOf("shell", "am", "force-stop", DNS_PACKAGE))
        Thread.sleep(2000)
        run(listOf("push", conf.absolutePath, "$dir/dnsfilter.conf"))
        // A stale index would keep serving the previous list; the marker must exist for the rebuild
        capture(listOf("shell", "rm -rf $dir/FILTERHOSTS.TXT.idx; touch $dir/IDX_OUTDATED"))
        run(listOf("push", list.absolutePath, "$dir/FILTERHOSTS.TXT"))
        // Without it the app "updates" at the first start and overwrites the pushed list
        run(listOf("push", downloadInfo.absolutePath, "$dir/FILTERHOSTS.TXT.DLD_CNT"))
        capture(listOf("shell", "settings", "put", "secure", "always_on_vpn_app", DNS_PACKAGE))
        // Fire OS 6 forgets always_on_vpn_app at boot: the app's own boot start (AUTOSTART) brings it up,
        // and Android sends no boot broadcast to a force-stopped app, so it is opened once now
        capture(listOf("shell", "am", "start", "-n", activity))
        Thread.sleep(DNS_START_WAIT_MS)
        capture(listOf("shell", "input", "keyevent", "KEYCODE_HOME"))

        logger.lifecycle("[verifica]")
        val results = DNS_TEST_BLOCKED.map { it to (resolvesBlocked(it, attempts = 3) == true) } +
            DNS_TEST_ALLOWED.map { it to (resolvesBlocked(it) == false) }
        results.forEach { (host, ok) ->
            val expected = if (host in DNS_TEST_BLOCKED) "bloccato" else "raggiungibile"
            logger.lifecycle("  ${if (ok) "✓" else "✗"} $host $expected")
        }
        if (results.any { !it.second }) {
            throw GradleException("Il filtro DNS non risponde come previsto: riavvia la stick e lancia ./gradlew verifyStick")
        }
        logger.lifecycle("Filtro DNS attivo. Per toglierlo: ./gradlew restoreDnsFilter")
    }

    private fun removeDnsFilter() {
        capture(listOf("shell", "settings", "delete", "secure", "always_on_vpn_app"))
        capture(listOf("shell", "dumpsys", "deviceidle", "whitelist", "-$DNS_PACKAGE"))
        run(listOf("uninstall", DNS_PACKAGE))
        logger.lifecycle("Filtro DNS tolto. Se qualche app non va in rete, riavvia la stick.")
    }

    /** Domains of a list: first word of each line, without comments; plain domains or hosts-file lines. */
    private fun hostLines(text: String): List<String> = text.lineSequence()
        .map { it.substringBefore('#').trim().lowercase() }
        .filter { it.isNotEmpty() }
        .map { line -> line.split(Regex("\\s+")).let { if (it.size > 1 && it[0] in HOSTS_FILE_IPS) it[1] else it[0] } }
        .filter { it.contains('.') }
        .toList()

    /** Sets the keys in personalDNSfilter's config; a missing key means the template changed: better to stop. */
    private fun patchDnsConf(template: String, values: Map<String, String>): String {
        var conf = template
        values.forEach { (key, value) ->
            val line = Regex("^${Regex.escape(key)} *=.*$", RegexOption.MULTILINE)
            if (!line.containsMatchIn(conf)) throw GradleException("dnsfilter.conf senza la chiave $key: aggiorna DNS_CONF_URL")
            conf = conf.replace(line, Regex.escapeReplacement("$key = $value"))
        }
        // Default list downloads off
        val updates = Regex("^filterAutoUpdateURL *=", RegexOption.MULTILINE)
        if (!updates.containsMatchIn(conf)) throw GradleException("dnsfilter.conf senza filterAutoUpdateURL: aggiorna DNS_CONF_URL")
        return conf.replace(updates, "#filterAutoUpdateURL =")
    }

    private fun defaultGateway(): String? =
        capture(listOf("shell", "ip", "route", "show", "table", "all")).lines()
            .filter { it.startsWith("default") && " via " in it && " dev tun" !in it }
            .sortedBy { "linkdown" in it }
            .firstNotNullOfOrNull { Regex(" via ([0-9a-fA-F.:]+)").find(it)?.groupValues?.get(1) }
            ?: capture(listOf("shell", "getprop", "dhcp.wlan0.gateway")).takeIf { it.isNotBlank() }

    /** true = the name answers 0.0.0.0/127.0.0.1 (blocked), false = it resolves, null = no answer. */
    private fun resolvesBlocked(host: String, attempts: Int = 1): Boolean? {
        var result: Boolean? = null
        repeat(attempts) { attempt ->
            val line = capture(listOf("shell", "ping", "-c", "1", "-W", "2", host), includeErrors = true).lineSequence().firstOrNull().orEmpty()
            val ip = Regex("\\(([0-9.]+)\\)").find(line)?.groupValues?.get(1)
            result = when (ip) {
                null -> null
                "0.0.0.0", "127.0.0.1" -> true
                else -> false
            }
            if (result == true || attempt == attempts - 1) return result
            Thread.sleep(DNS_RETRY_MS)
        }
        return result
    }

    /** Everything NTSFireTV sets up on the stick, with ✓ or ✗ and what to run when something is missing. */
    private fun verifyStick(app: String) {
        val problems = mutableListOf<String>()
        fun check(ok: Boolean, what: String, fix: String) {
            logger.lifecycle("  ${if (ok) "✓" else "✗"} $what")
            if (!ok) problems += "$what → $fix"
        }
        fun shell(vararg args: String) = capture(listOf("shell") + args)

        logger.lifecycle("[NTSFireTV]")
        check(shell("pm", "path", app).startsWith("package:"), "app installata", "./gradlew installStick")
        check("allow" in shell("appops", "get", app, "GET_USAGE_STATS"), "tasto Home (GET_USAGE_STATS)", "./gradlew enableServices")
        check("ServiceRecord" in shell("dumpsys", "activity", "services", "$app/.system.HomeWatcherService"), "servizio del tasto Home in esecuzione", "apri NTSFireTV una volta")
        check(
            verifyListener.get() in shell("settings", "get", "secure", "enabled_notification_listeners"),
            "widget In riproduzione",
            "./gradlew enableServices",
        )
        check(shell("settings", "get", "secure", "screensaver_components") == verifyScreensaver.get(), "salvaschermo NTSFireTV", "./gradlew enableScreensaver")
        check(
            "android.permission.READ_EXTERNAL_STORAGE: granted=true" in shell("dumpsys", "package", app),
            "permesso memoria e USB",
            "./gradlew enableServices",
        )
        verifyBrowser.orNull?.let { browser ->
            val (pkg, version) = browser.split('=')
            val installed = Regex("versionName=(\\S+)").find(shell("dumpsys", "package", pkg))?.groupValues?.get(1)
            check(installed == version, "browser TV Bro $version (sulla stick: ${installed ?: "assente"})", "./gradlew installBrowser")
        }

        logger.lifecycle("[debloat]")
        val record = debloatRecord.orNull?.asFile
        if (record != null && record.exists()) {
            val disabled = shell("pm", "list", "packages", "-d").lines().map { it.trim().removePrefix("package:") }.toSet()
            // Packages later removed from the list (e.g. kept on purpose) are not expected to be disabled
            val active = debloatList.orNull?.let { debloatPackages().values.flatten().toSet() }
            val expected = record.readLines().filter { it.isNotBlank() && (active == null || it in active) }
            val enabledAgain = expected.filterNot { it in disabled }
            check(enabledAgain.isEmpty(), "${expected.size - enabledAgain.size}/${expected.size} pacchetti disattivati" +
                if (enabledAgain.isEmpty()) "" else " (riattivati: ${enabledAgain.joinToString()})", "./gradlew debloat")
        } else {
            check(false, "debloat mai eseguito da questo computer", "./gradlew debloat")
        }

        logger.lifecycle("[privacy]")
        if (privacyList.isPresent) {
            val wrong = privacySettings().filter { (namespace, key, value) -> shell("settings", "get", namespace, key) != value }
            check(wrong.isEmpty(), "impostazioni privacy" + if (wrong.isEmpty()) "" else " (diverse: ${wrong.joinToString { it[1] }})", "./gradlew privacy")
            val running = privacyProtected().filter { pkg ->
                val uid = packageUid(pkg)
                uid != null && uid >= FIRST_APP_UID && "ignore" !in shell("appops", "get", pkg, "RUN_IN_BACKGROUND")
            }
            check(running.isEmpty(), "pacchetti protetti fermi in background" + if (running.isEmpty()) "" else " (liberi: ${running.joinToString()})", "./gradlew privacy")
        }

        logger.lifecycle("[filtro DNS]")
        val dnsInstalled = shell("pm", "path", DNS_PACKAGE).startsWith("package:")
        check(dnsInstalled, "personalDNSfilter installato", "./gradlew dnsFilter")
        if (dnsInstalled) {
            check(Regex("^\\d+: tun\\d+:.*UP", RegexOption.MULTILINE).containsMatchIn(shell("ip", "addr", "show")), "tunnel VPN del filtro attivo", "riavvia la stick, poi ./gradlew dnsFilter")
            DNS_TEST_BLOCKED.forEach { check(resolvesBlocked(it, attempts = 2) == true, "$it bloccato", "./gradlew dnsFilter") }
            DNS_TEST_ALLOWED.forEach { check(resolvesBlocked(it) == false, "$it raggiungibile", "controlla tools/dns/extra-hosts.txt") }
        }

        logger.lifecycle("")
        logger.lifecycle("Memoria: ${memAvailable()}")
        if (problems.isEmpty()) {
            logger.lifecycle("Tutto a posto.")
        } else {
            logger.lifecycle("Da sistemare (${problems.size}):")
            problems.forEach { logger.lifecycle("  • $it") }
        }
    }

    private fun memAvailable(): String =
        capture(listOf("shell", "cat", "/proc/meminfo")).lines()
            .firstOrNull { it.startsWith("MemAvailable") }
            ?.substringAfter(':')?.trim()?.removeSuffix("kB")?.trim()?.toLongOrNull()
            ?.let { "${it / 1024} MB disponibili" } ?: "sconosciuta"

    /** Sets our screensaver, saving Fire OS's one only once so it can be restored. */
    private fun enableScreensaver(component: String) {
        val key = listOf("shell", "settings", "get", "secure", "screensaver_components")
        val current = capture(key)
        val backup = screensaverBackup.get().asFile
        if (current.isNotBlank() && current != "null" && current != component && !backup.exists()) {
            backup.parentFile.mkdirs()
            backup.writeText(current)
            logger.lifecycle("Salvaschermo originale salvato in ${backup.path}: $current")
        }
        run(listOf("shell", "settings", "put", "secure", "screensaver_components", component))
        run(listOf("shell", "settings", "put", "secure", "screensaver_enabled", "1"))
        logger.lifecycle("Salvaschermo ora: ${capture(key)}")
    }

    private fun restorePreviousScreensaver() {
        val backup = screensaverBackup.get().asFile
        if (!backup.exists()) throw GradleException("Nessun salvaschermo originale salvato in ${backup.path}")
        run(listOf("shell", "settings", "put", "secure", "screensaver_components", backup.readText().trim()))
        logger.lifecycle("Salvaschermo di Fire OS ripristinato")
    }

    /** Downloads the APK (once), verifies its SHA-256 and installs it, if the app is missing or at another version. */
    private fun installExternalApk(packageName: String) {
        val wanted = externalVersion.get()
        val installed = capture(listOf("shell", "dumpsys", "package", packageName)).lines()
            .firstOrNull { it.trim().startsWith("versionName=") }?.substringAfter('=')?.trim()
        if (installed == wanted) {
            logger.lifecycle("$packageName $wanted è già installata")
            return
        }
        logger.lifecycle(if (installed == null) "Installo $packageName $wanted" else "Aggiorno $packageName $installed → $wanted")

        val apk = externalApkFile.get().asFile
        val expected = externalApkSha256.get()
        if (!apk.exists() || sha256(apk) != expected) {
            logger.lifecycle("Scarico ${externalApkUrl.get()}")
            apk.parentFile.mkdirs()
            val partial = File(apk.path + ".part")
            URI(externalApkUrl.get()).toURL().openStream().use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            val actual = sha256(partial)
            if (actual != expected) {
                partial.delete()
                throw GradleException("SHA-256 dell'APK non corrisponde (atteso $expected, ottenuto $actual): installazione annullata")
            }
            // Really replaces the old file (renameTo would fail silently on Windows)
            Files.move(partial.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        // Re-check right before installing: only the APK with the expected hash gets installed
        if (sha256(apk) != expected) throw GradleException("SHA-256 di ${apk.name} non corrisponde: installazione annullata")
        logger.lifecycle("adb install -r ${apk.name}")
        val output = capture(listOf("install", "-r", apk.absolutePath), includeErrors = true)
        logger.lifecycle(output.lines().lastOrNull { it.isNotBlank() }.orEmpty())
        // Android refuses an older version over a newer one: only uninstalling (and losing the app's data) allows it
        if ("INSTALL_FAILED_VERSION_DOWNGRADE" in output) {
            logger.lifecycle("$packageName $installed è più nuova di $wanted: per tornare a $wanted (si perdono i dati dell'app)")
            logger.lifecycle("  adb uninstall $packageName   poi rilancia questo task")
        }
    }

    /** Hash computed in chunks: the APK is not loaded entirely into memory. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { DigestInputStream(it, digest).copyTo(OutputStream.nullOutputStream()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun enableNotificationListener(component: String) {
        val key = listOf("shell", "settings", "get", "secure", "enabled_notification_listeners")
        val current = capture(key).split(':').filter { it.isNotBlank() && it != "null" }
        if (component in current) {
            logger.lifecycle("Notification listener già attivo")
            return
        }
        val updated = (current + component).joinToString(":")
        run(listOf("shell", "settings", "put", "secure", "enabled_notification_listeners", updated))
    }

    private fun run(args: List<String>, withSerial: Boolean = true) {
        logger.lifecycle("adb ${args.joinToString(" ")}")
        execOperations.exec {
            commandLine(command(args, withSerial))
            isIgnoreExitValue = true
        }
    }

    /** Output of an adb command; with [includeErrors] also stderr (where `pm` writes its errors). */
    private fun capture(args: List<String>, includeErrors: Boolean = false): String {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(command(args, withSerial = true))
            standardOutput = output
            if (includeErrors) errorOutput = output
            isIgnoreExitValue = true
        }
        return output.toString().trim()
    }

    private fun command(args: List<String>, withSerial: Boolean): List<String> {
        val target = serial.orNull?.takeIf { withSerial }?.let { listOf("-s", it) }.orEmpty()
        return listOf(adb.get().asFile.absolutePath) + target + args
    }

    private companion object {
        const val OPTIONAL_PREFIX = "opz-"
        const val PRIVACY_SETTINGS = "impostazioni"
        const val PRIVACY_PROTECTED = "protetti"
        /** Below this uid packages share a system identity. */
        const val FIRST_APP_UID = 10000

        const val DNS_PACKAGE = "dnsfilter.android"
        /** Lists downloaded on the computer: Amazon trackers and the most common ad and tracking domains (HaGeZi Light). */
        val DNS_LISTS = listOf(
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/native.amazon-onlydomains.txt",
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/light-onlydomains.txt",
        )
        /** Removed from the lists: Prime Video and its push channel must keep working. */
        val DNS_KEEP = listOf(Regex("aiv-delivery\\.net$"), Regex("aiv-cdn\\.net$"), Regex("^device-messaging-na\\.amazon\\.com$"))
        const val DNS_MIN_ENTRIES = 1000
        /** personalDNSfilter's config template, pinned to a commit and checked with SHA-256. */
        const val DNS_CONF_URL =
            "https://raw.githubusercontent.com/IngoZenz/personaldnsfilter/4594551cdf681d1d5ae374bdac7a6b42a2529de3/app/src/main/assets/dnsfilter.conf"
        const val DNS_CONF_SHA256 = "b0f99638581a4aa87148cd4638fd12f1659de2c29909df88c78fae7750447af9"
        val DNS_TEST_BLOCKED = listOf("softwareupdates.amazon.com", "arcus-uswest.amazon.com", "aax-eu.amazon-adsystem.com")
        val DNS_TEST_ALLOWED = listOf("www.youtube.com", "appstore-tv-prod-na.amazon.com", "api.open-meteo.com")
        const val DNS_START_WAIT_MS = 10_000L
        const val DNS_RETRY_MS = 10_000L
        val HOSTS_FILE_IPS = setOf("0.0.0.0", "127.0.0.1")
    }
}

val appId = "it.ntsciccio.ntsfiretv"
val adbExecutable = androidComponents.sdkComponents.adb
val stick = providers.gradleProperty("stick")
val projectRoot: File = rootProject.projectDir

/** Permissions granted via ADB: Home button (M4), storage and USB (M5/M6). */
val permissionCommands = listOf(
    listOf("shell", "appops", "set", appId, "GET_USAGE_STATS", "allow"),
    listOf("shell", "pm", "grant", appId, "android.permission.READ_EXTERNAL_STORAGE"),
    listOf("shell", "pm", "grant", appId, "android.permission.WRITE_EXTERNAL_STORAGE"),
)
val startHomeCommand = listOf("shell", "am", "start", "-n", "$appId/.home.HomeActivity")
val listenerComponent = "$appId/$appId.system.NowPlayingListenerService"
val clockScreensaver = "$appId/$appId.system.ClockDreamService"
// Debloat: list in tools/debloat.txt, record of disabled packages in .ntsfiretv/ (outside git).
// Also runs with installStick/installStickRelease (all groups + mandatory-update blocking);
// -Pdebloat=false skips it.
val debloatListFile: File = File(projectRoot, "tools/debloat.txt")
val debloatRecordFile: File = File(projectRoot, ".ntsfiretv/debloat-disattivati.txt")
val debloatOnInstall: Boolean = providers.gradleProperty("debloat").orNull != "false"
val debloatInstallGroups = listOf("opz-aggiornamenti")

// Privacy settings and protected packages: tools/privacy.txt, original values in .ntsfiretv/ (outside git).
// Also applied by `debloat` (without -Pgruppo) and installStick, put back by restoreBloat.
val privacyListFile: File = File(projectRoot, "tools/privacy.txt")
val privacyBackupFile: File = File(projectRoot, ".ntsfiretv/privacy-originali.txt")

/** Fire OS's original screensaver, saved before replacing it (folder excluded from git). */
val screensaverBackupFile: File = File(projectRoot, ".ntsfiretv/salvaschermo-originale.txt")

// SDK adb and stick (-Pstick) apply to all the tasks below
tasks.withType<AdbTask>().configureEach {
    adb.set(adbExecutable)
    serial.set(stick)
}

tasks.register<AdbTask>("connectStick") {
    description = "Si collega alla Fire TV indicata con -Pstick=IP:5555"
}

tasks.register<AdbTask>("enableServices") {
    description = "Concede i permessi via ADB e abilita il widget In riproduzione"
    commands.set(permissionCommands)
    notificationListener.set(listenerComponent)
    screensaverComponent.set(clockScreensaver)
    screensaverBackup.set(screensaverBackupFile)
}

// TV Bro browser (WebView variant, the lightest): official APK from GitHub, pinned version.
// To update it: change version and URL, then compute the new hash with `shasum -a 256 <apk>`.
val tvBroVersion = "2.1.6"

tasks.register<AdbTask>("installBrowser") {
    description = "Installa o aggiorna il browser TV Bro a $tvBroVersion (APK da GitHub, verificato SHA-256)"
    dependsOn("connectStick")
    externalPackage.set("com.phlox.tvwebbrowser")
    externalVersion.set(tvBroVersion)
    externalApkUrl.set(
        "https://github.com/truefedex/tv-bro/releases/download/v$tvBroVersion/tvbro-$tvBroVersion-generic-geckoExcluded.apk",
    )
    externalApkSha256.set("d8634edfe8d94b4fb9a52005d68a090a7f1e85b7f39c2cf01a25dc9dd60942b2")
    externalApkFile.set(layout.buildDirectory.file("external/tvbro-$tvBroVersion.apk"))
}

/** Full install on the stick: browser, permissions, widget, screensaver, debloat and opening the home. */
fun registerInstallStick(name: String, installTask: String, what: String) {
    tasks.register<AdbTask>(name) {
        description = "$what, browser, permessi, salvaschermo, debloat (-Pdebloat=false per saltarlo), apre NTSFireTV"
        dependsOn("connectStick", installTask, "installBrowser")
        commands.set(permissionCommands + listOf(startHomeCommand))
        notificationListener.set(listenerComponent)
        screensaverComponent.set(clockScreensaver)
        screensaverBackup.set(screensaverBackupFile)
        if (debloatOnInstall) {
            debloatList.set(debloatListFile)
            debloatRecord.set(debloatRecordFile)
            debloatExtraGroups.set(debloatInstallGroups)
            privacyList.set(privacyListFile)
            privacyBackup.set(privacyBackupFile)
        }
    }
    // With -Pstick the connection must happen before the install
    tasks.matching { it.name == installTask }.configureEach { mustRunAfter("connectStick") }
}

registerInstallStick("installStick", "installDebug", "Installa la debug")

// Signed release: exists only with keystore.properties (see docs/DEVELOPMENT.md, "Release firmata")
if (keystorePropertiesFile.exists()) {
    registerInstallStick("installStickRelease", "installRelease", "Installa la release firmata (più leggera)")
} else {
    tasks.register("installStickRelease") {
        group = "fire tv"
        description = "Installa la release firmata: prima crea keystore.properties (docs/DEVELOPMENT.md)"
        doFirst {
            throw GradleException(
                "Manca keystore.properties nella cartella del progetto: crealo seguendo docs/DEVELOPMENT.md, sezione \"Release firmata\".",
            )
        }
    }
}

/** Copies the file given with -P[property] to the [folder] directory on the stick. */
fun registerPush(name: String, property: String, folder: String, what: String) {
    tasks.register<AdbTask>(name) {
        val path = providers.gradleProperty(property)
        description = "Copia $what sulla stick: -P$property=percorso"
        commands.set(
            path.map { value ->
                val file = File(value).let { if (it.isAbsolute) it else File(projectRoot, value) }
                listOf(listOf("shell", "mkdir", "-p", folder), listOf("push", file.absolutePath, "$folder/"))
            },
        )
        doFirst {
            if (!path.isPresent) throw GradleException("Indica il file: ./gradlew $name -P$property=percorso")
        }
    }
}

registerPush("pushWallpaper", "image", "/sdcard/NTSFireTV/wallpapers", "un'immagine tra gli sfondi")
registerPush("pushTheme", "theme", "/sdcard/NTSFireTV/themes", "un tema JSON (poi Impostazioni → Tema)")

tasks.register<AdbTask>("enableScreensaver") {
    description = "Imposta il salvaschermo orologio di NTSFireTV (salva prima quello di Fire OS)"
    screensaverComponent.set(clockScreensaver)
    screensaverBackup.set(screensaverBackupFile)
}

tasks.register<AdbTask>("restoreScreensaver") {
    description = "Rimette il salvaschermo di Fire OS salvato da enableScreensaver/installStick"
    restoreScreensaver.set(true)
    screensaverBackup.set(screensaverBackupFile)
}

tasks.register<AdbTask>("debloat") {
    description = "Disattiva i pacchetti Amazon di tools/debloat.txt e applica tools/privacy.txt (-Pgruppo=nome per un solo gruppo)"
    debloatList.set(debloatListFile)
    debloatGroup.set(providers.gradleProperty("gruppo"))
    debloatRecord.set(debloatRecordFile)
    // A single group (-Pgruppo) touches only its packages
    if (!providers.gradleProperty("gruppo").isPresent) {
        privacyList.set(privacyListFile)
        privacyBackup.set(privacyBackupFile)
    }
}

tasks.register<AdbTask>("restoreBloat") {
    description = "Riattiva tutti i pacchetti disattivati da debloat e rimette le impostazioni originali"
    debloatList.set(debloatListFile)
    debloatRestore.set(true)
    debloatRecord.set(debloatRecordFile)
    privacyList.set(privacyListFile)
    privacyRestore.set(true)
    privacyBackup.set(privacyBackupFile)
}

tasks.register<AdbTask>("privacy") {
    description = "Spegne tracciamento pubblicitario e invio dati d'uso, ferma in background i pacchetti protetti (tools/privacy.txt)"
    privacyList.set(privacyListFile)
    privacyBackup.set(privacyBackupFile)
}

tasks.register<AdbTask>("restorePrivacy") {
    description = "Rimette le impostazioni originali salvate da privacy"
    privacyList.set(privacyListFile)
    privacyRestore.set(true)
    privacyBackup.set(privacyBackupFile)
}

// On-device DNS filter: personalDNSfilter from F-Droid (pinned version and SHA-256), list built here.
// To update it: change version and code, then compute the new hash with `shasum -a 256 <apk>`.
val dnsFilterVersion = "1.50.61.5"
val dnsFilterVersionCode = 1506105

tasks.register<AdbTask>("dnsFilter") {
    description = "Installa il filtro DNS sulla stick (personalDNSfilter): blocca telemetria, aggiornamenti e pubblicità"
    externalPackage.set("dnsfilter.android")
    externalVersion.set(dnsFilterVersion)
    externalApkUrl.set("https://f-droid.org/repo/dnsfilter.android_$dnsFilterVersionCode.apk")
    externalApkSha256.set("73489010f4d28800cc7ea8fa10b2c8b340c3f19ce81ba56760b4dc4054869039")
    externalApkFile.set(layout.buildDirectory.file("external/personaldnsfilter-$dnsFilterVersion.apk"))
    dnsExtraHosts.set(File(projectRoot, "tools/dns/extra-hosts.txt"))
    dnsWorkDir.set(layout.buildDirectory.dir("dns"))
}

tasks.register<AdbTask>("restoreDnsFilter") {
    description = "Toglie il filtro DNS (disinstalla personalDNSfilter)"
    dnsRemove.set(true)
}

tasks.register<AdbTask>("verifyStick") {
    description = "Controlla permessi, servizi, debloat, privacy e filtro DNS sulla stick"
    verifyApp.set(appId)
    verifyListener.set(listenerComponent)
    verifyScreensaver.set(clockScreensaver)
    verifyBrowser.set("com.phlox.tvwebbrowser=$tvBroVersion")
    debloatList.set(debloatListFile)
    debloatRecord.set(debloatRecordFile)
    privacyList.set(privacyListFile)
}

tasks.register<AdbTask>("logs") {
    description = "Mostra i log di NTSFireTV (Ctrl+C per uscire)"
    commands.set(listOf(listOf("logcat", "-s", "NTSFireTV")))
}
