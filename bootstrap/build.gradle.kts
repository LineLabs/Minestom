import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(rootProject)
    implementation("dev.nipafx.args:record-args:0.9.2")

    runtimeOnly(libs.bundles.logback)
}

tasks {
    application {
        mainClass.set("net.minestom.bootstrap.Main")
    }

    withType<ShadowJar> {
        archiveFileName.set("minestom-bootstrap.jar")
    }
}