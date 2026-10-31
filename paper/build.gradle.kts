import mc.xalitoria.gradle.sibling
import mc.xalitoria.gradle.Load
import mc.xalitoria.gradle.RelativeLoad
import mc.xalitoria.gradle.repo.mavenNexo

plugins {
    kotlin("plugin.serialization")
    id("mc.xalitoria.minecraft")
    alias(libs.plugins.shadow)
}

repositories {
    mavenNexo()
}

dependencies {
    implementation(sibling("api"))
    // Libraries the server downloads when the plugin loads (see loader.json); nothing declared here is shaded.
    // util brings kotlinx.serialization, the legacy color codes and MiniMessage, and adventure-nbt.
    api(libs.xalicraft.util)
    api(libs.kotlinx.serialization.hocon)
    api(libs.bundles.database)
    api(libs.bundles.exposed)

    // Folia's API is Paper's plus the regionised scheduler, so it covers both platforms.
    compileOnly(libs.folia.api)
    compileOnly(libs.placeholderapi)
    compileOnly(libs.vaultunlocked)
    compileOnly(libs.itemsadder)
    compileOnly(libs.nexo)
    compileOnly(libs.skinsrestorer)
    compileOnly(libs.headdatabase)

    testImplementation(libs.xalicraft.util)
    testImplementation(libs.kotlinx.serialization.hocon)
    testImplementation(libs.bundles.database)
    testImplementation(libs.bundles.exposed)
    testImplementation(libs.folia.api)
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    jar { enabled = false }
    shadowJar {
        // Other plugins use the API's classes from this jar, whether or not the plugin itself does.
        minimize {
            exclude(project(sibling("api").path))
        }
    }
    build {
        dependsOn(shadowJar)
    }
}
