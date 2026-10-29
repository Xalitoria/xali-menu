import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import mc.xalitoria.menu.paper.config.MainConfig
import mc.xalitoria.menu.paper.config.MessageConfig
import mc.xalitoria.menu.paper.util.verbatim
import mc.xalitoria.util.config.YamlFile
import mc.xalitoria.util.message.MINI
import mc.xalitoria.util.message.PLAIN
import mc.xalitoria.util.message.toComponent
import net.kyori.adventure.text.Component

class Messages {
    private fun Component.flatten(): List<Component> = listOf(this) + children().flatMap { it.flatten() }

    @Test
    fun cmiGradientsInTheDefaults() {
        val component = MessageConfig().commands.reload.pass.toComponent()
        assertEquals("XALIMENU > Reloaded!", PLAIN.serialize(component))
        assertTrue(component.flatten().mapNotNull { it.color() }.distinct().size > 3)
    }

    @Test
    fun filesAreWrittenWithTheirDefaults() {
        val dir = Files.createTempDirectory("xalimenu")
        try {
            val config = YamlFile(dir.resolve("config.yml"), MainConfig.serializer(), ::MainConfig)
            config.load()
            assertEquals(MainConfig(), config.data)
            assertTrue("language: \"en-us\"" in Files.readString(dir.resolve("config.yml")))

            val messages = YamlFile(dir.resolve("language", "en-us.yml"), MessageConfig.serializer(), ::MessageConfig)
            messages.load()
            assertEquals(MessageConfig(), messages.data)

            Files.writeString(dir.resolve("config.yml"), "language: de-de\n")
            config.load()
            assertEquals(MainConfig(language = "de-de"), config.data)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun verbatimTextIsNotFormatted() {
        val text = "&cHi <red>there</red> {#a0a>}x{#fff<} &#ff0000y"
        assertEquals("Hi <red>there</red> x y", PLAIN.serialize(MINI.deserialize(text.verbatim())))
    }
}
