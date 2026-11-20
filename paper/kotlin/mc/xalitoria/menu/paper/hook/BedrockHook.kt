package mc.xalitoria.menu.paper.hook

import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.bedrock.BedrockBridge
import mc.xalitoria.menu.paper.bedrock.ClientProfile
import mc.xalitoria.menu.paper.bedrock.CustomFieldSpec
import mc.xalitoria.menu.paper.bedrock.CustomFormSpec
import mc.xalitoria.menu.paper.bedrock.FormIcon
import mc.xalitoria.menu.paper.bedrock.ModalFormSpec
import mc.xalitoria.menu.paper.bedrock.SimpleFormSpec
import net.xalitoria.menu.model.ClientEdition
import net.xalitoria.menu.model.DeviceOs
import net.xalitoria.menu.model.InputMode
import net.xalitoria.menu.model.UiProfile
import org.bukkit.entity.Player
import org.geysermc.api.util.BedrockPlatform
import org.geysermc.cumulus.form.CustomForm
import org.geysermc.cumulus.form.Form
import org.geysermc.cumulus.form.ModalForm
import org.geysermc.cumulus.form.SimpleForm
import org.geysermc.cumulus.response.CustomFormResponse
import org.geysermc.cumulus.util.FormImage
import org.geysermc.floodgate.api.FloodgateApi
import org.geysermc.geyser.api.GeyserApi
import org.geysermc.api.util.InputMode as GeyserInputMode
import org.geysermc.api.util.UiProfile as GeyserUiProfile
import org.geysermc.floodgate.util.DeviceOs as FloodgateOs
import org.geysermc.floodgate.util.InputMode as FloodgateInputMode
import org.geysermc.floodgate.util.UiProfile as FloodgateUiProfile

/** Detects Floodgate or Geyser-Spigot at startup without linking their classes when absent. */
object BedrockHooks {
    fun detect(plugin: XaliMenu): BedrockBridge? {
        val pm = plugin.server.pluginManager
        return try {
            when {
                pm.isPluginEnabled("floodgate") -> FloodgateBridge()
                pm.isPluginEnabled("Geyser-Spigot") -> GeyserBridge()
                else -> null
            }
        } catch (e: Throwable) {
            plugin.logger.warning("Bedrock support could not be started: ${e.message ?: e::class.simpleName}")
            null
        }
    }
}

/** Shared Cumulus form builders used by both [FloodgateBridge] and [GeyserBridge]. */
private object CumulusForms {
    fun simple(spec: SimpleFormSpec, onSelect: (Int) -> Unit, onClose: () -> Unit): SimpleForm {
        val builder = SimpleForm.builder().title(spec.title)
        if (spec.content.isNotEmpty()) builder.content(spec.content)
        for (button in spec.buttons) {
            val icon = button.icon
            if (icon == null) builder.button(button.text)
            else builder.button(button.text, if (icon.type == FormIcon.Type.URL) FormImage.Type.URL else FormImage.Type.PATH, icon.value)
        }
        builder.validResultHandler { response -> onSelect(response.clickedButtonId()) }
        builder.closedOrInvalidResultHandler(Runnable { onClose() })
        return builder.build()
    }

    fun modal(spec: ModalFormSpec, onChoose: (Boolean) -> Unit, onClose: () -> Unit): ModalForm = ModalForm.builder()
        .title(spec.title)
        .content(spec.content)
        .button1(spec.button1)
        .button2(spec.button2)
        .validResultHandler { response -> onChoose(response.clickedFirst()) }
        .closedOrInvalidResultHandler(Runnable { onClose() })
        .build()

    fun custom(spec: CustomFormSpec, onSubmit: (List<String>) -> Unit, onClose: () -> Unit): CustomForm {
        val builder = CustomForm.builder().title(spec.title)
        val interactive = mutableListOf<CustomFieldSpec>()
        for (field in spec.fields) {
            when (field) {
                is CustomFieldSpec.Label -> builder.label(field.text)
                is CustomFieldSpec.Input -> {
                    builder.input(field.text, field.placeholder, field.default)
                    interactive += field
                }
                is CustomFieldSpec.Toggle -> {
                    builder.toggle(field.text, field.default)
                    interactive += field
                }
                is CustomFieldSpec.Dropdown -> {
                    builder.dropdown(field.text, field.options, field.default)
                    interactive += field
                }
                is CustomFieldSpec.Slider -> {
                    builder.slider(field.text, field.min, field.max, field.step, field.default)
                    interactive += field
                }
                is CustomFieldSpec.StepSlider -> {
                    builder.stepSlider(field.text, field.steps, field.default)
                    interactive += field
                }
            }
        }
        builder.validResultHandler { response -> onSubmit(readValues(interactive, response)) }
        builder.closedOrInvalidResultHandler(Runnable { onClose() })
        return builder.build()
    }

