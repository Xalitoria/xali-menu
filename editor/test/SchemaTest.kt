import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mc.xalitoria.menu.editor.SchemaBuilder
import mc.xalitoria.menu.editor.SourceDocs
import mc.xalitoria.menu.editor.builtIn
import mc.xalitoria.menu.editor.schema
import mc.xalitoria.menu.paper.format.MenuFormat

class SchemaTest {
    private val menuDirectory: Path = Path.of(System.getProperty("user.dir")).parent
    private val file: Path = menuDirectory.resolve("editor/web/schema.json")

    @Test
    fun theFileInWebIsUpToDate() {
        val fresh = schema(menuDirectory, "test")
        // The version is the only thing the build puts in that the sources do not.
        val current = Files.readString(file).replace(Regex("\"version\": \"[^\"]*\""), "\"version\": \"test\"")
        assertEquals(fresh, current, "web/schema.json is out of date: run ./gradlew :menu:editor:schema")
    }

    private val schema: JsonObject = Json.parseToJsonElement(Files.readString(file)).jsonObject

    @Test
    fun everyKeyOfAWrittenMenuIsInTheSchema() {
        val format = MenuFormat(builtIn().module)
        val menu = format.decode(
            """
            title = "T"
            commands = [x]
            args = [who]
            refresh = 5s
            open-with = [{ type = chat, text = hi }]
            items.a {
              material = stone
              slot = 0
              dynamic-amount = "%x%"
              hide = [enchantments]
              view.require = [{ condition { type = permission, node = a.b } }]
              click.left.actions = [{ type = message, text = hi }, { type = sound, sound = a.b }]
              food { nutrition = 3, always-edible = true }
              tool { rules = [ { blocks = [stone], speed = 2 } ] }
            }
            animation.frames = [ { delay = 2s, items.f { material = stone, slot = 1 } } ]
            """.trimIndent(),
        )
        val written = com.typesafe.config.ConfigFactory.parseString(format.encode(menu)).root().unwrapped()
        val problems = mutableListOf<String>()
        check(written, "Menu", problems)
        assertEquals(emptyList(), problems)
    }

    /** Walks [value] through the type [typeName], noting each key that the schema does not know. */
    private fun check(value: Any?, typeName: String, problems: MutableList<String>) {
        val map = value as? Map<*, *> ?: return
        val type = schema["types"]!!.jsonObject[typeName]!!.jsonObject
        val fields = type["fields"]!!.jsonArray.associateBy { it.jsonObject["key"]!!.jsonPrimitive.content }
        val discriminated = map["type"] as? String
        for (entry in map) {
            val key = entry.key as String
            if (key == "type" && discriminated != null && typeName.contains(':')) continue
            val field = fields[key]?.jsonObject
            if (field == null) { problems += "$typeName has no key '$key'"; continue }
            walk(entry.value, field["type"]!!.jsonObject, problems)
        }
    }

    private fun walk(value: Any?, type: JsonObject, problems: MutableList<String>) {
        when (type["kind"]!!.jsonPrimitive.content) {
            "object" -> check(value, type["type"]!!.jsonPrimitive.content, problems)
            "list" -> (value as? List<*>)?.forEach { walk(it, type["of"]!!.jsonObject, problems) }
            "map" -> (value as? Map<*, *>)?.values?.forEach { walk(it, type["of"]!!.jsonObject, problems) }
            "poly" -> {
                val name = (value as? Map<*, *>)?.get("type") as? String ?: return
                val variant = schema["poly"]!!.jsonObject[type["base"]!!.jsonPrimitive.content]!!.jsonObject[name]?.jsonPrimitive?.content
                if (variant == null) problems += "no ${type["base"]} called '$name'" else check(value, variant, problems)
            }
        }
    }

    @Test
    fun keysAreKebabCase() {
        assertEquals("dynamic-amount", SchemaBuilder.kebab("dynamicAmount"))
        assertEquals("on-open", SchemaBuilder.kebab("onOpen"))
        assertEquals("nbt", SchemaBuilder.kebab("nbt"))
    }

    @Test
    fun commentsAndDefaultsAreRead() {
        val docs = SourceDocs.scanFile(
            """
            package a.b

            /** A thing. See [Other]. */
            @Serializable @SerialName("thing")
            data class Thing(
                /** How many. */
                val count: Int = 3,
                val names: List<String> = [],
                val label: String = "x, y",
            ) : Base, Other<String> {
                val ignored: Int = 1
            }

            sealed interface Outer {
                /** Inner doc. */
                @Serializable
                data class Inner(val v: Boolean = true) : Outer
            }
            """.trimIndent(),
        ).toMap()
        val thing = docs.getValue("thing")
        assertEquals("A thing. See `Other`.", thing.doc)
        assertEquals("How many.", thing.fields.getValue("count").doc)
        assertEquals("3", thing.fields.getValue("count").default)
        assertEquals("\"x, y\"", thing.fields.getValue("label").default)
        assertEquals(listOf("Base", "Other"), thing.supertypes)
        assertEquals("Inner doc.", docs.getValue("a.b.Outer.Inner").doc)
    }

    @Test
    fun theSchemaHasTheBasesAndTheirVariants() {
        val poly = schema["poly"]!!.jsonObject
        assertEquals(setOf("Action", "Activator", "Catalog", "Condition"), poly.keys)
        assertTrue("close" in poly["Action"]!!.jsonObject && "permission" in poly["Condition"]!!.jsonObject)
        assertTrue(JsonArray::class.isInstance(schema["types"]!!.jsonObject["Item"]!!.jsonObject["fields"]))
    }

    @Test
    fun theSchemaSaysWhatTheElementsOfEachCatalogHold() {
        val catalogs = schema["catalogs"]!!.jsonObject
        val poly = schema["poly"]!!.jsonObject["Catalog"]!!.jsonObject
        assertEquals(poly.keys, catalogs.keys, "every catalog of the schema has its values listed")
        assertEquals(setOf("name", "uuid"), catalogs["players"]!!.jsonObject.keys)
    }
}
