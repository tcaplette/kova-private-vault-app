import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ScopedArtifacts
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.type.ArtifactTypeDefinition

plugins {
    // AGP 9 has built-in Kotlin support; the standalone kotlin.android plugin is gone.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.app.vault"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.app.vault"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Wipe app data between tests — see the `testOptions` note below. Only the
        // orchestrator can honour this; it is the half of it that clears preferences,
        // files and WorkManager's database.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // A hung device test dumps every thread's stack to logcat and is killed rather than
        // holding the run — see `HangWatch`.
        testInstrumentationRunnerArguments["listener"] = "com.app.vault.HangWatch"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        // `en-XA` renders every resource string accented and ~30% longer: plain English
        // on a screen is a literal that escaped extraction, and a clip is one German will
        // hit too. Its filter entry is added for debug only, in `androidComponents` below.
        debug {
            isPseudoLocalesEnabled = true
        }
    }

    /**
     * The nine languages, and only these. Material 3 and biometric ship ~80 languages of
     * their own, so without the filter an Italian phone would put an Italian button in a
     * dialog of an otherwise English app. `generateLocaleConfig` (with
     * `res/resources.properties` naming the base set's locale) builds the list system
     * Settings › Apps › Language offers on Android 13+.
     */
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "ja", "ko", "pt-rBR", "es", "de", "fr", "zh-rCN", "zh-rTW")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    /**
     * Two ways of getting the one app — `distribution-flavors`.
     *
     * `github` is what anyone builds from the public source: free and complete, with no
     * trial, no purchase and no Play Billing in it. `play` is the build published to
     * Google Play, with the 30-day trial and the one-time purchase `billing-entitlement`
     * describes. Both are one product, so they share an `applicationId`; the builder's
     * key and Play's key differ, so Android will not install one over the other.
     *
     * `BuildConfig.TRIAL` is the one switch shared source reads. Billing itself is split
     * by source set (`src/play` and `src/github` each hold a `Billing.kt`), so the
     * library never reaches the `github` APK. `github` is declared first so it is the
     * variant Android Studio opens on.
     */
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "TRIAL", "false")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "TRIAL", "true")
        }
    }

    /**
     * zxcvbn ships its feedback strings as a `ResourceBundle` in seven languages, and
     * `passphrase-strength-meter` replaces the English one with the app's own sentences
     * through `Feedback.replaceResourceBundle`, built from the app's string resources so
     * it speaks the app's language. The other six are not merely unused — they are a
     * hazard. `ResourceBundle` resolves by the JVM default locale, so a key that fell
     * through to the library's own bundle would answer in the library's register, or in
     * a language the app does not offer, instead of the app's own sentence.
     *
     * Excluding them removes that path rather than relying on every key being covered,
     * and takes ~19 KB with it. The dictionaries and keyboard files are deliberately
     * **not** excluded: they are what the estimate is made of.
     */
    packaging {
        resources {
            excludes += "com/nulabinc/zxcvbn/messages_*.properties"
        }
    }

    /**
     * ***One process per test — Android Test Orchestrator.***
     *
     * Every device failure this suite produced over seven full runs was state carried
     * from one test into another inside a single instrumentation process, and each looked
     * like a different bug: a stale index-backfill thread writing a dead vault's index
     * over a live one (`index is truncated`), a `MirrorWorker` still running inside a
     * later test's unlock, and an emulated camera that stops delivering frames after
     * about forty preceding tests. Each passed in isolation, every time, and each was
     * fixed one at a time while the next run produced a different one.
     *
     * The orchestrator ends the category rather than the symptoms: it runs each test in
     * its own process, so a leaked thread, a queued worker and a degraded camera client
     * all die with the test that made them.
     *
     * **The cost, named:** a process launch per test. Across ~858 instrumented tests the
     * run roughly doubles, from about 23 minutes to about 45.
     *
     * ***`clearPackageData` is on, and process isolation alone was not enough.***
     * Orchestrator gives each test a fresh process; it does not give it fresh storage.
     * What survived was on-device state, and it cost a 61-failure run: a suite that moves
     * the launcher identity left the app resolving to a **cover front end**, so nine
     * unrelated suites launched `MainActivity`, got a music player instead of a
     * passphrase field, and timed out waiting for "the lock screen". Preferences, files
     * and WorkManager's database all outlive a process; `pm clear` between tests is what
     * ends the category.
     *
     * **The objection to it did not survive checking.** It looked as though this would
     * revoke the runtime grants the camera and recorder suites need. It does not: every
     * suite that needs one already grants it itself in `@Before` through
     * `uiAutomation.grantRuntimePermission` — the idiom this project uses in place of
     * `androidx.test:rules`, because a dependency there is an I1 clearance run to save
     * four lines. `pm clear` runs *between* tests, so each `@Before` re-grants into a
     * clean install.
     *
     * `dependency-clearance`: `androidTestUtil` is test scope, so this enters no
     * application variant, adds no permission to the merged manifest, and triggers no I1
     * clearance run — the same argument `androidTestImplementation` already carries below.
     */
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    // `XWingDeviceTest` reads the same committed X-Wing vectors `XWingTest` does, from
    // the one copy — two copies of a vector file is two places for them to disagree.
    sourceSets["androidTest"].assets.directories.add("src/test/resources/xwing")
}

