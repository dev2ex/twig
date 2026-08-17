plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.twig.app"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.twig.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 270
        versionName = "1.1.1"
        vectorDrawables.useSupportLibrary = true
        // 与 fs-smb/fs-zstd 一致;避免 ffmpeg 解码器把 v7a/x86 的 so 也打进来
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        // libtwigpty:特权进程里分配 PTY 用(见 cpp/twigpty.c 顶部注释)
        externalNativeBuild { cmake { arguments("-DANDROID_STL=none") } }
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // R8 代码混淆/裁剪
            isShrinkResources = true        // 资源裁剪
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug") // 用 debug 签名发布
        }
    }

    // 体积优化:AAB 按 ABI / 密度 / 语言拆分,用户只下载自己设备需要的部分
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
        aidl = true // 特权 helper 的 ITwigPrivService
    }

    testOptions {
        // Robolectric 要能读到 res/(布局、strings)才能跑起来
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // jellyfin ffmpeg 解码器要求
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
            // bcprov 等签名 jar 的签名文件与多版本 stub
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

    // SSH 终端:Termux 终端模拟器 + 渲染视图(GPLv3,自用)
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0")

    // 媒体播放:ExoPlayer(自带解封装,MKV/MP4 全兼容)+ ffmpeg 音频软解(AC3/EAC3/DTS/TrueHD 等)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.jellyfin.media3.ffmpeg)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Shizuku:以 shell(uid 2000)身份跑进程,免 root 读 Android 11+ 的
    // /Android/data 与 /Android/obb —— 那两个目录连 MANAGE_EXTERNAL_STORAGE 都进不去。
    // ★ 这块是少数不手写的地方:核心是一个 AIDL 接口 + 特定协议的 ContentProvider,
    // 抄过来跨版本很脆,而两个包加起来只有几十 KB。
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // XAPK 组装、树 key 编解码、排序规则等纯 JVM 单测
    testImplementation("junit:junit:4.13.2")

    // PaneViewModel 这类挂在 Android 上的类的单测(见 PaneViewModelRestoreTest):
    // - robolectric 在普通 JVM 测试里提供**真实**的 Android 框架实现(android.jar
    //   里全是抛 Stub! 的空壳,构造 AndroidViewModel、读 SharedPreferences 都不行);
    // - coroutines-test 换掉 Dispatchers.Main 并提供虚拟时间,好确定性地把
    //   viewModelScope 里排队的协程推完再断言。
    // 都只是 testImplementation,不进 APK。
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
