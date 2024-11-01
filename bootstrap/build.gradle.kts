import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(rootProject)

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