kotlin {
    jvmToolchain(21)
}


// ---------------------------------------------------------------------------
// BouncyCastle, filtered — `share-format-v2`, `crypto-primitives`.
//
// bcprov-jdk18on 1.79 is ~8.5 MB of every algorithm BouncyCastle has, and release
// builds are not minified, so all of it would ship — including an Argon2 and a
// ChaCha20-Poly1305 that are not libsodium's. This transform rewrites the jar on
// resolution to the classes XWing.kt reaches and nothing else, so neither ever
// reaches the runtime classpath.
//
// ***The allowlist is for 1.79 exactly.*** It was measured, not guessed: the three
// packages below are the ones X-Wing calls, and BC_KEEP_SUPPORT is every other
// BouncyCastle class the JVM loaded (`-verbose:class`) while running keygen,
// encapsulation, decapsulation, SHAKE256 and SHA3-256. Some of it is unexpected and
// real — `CryptoServicesRegistrar`'s static initialiser touches the DH/DSA parameter
// classes and two X9 types, and X25519's base-point multiply borrows Ed25519's
// precomputation. A bump re-measures this list; a class missing from it fails
// `XWingDeviceTest` with `NoClassDefFoundError`, which is what that test is for.
// ---------------------------------------------------------------------------

val BC_KEEP_PACKAGES = listOf(
    "org/bouncycastle/pqc/crypto/mlkem/",
    "org/bouncycastle/crypto/digests/",
    "org/bouncycastle/math/ec/rfc7748/",
)

/** Support classes, by outer class name — their nested classes come with them. */
val BC_KEEP_SUPPORT = listOf(
    // crypto
    "org/bouncycastle/crypto/CipherParameters",
    "org/bouncycastle/crypto/CryptoServiceProperties",
    "org/bouncycastle/crypto/CryptoServicePurpose",
    "org/bouncycastle/crypto/CryptoServicesConstraints",
    "org/bouncycastle/crypto/CryptoServicesPermission",
    "org/bouncycastle/crypto/CryptoServicesRegistrar",
    "org/bouncycastle/crypto/Digest",
    "org/bouncycastle/crypto/EncapsulatedSecretExtractor",
    "org/bouncycastle/crypto/EncapsulatedSecretGenerator",
    "org/bouncycastle/crypto/ExtendedDigest",
    "org/bouncycastle/crypto/SecretWithEncapsulation",
    "org/bouncycastle/crypto/SecureRandomProvider",
    "org/bouncycastle/crypto/Xof",
    // crypto/params
    "org/bouncycastle/crypto/params/AsymmetricKeyParameter",
    "org/bouncycastle/crypto/params/DHParameters",
    "org/bouncycastle/crypto/params/DHValidationParameters",
    "org/bouncycastle/crypto/params/DSAParameters",
    "org/bouncycastle/crypto/params/DSAValidationParameters",
    // util
    "org/bouncycastle/util/Arrays",
    "org/bouncycastle/util/Encodable",
    "org/bouncycastle/util/Integers",
    "org/bouncycastle/util/Pack",
    "org/bouncycastle/util/encoders/DecoderException",
    "org/bouncycastle/util/encoders/Encoder",
    "org/bouncycastle/util/encoders/EncoderException",
    "org/bouncycastle/util/encoders/Hex",
    "org/bouncycastle/util/encoders/HexEncoder",
    // Outside the three named support packages, and loaded all the same.
    "org/bouncycastle/math/raw/Bits",
    "org/bouncycastle/math/raw/Interleave",
    "org/bouncycastle/math/raw/Mod",
    "org/bouncycastle/math/raw/Nat",
    "org/bouncycastle/math/ec/rfc8032/Codec",
    "org/bouncycastle/math/ec/rfc8032/Ed25519",
    "org/bouncycastle/math/ec/rfc8032/Scalar25519",
    "org/bouncycastle/pqc/crypto/KEMParameters",
    "org/bouncycastle/pqc/crypto/util/SecretWithEncapsulationImpl",
    "org/bouncycastle/asn1/ASN1Encodable",
    "org/bouncycastle/asn1/ASN1Object",
    "org/bouncycastle/asn1/x9/X9ECParameters",
    "org/bouncycastle/asn1/x9/X9ObjectIdentifiers",
)

abstract class BouncyCastleFilter : TransformAction<BouncyCastleFilter.Parameters> {

    interface Parameters : TransformParameters {
        @get:Input
        val packages: ListProperty<String>

        @get:Input
        val support: ListProperty<String>
    }

