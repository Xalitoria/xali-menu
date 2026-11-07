import mc.xalitoria.gradle.sibling

plugins {
    kotlin("plugin.serialization")
}

// The web editor for menus, served as static files from GitHub Pages (menu.xali.to). Its form is built from schema.json,
// which this module writes from the classes of the plugin, so the editor and the model cannot drift apart: `check` fails
// when the file in web/ is older than the code.
dependencies {
    implementation(sibling("paper"))
    implementation(libs.folia.api)
    implementation(libs.kotlinx.serialization.hocon)
}

// The plugin's jar is on the classpath (its `jar` task is off; shadowJar writes that file), so build it before it is read.
val pluginJar = "${sibling("paper").path}:shadowJar"

tasks.test {
    useJUnitPlatform()
}
tasks.configureEach {
    if (name in setOf("compileKotlin", "compileTestKotlin", "test")) dependsOn(pluginJar)
}

val menuDirectory = projectDir.parentFile

// Writes web/schema.json. Run it after a change to the menu model, a built-in action or condition, or a comment of the model.
val schema by tasks.registering(JavaExec::class) {
    group = "editor"
    description = "Writes web/schema.json from the classes and comments of the plugin."
    classpath = sourceSets.main.get().runtimeClasspath
    dependsOn(pluginJar)
    mainClass = "com.xalitoria.menu.editor.DumpSchemaKt"
    args(file("web/schema.json"), menuDirectory, project.version)
    inputs.files(fileTree(menuDirectory.resolve("api/kotlin")), fileTree(menuDirectory.resolve("paper/kotlin")))
    outputs.file("web/schema.json")
}
