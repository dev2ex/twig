// ★ R8 newer than the 8.5.35 that AGP 8.5.2 bundles, pinned here for reproducible
// builds. F-Droid's build of 1.8.5 matched ours in every byte except one string:
// the R8 marker embedded in classes.dex, whose "pg-map-id" is a hash of the
// obfuscation mapping. The mapping evidently carries something that varies between
// machines even though none of it reaches the code — the dex was otherwise
// identical, and the difference is neither CPU-count nor build-path dependent.
// F-Droid's reproducible-build documentation names 8.6.33 / 8.7.20 / 8.8+ as the
// releases where several R8 nondeterminism bugs were fixed, so this pins past them.
// AGP is left at 8.5.2; only the R8 it invokes changes.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools:r8:8.8.34")
    }
}

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.24" apply false
}