    @get:InputArtifact
    abstract val input: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val source = input.get().asFile
        val packages = parameters.packages.get()
        val support = parameters.support.get().toSet()
        val out = outputs.file(source.nameWithoutExtension + "-xwing.jar")
        var kept = 0
        ZipFile(source).use { zip ->
            ZipOutputStream(out.outputStream()).use { jar ->
                // Classes only: no META-INF (the jar's signature would no longer
                // match), no multi-release copies, no resources.
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".class") && it.name.startsWith("org/") }
                    .filter { e ->
                        packages.any { e.name.startsWith(it) } ||
                            e.name.removeSuffix(".class").substringBefore('$') in support
                    }
                    .forEach { e ->
                        jar.putNextEntry(ZipEntry(e.name))
                        zip.getInputStream(e).use { it.copyTo(jar) }
                        jar.closeEntry()
                        kept++
                    }
            }
        }
        check(kept > 0) { "the BouncyCastle filter kept nothing from ${source.name}" }
    }
}

val bcFiltered: Attribute<Boolean> = Attribute.of("vault.bouncycastle.filtered", Boolean::class.javaObjectType)

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    // Crypto, chosen in P0-4 and cleared against I1 in P0-7 (zero permission delta).
    // lazysodium pulls the JNA *jar*; Android needs the *aar* (it carries the .so).
    // Both on the classpath is a duplicate-class failure, so exclude the jar.
    implementation(libs.lazysodium.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(variantOf(libs.jna) { artifactType("aar") })

    // Resumable import (P1-7). Cleared against I1 in P0-7.
    implementation(libs.work.runtime.ktx)

    // Playback of encrypted media (P1-8). P0-5 proved the custom DataSource path.
    implementation(libs.media3.exoplayer)

    // Biometric unlock (P1-9). Cleared against I1 in P0-7 at this version. It is
    // the only route to a CryptoObject-bearing prompt; the platform BiometricPrompt
    // would work on minSdk 30 but the AndroidX one is what P0-7 measured.
    implementation(libs.biometric)

    // Subscription purchase (P1-16). The one dependency Phase 1 adds that P0-7 did
    // not clear — P0-2 measured a collision instead: this library declares INTERNET,
    // which I1 forbids outright. It is here at shipping scope with the declaration
    // stripped in the manifest, because the purchase flow is bindService IPC to
    // com.android.vending and needs no socket of ours. That is measured, not
    // assumed: P1-16 task 1 records connection, product query and a completed
    // purchase on the stripped build, and :app:checkPlayDebugPermissions is the gate
    // that stays untouched either way.
    //
    // `play` only. The GitHub build has no trial and no purchase, so it carries none
    // of this library: no classes, no notice, no BILLING permission. Its `Billing` is
    // the stub in `src/github`.
    "playImplementation"(libs.billing.ktx)

    // QR generation and decoding for pairing (P4-4). `zxing:core` is a plain Java
    // JAR — no AndroidManifest, so it cannot contribute a permission, and no
    // socket, no service, no callback home: I2-clean by construction rather than
    // by promise. It is here at shipping scope because the pairing screen both
    // draws a code and reads one, and dependency-clearance says clearance is only
    // clearance when it is measured at the scope the thing ships in. P4-4 task 1
    // is that measurement: baseline 10 permissions, 10 after, zero delta.
    implementation(libs.zxing.core)

    // Passphrase strength estimation (`passphrase-strength-meter`). The Java port of
    // zxcvbn, on zxing's terms and for the same reasons: a plain Java JAR with no
    // AndroidManifest, so it cannot contribute a permission, and nothing in it opens
    // a socket — its dictionaries are `.txt` resources read off the classpath, which
    // is the whole of its I/O. I2-clean by construction rather than by promise.
    //
    // Shipping scope, because the meter is on seven screens the user reaches, and
    // dependency-clearance says clearance is only clearance when it is measured at
    // the scope the thing ships in. This change's task 1.3 is that measurement:
    // baseline 10 permissions, 10 after, zero delta.
    implementation(libs.zxcvbn)

    // X-Wing (`share-format-v2`) — see BC_KEEP_* above. The attribute is what routes
    // this one jar through the filter; every other jar is marked unfiltered and is
    // left alone. Ship scope, because contact shares and the pairing identity both
    // ship; the clearance run is recorded beside the version in libs.versions.toml.
    attributesSchema { attribute(bcFiltered) }
    artifactTypes.maybeCreate(ArtifactTypeDefinition.JAR_TYPE).attributes.attribute(bcFiltered, false)
    registerTransform(BouncyCastleFilter::class) {
        from.attribute(bcFiltered, false)
            .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
        to.attribute(bcFiltered, true)
            .attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
        parameters {
            packages.set(BC_KEEP_PACKAGES)
            support.set(BC_KEEP_SUPPORT)
        }
    }
    implementation(libs.bouncycastle.bcprov) {
        attributes { attribute(bcFiltered, true) }
    }

    // Version constraint, not a new dependency: biometric 1.1.0 brings fragment
    // 1.2.x, and registerForActivityResult on a FragmentActivity needs 1.3.0+
    // (lint InvalidFragmentVersionForActivityResult, and it is a real hazard —
    // the photo-picker callbacks are registered on this Activity). No permission
    // delta: :app:checkPlayDebugPermissions is the proof, not the assumption.
    constraints {
        implementation(libs.androidx.fragment) {
            because("registerForActivityResult on FragmentActivity requires fragment 1.3.0+")
        }
    }

    // Design token verification (DESIGN.md section 10). JVM scope: the tokens are
    // constants, so no emulator is involved and no APK gains a byte.
    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // The orchestrator APK itself — see the `testOptions` note above.
    androidTestUtil(libs.androidx.test.orchestrator)

    // P2-6's instrumented Verify. Window insets, the IME, and predictive back are
    // properties of a real window on a real device — they cannot be asserted from
    // the JVM, and `FLAG_SECURE` rules out checking them by screenshot.
    //
    // **androidTest scope, and that is load-bearing.** `dependency-clearance`
    // states clearance is measured at ship scope and that a test-scope dependency
    // is explicitly *not* cleared, because it never enters the application
    // variant's merged manifest. This artifact therefore adds nothing to the
    // release APK and triggers no I1 clearance run; `:app:check<Variant>Permissions`
    // reads the application variant and is unaffected. Nothing shipping gained a
    // dependency in P2-6.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// ---------------------------------------------------------------------------
// P4-5's two-device rig, task 6.1.
//
// Renders a pairing payload to a PNG using the app's OWN generator, so the poster
// the emulator's virtual scene displays and the code the pairing screen draws
// cannot disagree about the bytes. Unit-test classpath, because `javax.imageio`
// is a JVM API and nothing here ships.
//
//   ./gradlew :app:renderPairingPoster -Ppayload=<payload hex> -Pout=/tmp/code.png
// ---------------------------------------------------------------------------

tasks.register<JavaExec>("renderPairingPoster") {
    group = "verification"
    description = "Renders a pairing payload to a PNG for the emulator's virtual scene."
    dependsOn("compilePlayDebugUnitTestKotlin")
    classpath = files(
        layout.buildDirectory.dir("intermediates/built_in_kotlinc/playDebugUnitTest/compilePlayDebugUnitTestKotlin/classes"),
        layout.buildDirectory.dir("intermediates/built_in_kotlinc/playDebug/compilePlayDebugKotlin/classes"),
        configurations.getByName("playDebugUnitTestRuntimeClasspath"),
    )
    mainClass.set("com.app.vault.PairingPosterKt")
    doFirst {
        args = listOf(
            project.findProperty("payload")?.toString()
                ?: error("-Ppayload=<payload hex> is required"),
            project.findProperty("out")?.toString()
                ?: error("-Pout=<file.png> is required"),
        )
    }
}

// ---------------------------------------------------------------------------
// Invariant I1 enforcement.
//
// Reads the MERGED manifest, not the source manifest, so that a permission
// contributed by a library dependency is caught. The merged manifest is taken
// from AGP's artifact API rather than a path under build/intermediates: a
// path-based check silently passes when it finds nothing, which is the worst
// possible failure mode for a security gate.
// ---------------------------------------------------------------------------

abstract class CheckPermissions : DefaultTask() {

    companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val manifest = mergedManifest.get().asFile
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)

        val nodes = doc.getElementsByTagName("uses-permission")
        val declared = (0 until nodes.length).mapNotNull { i ->
            (nodes.item(i) as org.w3c.dom.Element)
                .getAttributeNS(ANDROID_NS, "name")
                .takeIf { it.isNotEmpty() }
        }

        // READ_MEDIA_AUDIO is permitted by exact name and nothing else is.
        // `music-recorder-covers` declares it for the music player cover, which
        // reads the device's audio library through MediaStore. Permitting it by
        // name rather than by loosening the prefix is the whole point: images,
        // video and user-selected visual media stay forbidden, and a future edit
        // that reaches for one of them still fails the build.
        //
        // INTERNET is invariant I1 and does not move. The READ_MEDIA_* half of
        // this gate was never in I1 as OUTLINE.md states it.
        val forbidden = declared.filter {
            it == "android.permission.INTERNET" ||
                (
                    it.startsWith("android.permission.READ_MEDIA_") &&
                        it != "android.permission.READ_MEDIA_AUDIO"
                    )
        }

        if (forbidden.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Forbidden permission(s) found in the merged manifest:")
                    forbidden.forEach { appendLine("    $it") }
                    appendLine()
                    appendLine(
                        "Invariant I1: Vault never ships INTERNET. READ_MEDIA_AUDIO is permitted " +
                            "for the music player cover; every other READ_MEDIA_* is not."
                    )
                    appendLine("If a dependency introduced this, the dependency does not ship.")
                    appendLine()
                    appendLine("Merged manifest: $manifest")
                    appendLine("All declared permissions: ${declared.joinToString().ifEmpty { "(none)" }}")
                }
            )
        }

        logger.lifecycle(
            "checkPermissions: OK - merged manifest declares " +
                if (declared.isEmpty()) "no permissions." else "${declared.size}: $declared"
        )
    }
}

