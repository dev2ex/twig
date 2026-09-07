import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing: the key **never goes into the repo**; it's read from
// keystore.properties (path / passwords in CLAUDE.local.md).
// If the file is missing -> release produces an **unsigned APK**, not a silent
// fallback to the debug key.
// ★ The debug key is the AOSP public one; anyone can sign an APK that
//   installs over the official one, so a silent fallback would bury that
//   trap again with no warning.
//   An unsigned APK is exactly what F-Droid wants (it signs its own, or does
//   a reproducible-build comparison).
// ★ The `import java.util.Properties` at the top cannot be omitted: in the
//   Kotlin DSL `java` resolves to Gradle's java extension, so writing
//   `java.util.Properties` inline fails with "Unresolved reference: util".
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

// When a dev branch wants the test build to **install straight over** the
// previous one (instead of uninstall + reinstall each time), sign it with the
// debug key: set `debugSign=true` in the gitignored local.properties, or pass
// `-PdebugSign=true` on the command line.
// ★ This flag has a history: previously the dev branch achieved the same
//   thing by **deleting the entire signing block above**, and git only saw
//   "dev removed these lines". When dev was merged into master the real
//   signing setup on master got **silently deleted** too — no conflict, no
//   warning. Moving the choice into a gitignored file means this file is
//   identical on both branches and merges have nothing to clobber.
// ★ findProperty reads gradle.properties and `-P` args, **not** local.properties
//   (that's AGP's own file), so the latter has to be read explicitly.
val debugSign = (
    project.findProperty("debugSign") as String?
        ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
            Properties().apply { f.inputStream().use { load(it) } }.getProperty("debugSign")
        }
    )?.toBoolean() == true

android {
    namespace = "com.twig.app"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.twig.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 287
        versionName = "1.8.2"
        vectorDrawables.useSupportLibrary = true
        // Same as fs-smb / fs-zstd: prevent the ffmpeg decoder from dragging in v7a/x86 .so too
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        // libtwigpty: PTY allocation inside the privileged process (see cpp/twigpty.c top comment)
        externalNativeBuild { cmake { arguments("-DANDROID_STL=none") } }
    }

    // Distribution channels: libre drops RAR (junrar is under the UnRAR
    // license, non-free, F-Droid rejects it); full is the self-built / self-
    // distributed complete build. **They differ in RAR alone** — don't sneak
    // other differences into these two flavors.
    // See CLAUDE.md "RAR and F-Droid".
    flavorDimensions += "distribution"
    productFlavors {
        register("full") { dimension = "distribution"; isDefault = true }
        register("libre") { dimension = "distribution" }
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // R8 code obfuscation / shrinking
            isShrinkResources = true        // resource shrinking
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (debugSign) signingConfigs.getByName("debug")
            else keystoreProps?.let { signingConfigs.getByName("release") }
        }
    }

    // Size optimisation: AAB split by ABI / density / language — users download only what their device needs
    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = true }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true // ITwigPrivService for the privileged helper
    }

    testOptions {
        // Robolectric needs to read res/ (layouts, strings) to run
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // required by the jellyfin ffmpeg decoder
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
            // signature files of bcprov etc. and multi-version stubs
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/BC*",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST",
        )
    }
}

dependencies {
    implementation(project(":core-fs"))
    implementation(project(":fs-local"))
    implementation(project(":fs-archive"))
    "fullImplementation"(project(":fs-archive-rar")) // full only; see src/libre/.../RarSupport.kt for libre
    implementation(project(":fs-network"))
    implementation(project(":fs-smb"))
    implementation(project(":fs-restic"))
    implementation(project(":fs-zstd"))
    implementation(project(":git-lite"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // SSH terminal: Termux terminal emulator + render view (GPLv3, in-tree)
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0")

    // Media playback: ExoPlayer (own demuxer, full MKV/MP4) + ffmpeg audio software decode (AC3/EAC3/DTS/TrueHD, etc.)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.jellyfin.media3.ffmpeg)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Shizuku: run a process under shell (uid 2000), so we can read Android
    // 11+ /Android/data and /Android/obb without root — those two directories
    // are unreachable even with MANAGE_EXTERNAL_STORAGE.
    // ★ One of the few places we don't hand-write: the core is an AIDL
    //   interface plus a custom ContentProvider protocol, and copying it
    //   verbatim is fragile across versions, while the two packages total
    //   only a few dozen KB.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // scrypt for backup / password encryption (see secure/Secrets.kt).
    // ★ APK delta is 0: bcprov is already bundled via :fs-restic / :fs-network;
    //   this just exposes it to :app at compile time.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // XAPK packing, tree-key codec, sort rules etc. — pure JVM unit tests
    testImplementation("junit:junit:4.13.2")

    // Unit tests for Android-bound classes like PaneViewModel (see PaneViewModelRestoreTest):
    // - robolectric provides a **real** Android framework implementation inside
    //   plain JVM tests (android.jar is full of Stub!-throwing shells — you
    //   can't construct an AndroidViewModel or read SharedPreferences against it);
    // - coroutines-test swaps Dispatchers.Main and provides virtual time, so
    //   we can deterministically drain the coroutines queued on viewModelScope
    //   before asserting.
    // Both are testImplementation only — never shipped in the APK.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
