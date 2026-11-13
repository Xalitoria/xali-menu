import mc.xalitoria.gradle.repo.kotlinx

plugins {
    kotlin("plugin.serialization")
    `maven-publish`
}

// What other plugins compile against: the menu model, the types they can add (actions, conditions, item sources) and the
// variables. The server provides all of it at run time, from the XaliMenu plugin, so nothing here is shaded or published
// with the dependencies.
dependencies {
    compileOnly(kotlinx("serialization-core"))
    compileOnly(libs.folia.api)
}

dependencies {
    testImplementation(kotlinx("serialization-core"))
    testImplementation(libs.folia.api)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    repositories {
        maven("https://maven.xali.host/releases") {
            credentials {
                username = System.getenv("MAVEN_USER") ?: project.findProperty("maven.user") as? String
                password = System.getenv("MAVEN_TOKEN") ?: project.findProperty("maven.token") as? String
            }
        }
    }
}