// ---------------------------------------------------------------------------
// The other half of a stripped permission.
//
// check<Variant>Permissions proves a forbidden permission never reaches the merged
// manifest. This proves the opposite direction: that no app code depends on a
// permission the manifest strips.
//
// The two are not symmetric, and this one exists because of how WorkManager
// behaves without ACCESS_NETWORK_STATE. It does not throw. It catches the
// SecurityException and returns NetworkState(isConnected = false) — permanently.
// Work carrying a network constraint therefore never becomes eligible: no
// crash, no error log, an import that sits in ENQUEUED forever. A runtime that
// fails loudly needs no gate; one that fails silently does.
//
// Scanned against the PROJECT-scope CLASSES artifact rather than a path under
// build/, for the reason P0-1 gave: a path-based check silently passes when it
// finds nothing. The zero-classes guard below covers what the artifact API
// cannot.
// ---------------------------------------------------------------------------

abstract class CheckWorkConstraints : DefaultTask() {

    companion object {
        /**
         * Symbol -> why this app cannot use it. A map rather than a constant
         * because RECEIVE_BOOT_COMPLETED (stripped in P1-7) carries the same
         * class of trap — a PeriodicWorkRequest expecting to survive a reboot
         * would quietly not reschedule. It has no single symbol to match, so it
         * gets no entry, but this is where one would go.
         */
        val FORBIDDEN = mapOf(
            "setRequiredNetworkType" to
                "ACCESS_NETWORK_STATE is stripped, so WorkManager reports isConnected=false " +
                "permanently and this constraint can never be satisfied.",
            "setRequiredNetworkRequest" to
                "ACCESS_NETWORK_STATE is stripped, so registerDefaultNetworkCallback() fails " +
                "and the callback never fires.",
        )
    }