    private fun readValues(fields: List<CustomFieldSpec>, response: CustomFormResponse): List<String> {
        response.includeLabels(false)
        return fields.map { field ->
            when (field) {
                is CustomFieldSpec.Input -> response.asInput().orEmpty()
                is CustomFieldSpec.Toggle -> response.asToggle().toString()
                is CustomFieldSpec.Dropdown -> response.asDropdown().toString()
                is CustomFieldSpec.Slider -> formatSlider(response.asSlider())
                is CustomFieldSpec.StepSlider -> response.asStepSlider().toString()
                is CustomFieldSpec.Label -> ""
            }
        }
    }

    private fun formatSlider(value: Float): String {
        val rounded = value.toLong()
        return if (value == rounded.toFloat()) rounded.toString() else value.toString()
    }
}

/** Bedrock bridge through Floodgate (works both standalone and behind a Velocity proxy running Geyser). */
internal class FloodgateBridge : BedrockBridge {
    override val name = "Floodgate"

    private val api: FloodgateApi get() = FloodgateApi.getInstance()

    override fun profile(player: Player): ClientProfile? {
        val fp = api.getPlayer(player.uniqueId) ?: return null
        return ClientProfile(
            edition = ClientEdition.BEDROCK,
            os = mapOs(fp.deviceOs),
            input = mapInput(fp.inputMode),
            ui = mapUi(fp.uiProfile),
        )
    }

    override fun sendSimple(player: Player, form: SimpleFormSpec, onSelect: (Int) -> Unit, onClose: () -> Unit): Boolean =
        send(player, CumulusForms.simple(form, onSelect, onClose))

    override fun sendModal(player: Player, form: ModalFormSpec, onChoose: (Boolean) -> Unit, onClose: () -> Unit): Boolean =
        send(player, CumulusForms.modal(form, onChoose, onClose))

    override fun sendCustom(player: Player, form: CustomFormSpec, onSubmit: (List<String>) -> Unit, onClose: () -> Unit): Boolean =
        send(player, CumulusForms.custom(form, onSubmit, onClose))

    override fun closeForm(player: Player) {
        runCatching { api.closeForm(player.uniqueId) }
    }

    private fun send(player: Player, form: Form): Boolean = api.sendForm(player.uniqueId, form)

    private fun mapOs(os: FloodgateOs?): DeviceOs = when (os) {
        FloodgateOs.GOOGLE -> DeviceOs.ANDROID
        FloodgateOs.IOS -> DeviceOs.IOS
        FloodgateOs.OSX -> DeviceOs.OSX
        FloodgateOs.AMAZON -> DeviceOs.AMAZON
        FloodgateOs.GEARVR -> DeviceOs.GEAR_VR
        FloodgateOs.HOLOLENS -> DeviceOs.HOLOLENS
        FloodgateOs.UWP, FloodgateOs.WIN32 -> DeviceOs.WINDOWS
        FloodgateOs.WINDOWS_PHONE -> DeviceOs.WINDOWS_PHONE
        FloodgateOs.DEDICATED -> DeviceOs.DEDICATED
        FloodgateOs.TVOS -> DeviceOs.TVOS
        FloodgateOs.PS4 -> DeviceOs.PLAYSTATION
        FloodgateOs.NX -> DeviceOs.SWITCH
        FloodgateOs.XBOX -> DeviceOs.XBOX
        FloodgateOs.UNKNOWN, null -> DeviceOs.UNKNOWN
    }

