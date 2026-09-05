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
    // ★ This module is split off purely for **licensing**, not architecture:
    //   junrar is under the UnRAR license (non-free), and F-Droid requires
    //   100% FLOSS. The :app libre flavor does not depend on this module at
    //   all — see CLAUDE.md "RAR and F-Droid". The code itself is just a
    //   subclass of :fs-archive in the same package.
    api(project(":fs-archive"))
    implementation(libs.junrar) // rar(RAR4 + RAR5),UnRAR License

    testImplementation(project(":fs-local"))
    testImplementation("junit:junit:4.13.2")
}
