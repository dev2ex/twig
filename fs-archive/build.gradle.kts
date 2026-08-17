plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    implementation(project(":core-fs"))
    // zip 用 JDK 自带 java.util.zip;7z/rar 用以下纯 Java 库(无 .so)。
    implementation(libs.commons.compress) // 7z
    implementation(libs.xz)               // 7z 的 LZMA/LZMA2 解码
    implementation(libs.junrar)           // rar(仅 RAR4)

    testImplementation(project(":fs-local"))
    testImplementation("junit:junit:4.13.2")
}