    private fun mapInput(mode: FloodgateInputMode?): InputMode = when (mode) {
        FloodgateInputMode.KEYBOARD_MOUSE -> InputMode.KEYBOARD_MOUSE
        FloodgateInputMode.TOUCH -> InputMode.TOUCH
        FloodgateInputMode.CONTROLLER -> InputMode.CONTROLLER
        FloodgateInputMode.VR -> InputMode.VR
        FloodgateInputMode.UNKNOWN, null -> InputMode.UNKNOWN
    }

    private fun mapUi(ui: FloodgateUiProfile?): UiProfile = when (ui) {
        FloodgateUiProfile.POCKET -> UiProfile.POCKET
        FloodgateUiProfile.CLASSIC, null -> UiProfile.CLASSIC
    }
}

/** Bedrock bridge through Geyser-Spigot directly when Floodgate is not installed. */
internal class GeyserBridge : BedrockBridge {
    override val name = "Geyser"

    private val api: GeyserApi get() = GeyserApi.api()

    override fun profile(player: Player): ClientProfile? {
        val conn = api.connectionByUuid(player.uniqueId) ?: return null
        return ClientProfile(
            edition = ClientEdition.BEDROCK,
            os = mapPlatform(conn.platform()),
            input = mapInput(conn.inputMode()),
            ui = mapUi(conn.uiProfile()),
        )
    }

    override fun sendSimple(player: Player, form: SimpleFormSpec, onSelect: (Int) -> Unit, onClose: () -> Unit): Boolean =
        send(player, CumulusForms.simple(form, onSelect, onClose))

    override fun sendModal(player: Player, form: ModalFormSpec, onChoose: (Boolean) -> Unit, onClose: () -> Unit): Boolean =
        send(player, CumulusForms.modal(form, onChoose, onClose))

    override fun sendCustom(player: Player, form: CustomFormSpec, onSubmit: (List<String>) -> Unit, onClose: () -> Unit): Boolean =
        send(player, CumulusForms.custom(form, onSubmit, onClose))

    override fun closeForm(player: Player) {
        runCatching { api.connectionByUuid(player.uniqueId)?.closeForm() }
    }

    private fun send(player: Player, form: Form): Boolean = api.sendForm(player.uniqueId, form)

    @Suppress("DEPRECATION") // Geyser deprecated legacy platforms (HoloLens, Windows Phone, tvOS), but older clients can still report them.
    private fun mapPlatform(platform: BedrockPlatform?): DeviceOs = when (platform) {
        BedrockPlatform.GOOGLE -> DeviceOs.ANDROID
        BedrockPlatform.IOS -> DeviceOs.IOS
        BedrockPlatform.OSX -> DeviceOs.OSX
        BedrockPlatform.LINUX -> DeviceOs.LINUX
        BedrockPlatform.AMAZON -> DeviceOs.AMAZON
        BedrockPlatform.GEARVR -> DeviceOs.GEAR_VR
        BedrockPlatform.HOLOLENS -> DeviceOs.HOLOLENS
        BedrockPlatform.UWP, BedrockPlatform.WIN32 -> DeviceOs.WINDOWS
        BedrockPlatform.WINDOWS_PHONE -> DeviceOs.WINDOWS_PHONE
        BedrockPlatform.DEDICATED -> DeviceOs.DEDICATED
        BedrockPlatform.TVOS -> DeviceOs.TVOS
        BedrockPlatform.PS4 -> DeviceOs.PLAYSTATION
        BedrockPlatform.NX -> DeviceOs.SWITCH
        BedrockPlatform.XBOX -> DeviceOs.XBOX
        BedrockPlatform.UNKNOWN, null -> DeviceOs.UNKNOWN
    }

    private fun mapInput(mode: GeyserInputMode?): InputMode = when (mode) {
        GeyserInputMode.KEYBOARD_MOUSE -> InputMode.KEYBOARD_MOUSE
        GeyserInputMode.TOUCH -> InputMode.TOUCH
        GeyserInputMode.CONTROLLER -> InputMode.CONTROLLER
        GeyserInputMode.VR -> InputMode.VR
        GeyserInputMode.UNKNOWN, null -> InputMode.UNKNOWN
    }

    private fun mapUi(ui: GeyserUiProfile?): UiProfile = when (ui) {
        GeyserUiProfile.POCKET -> UiProfile.POCKET
        GeyserUiProfile.CLASSIC, null -> UiProfile.CLASSIC
    }
}
