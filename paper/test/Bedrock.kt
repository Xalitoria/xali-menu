import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.action.Social
import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.bedrock.BedrockForms
import mc.xalitoria.menu.paper.bedrock.BedrockIcons
import mc.xalitoria.menu.paper.bedrock.BedrockPlanner
import mc.xalitoria.menu.paper.bedrock.ClickChoices
import mc.xalitoria.menu.paper.bedrock.ClientProfile
import mc.xalitoria.menu.paper.bedrock.FormIcon
import mc.xalitoria.menu.paper.bedrock.Presentation
import mc.xalitoria.menu.paper.catalog.Catalogs
import mc.xalitoria.menu.paper.condition.Conditions
import mc.xalitoria.menu.paper.condition.PlayerConditions
import mc.xalitoria.menu.paper.config.MainConfig
import mc.xalitoria.menu.paper.format.MenuFormat
import mc.xalitoria.menu.paper.runtime.Clients
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.Host
import mc.xalitoria.menu.paper.util.stripPrivateUse
import net.xalitoria.menu.Action
import net.xalitoria.menu.model.BedrockButton
import net.xalitoria.menu.model.BedrockChoice
import net.xalitoria.menu.model.BedrockField
import net.xalitoria.menu.model.BedrockItem
import net.xalitoria.menu.model.BedrockMenu
import net.xalitoria.menu.model.Clicks
import net.xalitoria.menu.model.ClientEdition
import net.xalitoria.menu.model.DeviceKind
import net.xalitoria.menu.model.DeviceOs
import net.xalitoria.menu.model.Handler
import net.xalitoria.menu.model.InputMode
import net.xalitoria.menu.model.Item
import net.xalitoria.menu.model.Menu
import net.xalitoria.menu.model.UiProfile
import org.bukkit.entity.Player
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Bedrock {
    private val format = MenuFormat(TypeRegistry().apply {
        Actions.register(this)
        Conditions.register(this)
        Catalogs.register(this)
    }.module)

    private val javaPc = ClientProfile.JAVA
    private val bedrockPc = ClientProfile(ClientEdition.BEDROCK, DeviceOs.WINDOWS, InputMode.KEYBOARD_MOUSE, UiProfile.CLASSIC)
    private val bedrockMobile = ClientProfile(ClientEdition.BEDROCK, DeviceOs.ANDROID, InputMode.TOUCH, UiProfile.POCKET)
    private val bedrockConsole = ClientProfile(ClientEdition.BEDROCK, DeviceOs.XBOX, InputMode.CONTROLLER, UiProfile.CLASSIC)

    private val host = object : Host {
        override fun run(actions: List<Action>, ctx: Context) = Unit
        override fun runLater(player: Player, ticks: Long, task: () -> Unit) = Unit
        override fun console(command: String) = Unit
        override fun open(player: Player, menu: String, args: List<String>) = Unit
        override fun close(player: Player) = Unit
        override fun refresh(player: Player) = Unit
        override fun page(player: Player, relative: Boolean, value: Int) = Unit
    }

    private fun fakePlayer(): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, _, _ -> null } as Player

    @Test
    fun clientProfileMatchingAndDeviceKinds() {
        assertEquals(DeviceKind.PC, javaPc.device)
        assertEquals(DeviceKind.PC, bedrockPc.device)
        assertEquals(DeviceKind.MOBILE, bedrockMobile.device)
        assertEquals(DeviceKind.CONSOLE, bedrockConsole.device)
        assertEquals(DeviceKind.VR, DeviceOs.HOLOLENS.deviceKind())

        assertFalse(javaPc.prefersForm)
        assertFalse(bedrockPc.prefersForm)
        assertTrue(bedrockMobile.prefersForm)
        assertTrue(bedrockConsole.prefersForm)

        assertTrue(bedrockMobile.matches(edition = ClientEdition.BEDROCK, devices = [DeviceKind.MOBILE], inputs = [InputMode.TOUCH], ui = [UiProfile.POCKET]))
        assertFalse(bedrockPc.matches(devices = [DeviceKind.MOBILE]))
        assertTrue(bedrockConsole.matches(os = [DeviceOs.XBOX, DeviceOs.PLAYSTATION], inputs = [InputMode.CONTROLLER]))
    }

    @Test
    fun clientConditionAndDialogsCheckProfile() {
        val player = fakePlayer()
        val ctx = Context(player, "shop", emptyMap(), host)
        val previousProfile = Clients.profileOf
        val previousVersion = Clients.versionOf
        try {
            Clients.versionOf = { Clients.DIALOGS }
            Clients.profileOf = { bedrockMobile }

            assertFalse(Clients.dialogs(player), "Bedrock clients do not support Java dialogs")
            assertTrue(PlayerConditions.Client(edition = ClientEdition.BEDROCK).test(ctx))
            assertTrue(PlayerConditions.Client(devices = [DeviceKind.MOBILE], ui = [UiProfile.POCKET]).test(ctx))
            assertFalse(PlayerConditions.Client(edition = ClientEdition.JAVA).test(ctx))
            assertFalse(PlayerConditions.Client(inputs = [InputMode.KEYBOARD_MOUSE]).test(ctx))

            Clients.profileOf = { javaPc }
            assertTrue(Clients.dialogs(player))
            assertTrue(PlayerConditions.Client(edition = ClientEdition.JAVA, devices = [DeviceKind.PC]).test(ctx))
            assertFalse(PlayerConditions.Client(edition = ClientEdition.BEDROCK).test(ctx))
        } finally {
            Clients.profileOf = previousProfile
            Clients.versionOf = previousVersion
        }
        assertFailsWith<IllegalArgumentException> { PlayerConditions.Client() }
    }

    @Test
    fun plannerRespectsDefaultsAndDeviceFilters() {
        val plainMenu = Menu(title = "Shop")

        // Server default = inventory
        val invDefaults = MainConfig.Bedrock(defaultMode = MainConfig.DefaultBedrockMode.INVENTORY)
        assertEquals(Presentation.Inventory, BedrockPlanner.plan(plainMenu, javaPc, invDefaults))
        assertEquals(Presentation.Inventory, BedrockPlanner.plan(plainMenu, bedrockMobile, invDefaults))

        // Server default = touch-form: mobile & console get SimpleForm, Bedrock PC keeps chest inventory
        val touchDefaults = MainConfig.Bedrock(defaultMode = MainConfig.DefaultBedrockMode.TOUCH_FORM)
        assertEquals(Presentation.Inventory, BedrockPlanner.plan(plainMenu, javaPc, touchDefaults))
        assertEquals(Presentation.Inventory, BedrockPlanner.plan(plainMenu, bedrockPc, touchDefaults))
        assertEquals(Presentation.SimpleForm, BedrockPlanner.plan(plainMenu, bedrockMobile, touchDefaults))
        assertEquals(Presentation.SimpleForm, BedrockPlanner.plan(plainMenu, bedrockConsole, touchDefaults))

        // Server default = always-form: all Bedrock get SimpleForm, Java stays inventory
        val alwaysDefaults = MainConfig.Bedrock(defaultMode = MainConfig.DefaultBedrockMode.ALWAYS_FORM)
        assertEquals(Presentation.Inventory, BedrockPlanner.plan(plainMenu, javaPc, alwaysDefaults))
        assertEquals(Presentation.SimpleForm, BedrockPlanner.plan(plainMenu, bedrockPc, alwaysDefaults))
        assertEquals(Presentation.SimpleForm, BedrockPlanner.plan(plainMenu, bedrockMobile, alwaysDefaults))

        // Menu scoped to touch/controller only: Bedrock PC falls back to inventory even when server default is always-form
        val touchOnly = Menu(title = "Shop", bedrock = BedrockMenu(mode = BedrockMenu.Mode.SIMPLE, inputs = [InputMode.TOUCH, InputMode.CONTROLLER]))
        assertEquals(Presentation.SimpleForm, BedrockPlanner.plan(touchOnly, bedrockMobile, alwaysDefaults))
        assertEquals(Presentation.SimpleForm, BedrockPlanner.plan(touchOnly, bedrockConsole, alwaysDefaults))
        assertEquals(Presentation.Inventory, BedrockPlanner.plan(touchOnly, bedrockPc, alwaysDefaults))

        // AUTO infers CUSTOM / MODAL / SIMPLE from fields / confirm / buttons
        val customMenu = Menu(title = "Nick", bedrock = BedrockMenu(fields = [BedrockField.Input("nick", "Nickname")]))
        assertEquals(Presentation.CustomForm, BedrockPlanner.plan(customMenu, bedrockPc, invDefaults))

        val modalMenu = Menu(title = "Confirm", bedrock = BedrockMenu(confirm = BedrockChoice("Yes"), cancel = BedrockChoice("No")))
        assertEquals(Presentation.ModalForm, BedrockPlanner.plan(modalMenu, bedrockMobile, invDefaults))

        val simpleMenu = Menu(title = "Hub", bedrock = BedrockMenu(buttons = [BedrockButton("Spawn")]))
        assertEquals(Presentation.SimpleForm, BedrockPlanner.plan(simpleMenu, bedrockPc, invDefaults))

        // Redirects
        val redirectMenu = Menu(title = "Shop", bedrock = BedrockMenu(devices = [DeviceKind.MOBILE], menu = "shop-mobile"))
        assertEquals(Presentation.Redirect("shop-mobile"), BedrockPlanner.plan(redirectMenu, bedrockMobile, invDefaults))
        assertEquals(Presentation.Inventory, BedrockPlanner.plan(redirectMenu, bedrockPc, invDefaults))
    }

    @Test
    fun titlesOverlaysFillersAndClickChoices() {
        val defaults = MainConfig.Bedrock()
        val menu = Menu(title = "\uE001\uF808&8Shop", bedrock = BedrockMenu(title = "&8Bedrock Shop"))
        assertEquals("\uE001\uF808&8Shop", BedrockPlanner.titleTemplate(menu, javaPc))
        assertEquals("&8Bedrock Shop", BedrockPlanner.titleTemplate(menu, bedrockMobile))
        assertEquals("§8Shop", stripPrivateUse("\uE001\uF808§8Shop"))

        // Pocket UI skips inventory-slots overlay by default, Bedrock Classic & Java keep it
        assertTrue(BedrockPlanner.shouldDrawInventoryOverlay(menu, javaPc, defaults))
        assertTrue(BedrockPlanner.shouldDrawInventoryOverlay(menu, bedrockPc, defaults))
        assertFalse(BedrockPlanner.shouldDrawInventoryOverlay(menu, bedrockMobile, defaults))
        val forcedOverlay = menu.copy(bedrock = menu.bedrock!!.copy(inventoryOverlay = true))
        assertTrue(BedrockPlanner.shouldDrawInventoryOverlay(forcedOverlay, bedrockMobile, defaults))

        // Fillers vs interactive items
        val glassBorder = Item(material = "gray_stained_glass_pane", name = " ")
        val namedInfo = Item(material = "book", name = "&eHelp")
        val hiddenItem = Item(material = "diamond", name = "&bDiamond", bedrock = BedrockItem(hidden = true))
        val clickableNoName = Item(material = "barrier", click = Clicks(any = Handler(actions = [Actions.Close])))
        assertTrue(BedrockPlanner.isFiller(glassBorder))
        assertFalse(BedrockPlanner.isFiller(namedInfo))
        assertTrue(BedrockPlanner.isFiller(hiddenItem))
        assertFalse(BedrockPlanner.isFiller(clickableNoName))

        // Click choices with custom Bedrock labels
        val trade = Item(
            material = "diamond",
            name = "&bDiamond",
            click = Clicks(
                left = Handler(actions = [Actions.Message("bought")]),
                right = Handler(actions = [Actions.Message("sold")]),
                shiftLeft = Handler(actions = [Actions.Message("bought stack")]),
            ),
            bedrock = BedrockItem(leftLabel = "&aBuy 1", rightLabel = "&cSell 1"),
        )
        val choices = ClickChoices.choicesFor(trade)
        assertEquals(listOf("&aBuy 1", "&cSell 1", "Shift + Left click"), choices.map { it.label })

        // Single `any` click
        val close = Item(material = "barrier", click = Clicks(any = Handler(actions = [Actions.Close])))
        assertEquals(listOf("Select"), ClickChoices.choicesFor(close).map { it.label })
    }

    @Test
    fun bedrockIconsResolvePathsUrlsAndTemplates() {
        assertEquals(FormIcon(FormIcon.Type.PATH, "textures/items/diamond_sword"), BedrockIcons.resolve("diamond_sword", null))
        assertEquals(FormIcon(FormIcon.Type.PATH, "textures/items/clock_item"), BedrockIcons.resolve("minecraft:clock", null))
        assertEquals(FormIcon(FormIcon.Type.PATH, "textures/blocks/chest_front"), BedrockIcons.resolve("chest", null))
        assertEquals(FormIcon(FormIcon.Type.PATH, "textures/blocks/oak_planks"), BedrockIcons.resolve("oak_planks", null))
        assertEquals(FormIcon(FormIcon.Type.URL, "https://example.com/icon.png"), BedrockIcons.resolve("stone", "https://example.com/icon.png"))
        assertEquals(FormIcon(FormIcon.Type.PATH, "textures/ui/check"), BedrockIcons.resolve("stone", "textures/ui/check"))
        assertEquals(
            FormIcon(FormIcon.Type.URL, "https://cdn.example.com/diamond.png"),
            BedrockIcons.resolve("diamond", null, "https://cdn.example.com/{material}.png"),
        )
        assertNull(BedrockIcons.resolve("air", null))
        assertNull(BedrockIcons.resolve("nexo:ruby", null))
        assertNull(BedrockIcons.resolve("head:%player_name%", null))
    }

    @Test
    fun customFormValueBinding() {
        val fields = listOf(
            BedrockField.Input("nick", "Nickname"),
            BedrockField.Toggle("notify", "Notifications", default = true),
            BedrockField.Dropdown("tier", "Tier", options = ["Bronze", "Silver", "Gold"]),
            BedrockField.Slider("amount", "Amount", min = 1f, max = 64f, step = 1f),
            BedrockField.StepSlider("speed", "Speed", steps = ["Slow", "Normal", "Fast"]),
        )
        val bound = BedrockForms.bindCustomValues(fields, ["Alex", "true", "2", "16", "0"]) { it }
        assertEquals("Alex", bound["nick"])
        assertEquals("true", bound["notify"])
        assertEquals("Gold", bound["tier"])
        assertEquals("2", bound["tier_index"])
        assertEquals("16", bound["amount"])
        assertEquals("Slow", bound["speed"])
        assertEquals("0", bound["speed_index"])
    }

    @Test
    fun hoconRoundTripForBedrockMenusAndClientCondition() {
        val source = """
            title = "&8Shop"
            size = 27
            bedrock {
                mode = "CUSTOM"
                devices = ["MOBILE", "CONSOLE"]
                inputs = ["TOUCH", "CONTROLLER"]
                ui = ["POCKET"]
                title = "&8Mobile Shop"
                content = "&7Pick your options"
                fields = [
                    { type = "label", text = "&eConfigure order" }
                    { type = "input", id = "note", text = "Note", placeholder = "Optional" }
                    { type = "toggle", id = "gift", text = "Gift wrap", default = true }
                    { type = "dropdown", id = "item", text = "Item", options = ["Diamond", "Emerald"], default = 1 }
                    { type = "slider", id = "qty", text = "Quantity", min = 1, max = 64, step = 1, default = 4 }
                    { type = "step-slider", id = "tier", text = "Tier", steps = ["Basic", "Pro"] }
                ]
                on-submit = [{ type = "message", text = "Ordered {qty} {item} ({note})" }]
                on-close = [{ type = "message", text = "Closed" }]
            }
            items {
                buy {
                    material = "diamond"
                    slot = 13
                    name = "&bDiamond"
                    view {
                        require = [{ condition = { type = "client", edition = "JAVA" } }]
                    }
                    bedrock {
                        text = "&bBuy Diamond"
                        image = "textures/items/diamond"
                        left-label = "&aBuy 1"
                        right-label = "&cSell 1"
                    }
                    click.left.actions = [{ type = "input", prompt = "How many?", mode = "FORM", then = [{ type = "message", text = "{input}" }] }]
                }
            }
        """.trimIndent()

        val read = format.decodeChecked(source)
        assertEquals(emptyList(), read.warnings)
        val menu = read.menu
        val bedrock = menu.bedrock!!
        assertEquals(BedrockMenu.Mode.CUSTOM, bedrock.mode)
        assertEquals([DeviceKind.MOBILE, DeviceKind.CONSOLE], bedrock.devices)
        assertEquals([InputMode.TOUCH, InputMode.CONTROLLER], bedrock.inputs)
        assertEquals([UiProfile.POCKET], bedrock.ui)
        assertEquals(6, bedrock.fields.size)
        assertEquals(Social.Input.Mode.FORM, (menu.items.getValue("buy").click.left!!.actions.single() as Social.Input).mode)
        assertEquals("&aBuy 1", menu.items.getValue("buy").bedrock!!.leftLabel)

        val encoded = format.encode(menu)
        val reread = format.decodeChecked(encoded)
        assertEquals(emptyList(), reread.warnings)
        assertEquals(menu, reread.menu)
    }

    @Test
    fun invalidBedrockFieldConfigsFailCleanly() {
        assertFailsWith<IllegalArgumentException> { BedrockField.Input(" ", "Name") }
        assertFailsWith<IllegalArgumentException> { BedrockField.Dropdown("d", "Pick", options = []) }
        assertFailsWith<IllegalArgumentException> { BedrockField.Dropdown("d", "Pick", options = ["A"], default = 2) }
        assertFailsWith<IllegalArgumentException> { BedrockField.Slider("s", "Count", min = 10f, max = 5f) }
        assertFailsWith<IllegalArgumentException> { BedrockField.Slider("s", "Count", min = 0f, max = 10f, step = 0f) }
        assertFailsWith<IllegalArgumentException> { BedrockField.Slider("s", "Count", min = 0f, max = 10f, default = 20f) }
        assertFailsWith<IllegalArgumentException> { BedrockField.StepSlider("ss", "Tier", steps = []) }
    }
}
