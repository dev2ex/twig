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
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1") // scrypt(与 sshj 同源,去重)

    // org.json:Android 运行期自带;编译与单测各自提供
    compileOnly("org.json:json:20240303")
    testImplementation(project(":fs-local"))
    testImplementation("org.json:json:20240303")
    testImplementation("io.airlift:aircompressor:0.25") // 测试用纯 Java zstd(JVM 上可用)
    testImplementation("junit:junit:4.13.2")
}