    @get:InputFiles
    abstract val allJars: ListProperty<RegularFile>

    @get:InputFiles
    abstract val allDirectories: ListProperty<Directory>

    /**
     * A class file's constant pool holds referenced method names as plain UTF-8,
     * so a byte scan finds a call site without needing a bytecode parser. This
     * reads the app's own classes only — WorkManager *defines* these methods, so
     * scanning the merged output would match on every build.
     */
    private fun scan(name: String, bytes: ByteArray): List<Pair<String, String>> {
        val pool = String(bytes, Charsets.ISO_8859_1)
        return FORBIDDEN.keys.filter { pool.contains(it) }.map { name to it }
    }

    @TaskAction
    fun check() {
        var scanned = 0
        val hits = mutableListOf<Pair<String, String>>()

        allDirectories.get().forEach { dir ->
            dir.asFile.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
                scanned++
                hits += scan(f.relativeTo(dir.asFile).path, f.readBytes())
            }
        }

        allJars.get().forEach { jar ->
            ZipFile(jar.asFile).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                    scanned++
                    hits += scan("${jar.asFile.name}!${entry.name}", zip.getInputStream(entry).readBytes())
                }
            }
        }

        // Scanning nothing and reporting success is the failure mode this gate
        // exists to avoid elsewhere. It does not get to have it itself.
        if (scanned == 0) {
            throw GradleException(
                "checkWorkConstraints scanned 0 class files. The gate is inert, which is a " +
                    "build configuration failure, not a pass."
            )
        }

        if (hits.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Work constraint(s) this app can never satisfy:")
                    hits.forEach { (where, symbol) ->
                        appendLine("    $where")
                        appendLine("        $symbol - ${FORBIDDEN[symbol]}")
                    }
                    appendLine()
                    appendLine("Work enqueued with such a constraint stays ENQUEUED forever, silently.")
                    appendLine("Remove the constraint. Restoring the permission is an I1 decision.")
                }
            )
        }

        logger.lifecycle(
            "checkWorkConstraints: OK - $scanned app classes, no unsatisfiable work constraints."
        )
    }
}

// ---------------------------------------------------------------------------
// `crypto-primitives`: libsodium is the only Argon2id and the only AEAD.
//
// BouncyCastle ships for X-Wing, filtered (BC_KEEP_* above). This proves the filter
// held in the artifact that actually ships: it reads the class definitions out of
// every dex in the assembled APK and fails on any BouncyCastle Argon2, ChaCha or
// Poly1305 class. Class *definitions*, not strings — a kept digest that merely names
// a type is not that type shipping.
//
// It also fails when it finds no BouncyCastle class at all: X-Wing needs some, so
// zero means the scan read the wrong thing, and a gate that passes on nothing is the
// failure mode P0-1 named.
// ---------------------------------------------------------------------------

abstract class CheckBouncyCastle : DefaultTask() {

    @get:InputFiles
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val loader: Property<com.android.build.api.variant.BuiltArtifactsLoader>

    private val forbidden = Regex("^Lorg/bouncycastle/.*(Argon2|ChaCha|Poly1305)")

    /** Every class descriptor a dex defines — the header's `class_defs` through `type_ids` to `string_ids`. */
    private fun definedClasses(dex: ByteArray): List<String> {
        val b = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        val stringIdsOff = b.getInt(0x3C)
        val typeIdsOff = b.getInt(0x44)
        val classDefsSize = b.getInt(0x60)
        val classDefsOff = b.getInt(0x64)
        fun string(index: Int): String {
            var at = b.getInt(stringIdsOff + 4 * index)
            while (dex[at].toInt() and 0x80 != 0) at++ // skip the ULEB128 length
            at++
            var end = at
            while (dex[end].toInt() != 0) end++
            return String(dex, at, end - at, Charsets.UTF_8)
        }
        return (0 until classDefsSize).map { i ->
            val typeIndex = b.getInt(classDefsOff + 32 * i)
            string(b.getInt(typeIdsOff + 4 * typeIndex))
        }
    }

