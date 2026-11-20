package mc.xalitoria.menu.paper.bedrock

import org.bukkit.entity.Player

/** An icon attached to a Bedrock `SimpleForm` button: a Bedrock texture path or an HTTP(S) URL. */
data class FormIcon(val type: Type, val value: String) {
    enum class Type { PATH, URL }
}

/** One button of a [SimpleFormSpec]. */
data class SimpleButtonSpec(val text: String, val icon: FormIcon? = null)

/** A platform-neutral description of a Bedrock `SimpleForm`. */
data class SimpleFormSpec(
    val title: String,
    val content: String = "",
    val buttons: List<SimpleButtonSpec>,
)

/** A platform-neutral description of a Bedrock `ModalForm`. */
data class ModalFormSpec(
    val title: String,
    val content: String = "",
    val button1: String,
    val button2: String,
)

/** One component of a [CustomFormSpec]. */
sealed interface CustomFieldSpec {
    data class Label(val text: String) : CustomFieldSpec
    data class Input(val text: String, val placeholder: String, val default: String) : CustomFieldSpec
    data class Toggle(val text: String, val default: Boolean) : CustomFieldSpec
    data class Dropdown(val text: String, val options: List<String>, val default: Int) : CustomFieldSpec
    data class Slider(val text: String, val min: Float, val max: Float, val step: Float, val default: Float) : CustomFieldSpec
    data class StepSlider(val text: String, val steps: List<String>, val default: Int) : CustomFieldSpec
}

/** A platform-neutral description of a Bedrock `CustomForm`. */
data class CustomFormSpec(
    val title: String,
    val fields: List<CustomFieldSpec>,
)

/**
 * What the server needs from a Bedrock bridge (Floodgate or Geyser-Spigot): identifying Bedrock players and sending
 * native Cumulus forms. Kept behind an interface so tests run without Floodgate or Geyser on the classpath.
 */
interface BedrockBridge {
    /** Display name of the bridge in use (`Floodgate` or `Geyser`). */
    val name: String

    /** Returns the client profile of [player], or null if [player] is not a Bedrock player. */
    fun profile(player: Player): ClientProfile?

    /** Sends a `SimpleForm` to [player]. [onSelect] receives the 0-based button index; [onClose] runs if closed without selection. */
    fun sendSimple(player: Player, form: SimpleFormSpec, onSelect: (Int) -> Unit, onClose: () -> Unit): Boolean

    /** Sends a `ModalForm` to [player]. [onChoose] receives `true` for button1 and `false` for button2. */
    fun sendModal(player: Player, form: ModalFormSpec, onChoose: (Boolean) -> Unit, onClose: () -> Unit): Boolean

    /**
     * Sends a `CustomForm` to [player]. [onSubmit] receives one string value per non-label field in order
     * (raw text for inputs, `true`/`false` for toggles, selected index for dropdowns and step sliders, float text for sliders).
     */
    fun sendCustom(player: Player, form: CustomFormSpec, onSubmit: (List<String>) -> Unit, onClose: () -> Unit): Boolean

    /** Closes any open Bedrock form for [player] if supported by the bridge. */
    fun closeForm(player: Player) {}
}
