plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.twig.fs.nfs"
    compileSdk = 34
    buildToolsVersion = "36.1.0"
    ndkVersion = "26.1.10909125"
    defaultConfig {
        minSdk = 24
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core-fs"))
    testImplementation("junit:junit:4.13.2")
}

// Host-built JNI library for opt-in integration tests against an isolated export.
tasks.withType<Test>().configureEach {
    System.getenv("TWIG_NFS_NATIVE_DIR")?.let { systemProperty("java.library.path", it) }
    if (!System.getenv("TWIG_NFS_TEST_HOST").isNullOrBlank()) {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("NFS live tests depend on an external fixture") { true }
    }
}