    @TaskAction
    fun check() {
        val apks = loader.get().load(apkDirectory.get())?.elements?.map { File(it.outputFile) }
            ?: throw GradleException("checkBouncyCastle found no APK to read.")
        var bouncyCastle = 0
        val hits = mutableListOf<String>()
        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.forEach { e ->
                    definedClasses(zip.getInputStream(e).readBytes()).forEach { type ->
                        if (type.startsWith("Lorg/bouncycastle/")) bouncyCastle++
                        if (forbidden.containsMatchIn(type)) hits += "${apk.name}!${e.name}: $type"
                    }
                }
            }
        }
        if (bouncyCastle == 0) {
            throw GradleException(
                "checkBouncyCastle found no org.bouncycastle class in the APK. X-Wing needs " +
                    "some, so the gate read the wrong thing — a configuration failure, not a pass."
            )
        }
        if (hits.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("BouncyCastle Argon2 / ChaCha20 / Poly1305 classes reached the APK:")
                    hits.forEach { appendLine("    $it") }
                    appendLine()
                    appendLine("crypto-primitives: libsodium is the only Argon2id and the only AEAD.")
                    appendLine("Narrow BC_KEEP_* in app/build.gradle.kts.")
                }
            )
        }
        logger.lifecycle(
            "checkBouncyCastle: OK - $bouncyCastle org.bouncycastle classes, none Argon2, ChaCha or Poly1305."
        )
    }
}

// ---------------------------------------------------------------------------
// Third-party notices — `third-party-notices`.
//
// Writes `res/raw/aboutlibraries.json` for each variant from that variant's resolved
// runtime classpath, so the notices the app shows cannot drift from what ships. The
// file is in AboutLibraries' format, and `aboutlibraries/` at the root is laid out as
// that plugin's config directory: AboutLibraries 15.3.0 was the plan, and against AGP
// 9.3.1 it resolved the classpath at configuration time and wrote an empty list for
// both variants. This task is the fallback the design named, and the files stay
// readable by the plugin if a later version works.
//
// Licences come from each library's POM (and its parents'), mapped to the ids in
// `GenerateNotices.NOTICE_LICENCES`. A POM licence that maps to none of them fails the build, naming
// the library: that is what keeps the notices compliant after a new dependency lands,
// not just on the day this was written. Licence texts are the files in
// `aboutlibraries/licenses/`, so no network is read for them. A file in
// `aboutlibraries/libraries/` whose `uniqueId` matches a resolved library overrides
// its fields (JNA's licence choice, lazysodium's source URL); one that matches
// nothing is added as a component with no POM (libsodium, the two fonts).
// ---------------------------------------------------------------------------

abstract class GenerateNotices : DefaultTask() {

    companion object {
        /**
         * The accepted licences, and how a POM names each. Matched on the POM's licence URL
         * or name, because POMs spell the same licence a dozen ways.
         */
        val NOTICE_LICENCES: Map<String, (String, String) -> Boolean> = mapOf(
            "Apache-2.0" to { name, url ->
                url.contains("apache.org/licenses/LICENSE-2.0") ||
                    (name.contains("Apache", ignoreCase = true) && name.contains("2.0"))
            },
            "MIT" to { name, url ->
                url.contains("opensource.org/licenses/MIT") || name.equals("MIT", ignoreCase = true) ||
                    name.equals("MIT License", ignoreCase = true) || name.equals("The MIT License", ignoreCase = true)
            },
            "BouncyCastle" to { name, url ->
                url.contains("bouncycastle.org/licence") || name.contains("Bouncy Castle Licence", ignoreCase = true)
            },
            "MPL-2.0" to { name, url ->
                url.contains("mozilla.org/MPL/2.0") ||
                    (name.contains("Mozilla Public License", ignoreCase = true) && name.contains("2.0"))
            },
            "ISC" to { name, url -> url.contains("opensource.org/licenses/ISC") || name.equals("ISC", ignoreCase = true) },
            "OFL-1.1" to { name, url -> url.contains("OFL") || name.contains("Open Font License", ignoreCase = true) },
            "asdkl" to { name, url ->
                url.contains("developer.android.com/studio/terms") ||
                    name.contains("Android Software Development Kit License", ignoreCase = true)
            },
        )
    }

    /** "group:artifact:version" to the library's POM text, then its parents' in order. */
    @get:Input
    abstract val poms: MapProperty<String, List<String>>

