import com.xalitoria.menu.editor.SchemaBuilder
import com.xalitoria.menu.editor.SourceDocs
import com.xalitoria.menu.editor.builtIn
import com.xalitoria.menu.editor.schema
import com.xalitoria.menu.editor.DynamicType
import com.xalitoria.menu.editor.FieldType
import com.xalitoria.menu.editor.ListType
import com.xalitoria.menu.editor.MapType
import com.xalitoria.menu.editor.ObjectType
import com.xalitoria.menu.editor.PolyType
import com.xalitoria.menu.editor.SchemaFile
import com.xalitoria.menu.editor.schemaJson
import kotlinx.serialization.json.JsonPrimitive
import mc.xalitoria.menu.paper.format.MenuFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private val schema: SchemaFile = schemaJson.decodeFromString(SchemaFile.serializer(), Files.readString(file))

    private fun fields(type: String) = schema.types.getValue(type).fields.associateBy { it.key }

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
              amount = "%x%"
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
        check(written, schema.root, problems)
        assertEquals(emptyList(), problems)
    }

    /** Walks [value] through the type [typeName], noting each key that the schema does not know. */
    private fun check(value: Any?, typeName: String, problems: MutableList<String>) {
        val map = value as? Map<*, *> ?: return
        val fields = fields(typeName)
        val discriminated = map["type"] as? String
        for (entry in map) {
            val key = entry.key as String
            if (key == "type" && discriminated != null && typeName.contains(':')) continue
            val field = fields[key]
            if (field == null) { problems += "$typeName has no key '$key'"; continue }
            walk(entry.value, field.type, problems)
        }
    }

    private fun walk(value: Any?, type: FieldType, problems: MutableList<String>) {
        when (type) {
            is ObjectType -> check(value, type.type, problems)
            is ListType -> (value as? List<*>)?.forEach { walk(it, type.of, problems) }
            is MapType -> (value as? Map<*, *>)?.values?.forEach { walk(it, type.of, problems) }
            is PolyType -> {
                val name = (value as? Map<*, *>)?.get("type") as? String ?: return
                val variant = schema.poly.getValue(type.base)[name]
                if (variant == null) problems += "no ${type.base} called '$name'" else check(value, variant, problems)
            }
            else -> {}
        }
    }

    @Test
    fun aNumberThatMayBePlaceholderTextIsDynamic() {
        val fields = fields("Item")
        assertEquals(DynamicType("int"), fields.getValue("amount").type)
        assertEquals("1", (fields.getValue("amount").default as JsonPrimitive).content)
        assertEquals(DynamicType("boolean"), fields.getValue("glow").type)
        assertEquals(null, fields["dynamic-amount"], "the amount is one key")
    }

    @Test
    fun theLimitsOfANumberComeFromTheModel() {
        val fields = fields("Item")
        assertEquals(1L to 99L, fields.getValue("amount").min to fields.getValue("amount").max)
        assertEquals(35L, fields.getValue("inventory-slots").max)
        assertEquals(null, fields.getValue("slot").max, "a limit that is not there is left out")
        assertEquals(9L, fields("Menu").getValue("size").min)
        // The numbers of actions and conditions have theirs too.
        assertEquals(0L to 100L, fields("Action:chance").getValue("percent").let { it.min to it.max })
        assertEquals(0L to 255L, fields("Action:potion").getValue("amplifier").let { it.min to it.max })
    }

    @Test
    fun theFileIsWrittenAsTheModelAndReadBackAsIt() {
        // What the editor reads is what the model says: the same text from the same classes, with a `kind` on every type and nothing written for what is null.
        val text = Files.readString(file)
        assertEquals(text.trimEnd(), schemaJson.encodeToString(SchemaFile.serializer(), schema))
        assertTrue("\"kind\": \"string\"" in text && "\"fields\": []" in text && "null" !in text)
    }

    @Test
    fun keysAreKebabCase() {
        assertEquals("max-stack-size", SchemaBuilder.kebab("maxStackSize"))
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
        assertEquals(setOf("Action", "Activator", "Catalog", "Condition"), schema.poly.keys)
        assertTrue("close" in schema.poly.getValue("Action") && "permission" in schema.poly.getValue("Condition"))
        // Every variant a base names is a type of the schema.
        for ([base, variants] in schema.poly) for (type in variants.values) assertTrue(type in schema.types, "$base: $type")
    }

    @Test
    fun theSchemaSaysWhatTheElementsOfEachCatalogHold() {
        assertEquals(schema.poly.getValue("Catalog").keys, schema.catalogs.keys, "every catalog of the schema has its values listed")
        assertEquals(setOf("name", "uuid"), schema.catalogs.getValue("players").keys)
    }
}
