plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.twig.fs.smb"
    compileSdk = 34
    buildToolsVersion = "36.1.0"
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 24

        ndk {
            // Size-first: only 64-bit mainstream ABIs; add armeabi-v7a/x86 if broader coverage is needed
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments("-DANDROID_STL=c++_static")
            }
        }
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-fs"))
}