    @get:InputDirectory
    abstract val configDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    private fun parse(xml: String): org.w3c.dom.Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream()).documentElement

    /** The text of `<project><name>`-style direct children, ignoring nested ones. */
    private fun org.w3c.dom.Element.child(tag: String): String? =
        (0 until childNodes.length).map { childNodes.item(it) }
            .firstOrNull { it is org.w3c.dom.Element && it.tagName == tag }
            ?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun org.w3c.dom.Element.childElement(tag: String): org.w3c.dom.Element? =
        (0 until childNodes.length).map { childNodes.item(it) }
            .firstOrNull { it is org.w3c.dom.Element && it.tagName == tag } as org.w3c.dom.Element?

    @Suppress("UNCHECKED_CAST")
    private fun readJson(file: File): Map<String, Any?> =
        groovy.json.JsonSlurper().parse(file) as Map<String, Any?>

    @TaskAction
    fun generate() {
        val config = configDirectory.get().asFile
        val licenceTexts = File(config, "licenses").listFiles { f -> f.extension == "json" }
            .orEmpty().associate { it.nameWithoutExtension to readJson(it) }
        val overrides = File(config, "libraries").listFiles { f -> f.extension == "json" }
            .orEmpty().map { readJson(it) }.associateBy { it["uniqueId"] as String }

        val libraries = sortedMapOf<String, MutableMap<String, Any?>>()
        val refused = mutableListOf<String>()
        poms.get().forEach { (coordinate, chain) ->
            val (group, artifact, version) = coordinate.split(":")
            val documents = chain.map(::parse)
            val pom = documents.first()
            val licences = documents.firstNotNullOfOrNull { doc ->
                doc.childElement("licenses")?.getElementsByTagName("license")
                    ?.let { list -> (0 until list.length).map { list.item(it) as org.w3c.dom.Element } }
                    ?.takeIf { it.isNotEmpty() }
            }.orEmpty().map { (it.child("name") ?: "") to (it.child("url") ?: "") }
            val name = pom.child("name")?.takeUnless { it.contains("\${") } ?: artifact
            val website = documents.firstNotNullOfOrNull { it.child("url") }
            val scm = documents.firstNotNullOfOrNull { it.childElement("scm")?.child("url") }
            val ids = licences.map { (n, u) ->
                NOTICE_LICENCES.entries.firstOrNull { it.value(n, u) }?.key ?: "?$n ($u)"
            }
            libraries["$group:$artifact"] = mutableMapOf(
                "uniqueId" to "$group:$artifact",
                "artifactId" to coordinate,
                "artifactVersion" to version,
                "name" to name,
                "description" to (pom.child("description") ?: ""),
                "website" to website,
                "developers" to emptyList<Any>(),
                "licenses" to ids,
                "scm" to scm?.let { mapOf("url" to it) },
            )
        }

        // `-android` and `-jvm` artifacts are one library with its root, at the root's
        // version: the root keeps the row.
        libraries.keys.toList().forEach { id ->
            val root = id.removeSuffix("-android").removeSuffix("-jvm")
            if (root != id && root in libraries) libraries.remove(id)
        }

        overrides.forEach { (id, fields) ->
            libraries.getOrPut(id) { mutableMapOf("developers" to emptyList<Any>(), "description" to "") }
                .putAll(fields.filterValues { it != null })
        }

        // A licence file may name a holder-specific copy of an accepted licence
        // ("MIT-zxcvbn4j", spdxId MIT): MIT, ISC, OFL and Bouncy Castle are only given
        // when the holder's copyright line comes with them, so the bare SPDX template,
        // with its <year> and <owner> still in it, is refused as a notice.
        fun accepted(key: String) =
            key in NOTICE_LICENCES || licenceTexts[key]?.get("spdxId") in NOTICE_LICENCES
        val placeholder = Regex("<(year|copyright holders|owner|dates|Copyright Holder)>")
        libraries.values.forEach { library ->
            @Suppress("UNCHECKED_CAST")
            val ids = library["licenses"] as List<String>
            val id = library["uniqueId"]
            if (ids.isEmpty()) refused += "$id: no licence declared"
            ids.filterNot(::accepted).forEach { refused += "$id: licence ${it.removePrefix("?")}" }
            ids.filter(::accepted).forEach { key ->
                val content = licenceTexts[key]?.get("content") as String?
                when {
                    content.isNullOrBlank() ->
                        refused += "$id: $key has no text in aboutlibraries/licenses/"
                    placeholder.containsMatchIn(content) ->
                        refused += "$id: $key is the bare template; give it the holder's copyright " +
                            "line in its own aboutlibraries/licenses/ file"
                }
            }
        }
        if (refused.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Third-party notices: a shipped library is under a licence nobody has accepted:")
                    refused.forEach { appendLine("    $it") }
                    appendLine()
                    appendLine("Accepted: ${NOTICE_LICENCES.keys.joinToString()}.")
                    appendLine("Remove the library, or choose one of its licences in aboutlibraries/libraries/.")
                }
            )
        }

        val used = libraries.values.flatMap {
            @Suppress("UNCHECKED_CAST")
            (it["licenses"] as List<String>)
        }.toSortedSet()
        val json = mapOf(
            "libraries" to libraries.values.toList(),
            "licenses" to used.associateWith { licenceTexts.getValue(it) },
        )
        val out = outputDirectory.get().asFile.resolve("raw/aboutlibraries.json")
        out.parentFile.mkdirs()
        out.writeText(groovy.json.JsonOutput.toJson(json))
        logger.lifecycle("generateNotices: OK - ${libraries.size} components, licences ${used.joinToString()}.")
    }
}

