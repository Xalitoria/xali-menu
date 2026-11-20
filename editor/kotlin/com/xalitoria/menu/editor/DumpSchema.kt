package com.xalitoria.menu.editor

import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.catalog.Catalogs
import mc.xalitoria.menu.paper.condition.Conditions
import java.nio.file.Files
import java.nio.file.Path

/** The registry with the built-in types, as the plugin registers them. */
fun builtIn(): TypeRegistry = TypeRegistry().also {
    Actions.register(it)
    Conditions.register(it)
    Catalogs.register(it)
}

/** The schema of the built-in menu format, read from the sources in [menu] (the directory of the menu project). */
fun schema(menu: Path, version: String): String {
    val docs = SourceDocs.scan(menu.resolve("api/kotlin"), menu.resolve("paper/kotlin"))
    val registry = builtIn()
    return schemaJson.encodeToString(SchemaFile.serializer(), SchemaBuilder(registry.module, docs, version, registry.catalogInfo).build()) + "\n"
}

/** Writes the schema to the file given as the first argument, from the project directory given as the second. */
fun main(args: Array<String>) {
    val target = Path.of(args[0])
    Files.createDirectories(target.parent)
    Files.writeString(target, schema(Path.of(args[1]), args[2]))
    println("Wrote $target")
}
