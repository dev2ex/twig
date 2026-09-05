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
    implementation(libs.commons.net) // pure-Java FTP, no .so
    implementation(libs.sshj)        // SFTP (pure Java, includes BouncyCastle)
    implementation("org.bouncycastle:bcprov-jdk18on:1.75") // explicit reference: replaces Android's stripped BC at runtime
    implementation(libs.okhttp)      // WebDAV / S3 / Jellyfin (hand-written REST, no heavy SDK)

    // org.json ships with Android at runtime; the compile and unit-test classpaths provide it themselves (Jellyfin REST responses)
    compileOnly("org.json:json:20240303")

    testImplementation(project(":fs-local"))
    testImplementation(libs.ftpserver.core) // embedded FTP server
    testImplementation(libs.sshd.sftp)      // embedded SFTP server (Apache MINA)
    testImplementation(libs.mockwebserver)  // WebDAV / Jellyfin mock server
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