/**
 * Every external module on [configuration], minus platforms (BOMs ship no code), mapped
 * to its POM and its parents' POMs. Read through an artifact-resolution query, so Gradle
 * fetches each POM once and serves it from its cache after that.
 */
fun resolveNoticePoms(configuration: String): Map<String, List<String>> {
    val modules = configurations.getByName(configuration).incoming.resolutionResult.allComponents
        .filter { c -> c.id is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
        .filterNot { c ->
            c.variants.all { v ->
                v.attributes.keySet().firstOrNull { it.name == "org.gradle.category" }
                    ?.let { v.attributes.getAttribute(it).toString() } in setOf("platform", "enforced-platform")
            }
        }
        .map { it.id as org.gradle.api.artifacts.component.ModuleComponentIdentifier }

    val texts = mutableMapOf<String, String>()
    fun fetch(coordinates: Collection<Triple<String, String, String>>) {
        val query = dependencies.createArtifactResolutionQuery()
        coordinates.forEach { (g, a, v) -> query.forModule(g, a, v) }
        query.withArtifacts(
            org.gradle.maven.MavenModule::class.java,
            org.gradle.maven.MavenPomArtifact::class.java,
        ).execute().resolvedComponents.forEach { component ->
            val id = component.id as org.gradle.api.artifacts.component.ModuleComponentIdentifier
            component.getArtifacts(org.gradle.maven.MavenPomArtifact::class.java)
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedArtifactResult>()
                .firstOrNull()?.let { texts["${id.group}:${id.module}:${id.version}"] = it.file.readText() }
        }
    }
    fun parentOf(xml: String): Triple<String, String, String>? {
        val parent = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream()).documentElement.getElementsByTagName("parent")
            .let { if (it.length == 0) null else it.item(0) as org.w3c.dom.Element } ?: return null
        fun text(tag: String) = parent.getElementsByTagName(tag).item(0)?.textContent?.trim()
        return Triple(text("groupId") ?: return null, text("artifactId") ?: return null, text("version") ?: return null)
    }

    fetch(modules.map { Triple(it.group, it.module, it.version) })
    var pending = texts.values.mapNotNull(::parentOf).filter { "${it.first}:${it.second}:${it.third}" !in texts }
    while (pending.isNotEmpty()) {
        fetch(pending.toSet())
        pending = pending.mapNotNull { texts["${it.first}:${it.second}:${it.third}"] }
            .mapNotNull(::parentOf).filter { "${it.first}:${it.second}:${it.third}" !in texts }
    }

    return modules.associate { m ->
        val key = "${m.group}:${m.module}:${m.version}"
        val chain = mutableListOf<String>()
        var at: String? = key
        while (at != null) {
            val xml = texts[at]
                ?: throw GradleException("generateNotices: no POM resolved for $at, so its licence is unknown.")
            chain += xml
            at = parentOf(xml)?.let { "${it.first}:${it.second}:${it.third}" }
        }
        key to chain
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val notices = tasks.register<GenerateNotices>("generate${name}Notices") {
            group = "build"
            description =
                "Writes the ${variant.name} variant's third-party notices, and fails on a licence nobody accepted."
            configDirectory.set(rootProject.layout.projectDirectory.dir("aboutlibraries"))
            poms.set(provider { resolveNoticePoms("${variant.name}RuntimeClasspath") })
        }
        variant.sources.res?.addGeneratedSourceDirectory(notices, GenerateNotices::outputDirectory)
    }

    // One set of gates per debug variant — `githubDebug` and `playDebug` — each named
    // from its variant and hooked to that variant's own assemble. Two flavors under the
    // old fixed names would collide at configuration.
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.androidResources.localeFilters.add("en-rXA")
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val assemble = "assemble$name"

        val bcCheck = tasks.register<CheckBouncyCastle>("check${name}BouncyCastle") {
            group = "verification"
            description =
                "Fails the build if the ${variant.name} APK's dex defines a BouncyCastle Argon2, ChaCha or Poly1305 class."
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            loader.set(variant.artifacts.getBuiltArtifactsLoader())
        }
        tasks.matching { it.name == "check" || it.name == assemble }.configureEach { dependsOn(bcCheck) }

        val workCheck = tasks.register<CheckWorkConstraints>("check${name}WorkConstraints") {
            group = "verification"
            description =
                "Fails the build if app code sets a work constraint that a stripped permission " +
                    "makes permanently unsatisfiable."
        }
        variant.artifacts
            .forScope(ScopedArtifacts.Scope.PROJECT)
            .use(workCheck)
            .toGet(
                ScopedArtifact.CLASSES,
                CheckWorkConstraints::allJars,
                CheckWorkConstraints::allDirectories,
            )
        tasks.matching { it.name == assemble }.configureEach { dependsOn(workCheck) }

        val check = tasks.register<CheckPermissions>("check${name}Permissions") {
            group = "verification"
            description =
                "Fails the build if the merged manifest declares INTERNET or READ_MEDIA_* (invariant I1)."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        // assemble<Variant> is not registered yet at onVariants time, so match lazily
        // rather than resolving it here.
        tasks.matching { it.name == assemble }.configureEach { dependsOn(check) }
    }
}
