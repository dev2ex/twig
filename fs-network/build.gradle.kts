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
    implementation(libs.commons.net) // 纯 Java FTP,无 .so
    implementation(libs.sshj)        // SFTP(纯 Java,内含 BouncyCastle)
    implementation("org.bouncycastle:bcprov-jdk18on:1.75") // 显式引用:运行时顶掉 Android 阉割版 BC
    implementation(libs.okhttp)      // WebDAV(手写 PROPFIND,不引入重 SDK)

    testImplementation(project(":fs-local"))
    testImplementation(libs.ftpserver.core) // 内嵌 FTP 服务器
    testImplementation(libs.sshd.sftp)      // 内嵌 SFTP 服务器(Apache MINA)
    testImplementation(libs.mockwebserver)  // WebDAV 模拟服务器
    testImplementation("junit:junit:4.13.2")
}
