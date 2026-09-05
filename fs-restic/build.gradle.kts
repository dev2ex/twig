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
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1") // scrypt (same source as sshj, deduplicate)

    // org.json: Android bundles it at runtime; the compile and unit-test classpaths provide it themselves
    compileOnly("org.json:json:20240303")
    testImplementation(project(":fs-local"))
    testImplementation("org.json:json:20240303")
    testImplementation("io.airlift:aircompressor:0.25") // pure-Java zstd for tests (usable on the JVM)
    testImplementation("junit:junit:4.13.2")
}
