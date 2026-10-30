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
        versionCode = 1
        versionName = "1.0"
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

    /** External app to install if missing: package, APK URL, expected SHA-256 and cache file. */
    @get:Internal
    abstract val externalPackage: Property<String>

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

    init {
        group = "fire tv"
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun runCommands() {
        serial.orNull?.let { run(listOf("connect", it), withSerial = false) }
        externalPackage.orNull?.let(::installExternalApk)
        notificationListener.orNull?.let(::enableNotificationListener)
        screensaverComponent.orNull?.let(::enableScreensaver)
        if (restoreScreensaver.getOrElse(false)) restorePreviousScreensaver()
        if (debloatList.isPresent) {
            if (debloatRestore.getOrElse(false)) restoreDebloat() else applyDebloat()
        }
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

    /** Downloads the APK (once), verifies its SHA-256 and installs it, only if the app isn't already there. */
    private fun installExternalApk(packageName: String) {
        val installed = capture(listOf("shell", "pm", "list", "packages", packageName))
            .lines().any { it.trim() == "package:$packageName" }
        if (installed) {
            logger.lifecycle("$packageName è già installata")
            return
        }

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
        run(listOf("install", "-r", apk.absolutePath))
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
    description = "Installa il browser TV Bro $tvBroVersion se manca (APK da GitHub, verificato SHA-256)"
    dependsOn("connectStick")
    externalPackage.set("com.phlox.tvwebbrowser")
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
    description = "Disattiva i pacchetti Amazon di tools/debloat.txt (-Pgruppo=nome per un gruppo alla volta)"
    debloatList.set(debloatListFile)
    debloatGroup.set(providers.gradleProperty("gruppo"))
    debloatRecord.set(debloatRecordFile)
}

tasks.register<AdbTask>("restoreBloat") {
    description = "Riattiva tutti i pacchetti disattivati da debloat"
    debloatList.set(debloatListFile)
    debloatRestore.set(true)
    debloatRecord.set(debloatRecordFile)
}

tasks.register<AdbTask>("logs") {
    description = "Mostra i log di NTSFireTV (Ctrl+C per uscire)"
    commands.set(listOf(listOf("logcat", "-s", "NTSFireTV")))
}
