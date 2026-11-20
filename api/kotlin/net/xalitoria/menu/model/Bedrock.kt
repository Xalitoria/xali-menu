package net.xalitoria.menu.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.xalitoria.menu.Action

/** Whether a player connects with Java Edition or Bedrock Edition (through Geyser or Floodgate). */
enum class ClientEdition { JAVA, BEDROCK }

/**
 * The kind of hardware a player uses, grouped from [DeviceOs]: `PC` (Java, Windows, macOS, Linux), `MOBILE` (Android,
 * iOS, Amazon Fire, Windows Phone), `CONSOLE` (Xbox, PlayStation, Nintendo Switch, Apple TV), `VR` (Gear VR, HoloLens)
 * or `UNKNOWN`.
 */
enum class DeviceKind { PC, MOBILE, CONSOLE, VR, UNKNOWN }

/** The operating system of the player's client. Java Edition clients report [JAVA]. */
enum class DeviceOs {
    JAVA,
    ANDROID,
    IOS,
    OSX,
    LINUX,
    AMAZON,
    GEAR_VR,
    HOLOLENS,
    WINDOWS,
    WINDOWS_PHONE,
    DEDICATED,
    TVOS,
    PLAYSTATION,
    SWITCH,
    XBOX,
    UNKNOWN;

    /** The broad device category for this operating system. */
    fun deviceKind(): DeviceKind = when (this) {
        JAVA, WINDOWS, OSX, LINUX -> DeviceKind.PC
        ANDROID, IOS, AMAZON, WINDOWS_PHONE -> DeviceKind.MOBILE
        XBOX, PLAYSTATION, SWITCH, TVOS -> DeviceKind.CONSOLE
        GEAR_VR, HOLOLENS -> DeviceKind.VR
        DEDICATED, UNKNOWN -> DeviceKind.UNKNOWN
    }
}

/** How the player controls the game. Java Edition clients report [KEYBOARD_MOUSE]. */
enum class InputMode { KEYBOARD_MOUSE, TOUCH, CONTROLLER, VR, UNKNOWN }

/** The inventory UI layout the client uses: Java Edition reports [JAVA], Bedrock reports [CLASSIC] or [POCKET]. */
enum class UiProfile { JAVA, CLASSIC, POCKET }

/**
 * How a menu behaves for Bedrock Edition players (connected through Floodgate or Geyser).
 *
 * A menu can render as a native Bedrock form (`SIMPLE`, `MODAL` or `CUSTOM`), redirect to another menu ([menu]), or
 * stay an inventory window (`INVENTORY`) with Bedrock-safe title handling and click adaptation. Filters ([devices],
 * [inputs], [ui]) restrict form or redirect behavior to matching Bedrock devices (for example, showing a `SIMPLE` form
 * to touch and controller players while Bedrock PC players keep the chest inventory).
 */
@Serializable
data class BedrockMenu(
    /**
     * How this menu opens for matching Bedrock players. `AUTO` picks `CUSTOM` when [fields] are set, `MODAL` when
     * [confirm] is set, `SIMPLE` when [buttons] are set, or follows `bedrock.default-mode` in `config.yml`.
     */
    val mode: Mode = Mode.AUTO,
    /** Bedrock device categories this configuration applies to; empty means every Bedrock device. */
    val devices: List<DeviceKind> = [],
    /** Bedrock input modes this configuration applies to; empty means any input mode. */
    val inputs: List<InputMode> = [],
    /** Bedrock UI profiles this configuration applies to; empty means any UI profile. */
    val ui: List<UiProfile> = [],
    /** Another menu to open instead for matching Bedrock players, such as a mobile-tailored layout. */
    val menu: String? = null,
    /** Title shown to Bedrock players instead of the menu's Java title, avoiding resource-pack font glyphs. */
    val title: String? = null,
    /** Body text shown above the buttons of a `SIMPLE` or `MODAL` form. Colors, placeholders and `\n` line breaks are allowed. */
    val content: String = "",
    /** Explicit buttons of a `SIMPLE` form. When empty, visible items (and paged elements) of the menu become buttons automatically. */
    val buttons: List<BedrockButton> = [],
    /** First button of a `MODAL` form. */
    val confirm: BedrockChoice? = null,
    /** Second button of a `MODAL` form. */
    val cancel: BedrockChoice? = null,
    /** Interactive controls and labels of a `CUSTOM` form. */
    val fields: List<BedrockField> = [],
    /** Actions run when a `CUSTOM` form is submitted. Each field's answer is in scope as `{id}`. */
    val onSubmit: List<Action> = [],
    /** Actions run when a Bedrock form is closed without tapping a button or submitting. */
    val onClose: List<Action> = [],
    /** Skip decorative items that have no click actions when converting an inventory menu into a `SIMPLE` form. */
    val skipFillers: Boolean = true,
    /** Include the first non-blank lore line under the item name on auto-converted `SIMPLE` form buttons. */
    val includeLore: Boolean = true,
    /** Open a follow-up choice form when a Bedrock player activates an item with multiple click handlers (such as left and right click). */
    val actionPicker: Boolean = true,
    /** Whether `inventory-slots` packet overlays are drawn for Bedrock Pocket UI players. */
    val inventoryOverlay: Boolean = false,
    /** Label for the previous-page button on an auto-converted paged `SIMPLE` form. */
    val prevButton: String = "&e« Previous ({page_prev}/{pages})",
    /** Label for the next-page button on an auto-converted paged `SIMPLE` form. */
    val nextButton: String = "&eNext ({page_next}/{pages}) »",
) {
    /** How a menu is presented to a matching Bedrock player. */
    enum class Mode { AUTO, INVENTORY, SIMPLE, MODAL, CUSTOM }
}

/** One button of a Bedrock `SIMPLE` form. */
@Serializable
data class BedrockButton(
    /** Label shown on the button. Use `\n` for a second line. Colors and placeholders are allowed. */
    val text: String,
    /** Optional button icon: an `https://` URL, a Bedrock texture path (`textures/items/diamond`), or a vanilla item key (`diamond`). */
    val image: String? = null,
    /** Must hold for the button to appear in the form. */
    val view: Gate? = null,
    /** Actions run in order when the button is tapped. */
    val actions: List<Action> = [],
    /** Must hold for [actions] to run when tapped. */
    val require: Gate? = null,
)

/** One of the two buttons of a Bedrock `MODAL` form. */
@Serializable
data class BedrockChoice(
    /** Label shown on the button. Colors and placeholders are allowed. */
    val text: String,
    /** Actions run in order when this button is chosen. */
    val actions: List<Action> = [],
    /** Must hold for [actions] to run when chosen. */
    val require: Gate? = null,
)

/**
 * One element of a Bedrock `CUSTOM` form, listed in `bedrock.fields`. Each interactive element has an [id] whose answer
 * is passed to `bedrock.on-submit` as `{id}` (and `{id}_index` for dropdowns and step sliders).
 */
@Serializable
sealed interface BedrockField {
    /** Static text displayed inside the form. Produces no submitted value. */
    @Serializable @SerialName("label")
    data class Label(
        /** Text shown in the form. Colors and placeholders are allowed. */
        val text: String,
    ) : BedrockField

    /** A text box whose entered text is bound to `{id}` on submit. */
    @Serializable @SerialName("input")
    data class Input(
        /** Variable name used in `{id}` within `on-submit` actions. */
        val id: String,
        /** Label shown above the text box. */
        val text: String,
        /** Hint text shown when the box is empty. */
        val placeholder: String = "",
        /** Initial text placed in the box. Placeholders are allowed. */
        val default: String = "",
    ) : BedrockField {
        init {
            require(id.isNotBlank()) { "a custom form input needs an id" }
        }
    }

    /** An on/off switch whose value (`true` or `false`) is bound to `{id}` on submit. */
    @Serializable @SerialName("toggle")
    data class Toggle(
        /** Variable name used in `{id}` within `on-submit` actions. */
        val id: String,
        /** Label shown beside the switch. */
        val text: String,
        /** Initial state of the switch. */
        val default: Boolean = false,
    ) : BedrockField {
        init {
            require(id.isNotBlank()) { "a custom form toggle needs an id" }
        }
    }

    /** A dropdown list whose chosen option text is bound to `{id}` and 0-based index to `{id}_index`. */
    @Serializable @SerialName("dropdown")
    data class Dropdown(
        /** Variable name used in `{id}` and `{id}_index` within `on-submit` actions. */
        val id: String,
        /** Label shown above the dropdown. */
        val text: String,
        /** Choices listed in order. */
        val options: List<String>,
        /** 0-based index of the initially selected option. */
        @Within(min = 0, strict = true)
        val default: Int = 0,
    ) : BedrockField {
        init {
            require(id.isNotBlank()) { "a custom form dropdown needs an id" }
            require(options.isNotEmpty()) { "a custom form dropdown needs at least one option" }
            require(default in options.indices) { "dropdown default $default is outside 0..${options.lastIndex}" }
        }
    }

    /** A numeric slider whose chosen value is bound to `{id}` on submit. */
    @Serializable @SerialName("slider")
    data class Slider(
        /** Variable name used in `{id}` within `on-submit` actions. */
        val id: String,
        /** Label shown above the slider. */
        val text: String,
        /** Lowest selectable value. */
        val min: Float = 0f,
        /** Highest selectable value. */
        val max: Float = 100f,
        /** Increment between values on the slider. */
        val step: Float = 1f,
        /** Starting value; defaults to [min]. */
        val default: Float? = null,
    ) : BedrockField {
        init {
            require(id.isNotBlank()) { "a custom form slider needs an id" }
            require(max > min) { "slider max ($max) must be greater than min ($min)" }
            require(step > 0f) { "slider step ($step) must be positive" }
            if (default != null) require(default in min..max) { "slider default ($default) must be between $min and $max" }
        }
    }

    /** A stepped slider over named [steps], binding the chosen label to `{id}` and its 0-based index to `{id}_index`. */
    @Serializable @SerialName("step-slider")
    data class StepSlider(
        /** Variable name used in `{id}` and `{id}_index` within `on-submit` actions. */
        val id: String,
        /** Label shown above the step slider. */
        val text: String,
        /** Ordered labels of each stop along the slider. */
        val steps: List<String>,
        /** 0-based index of the starting stop. */
        @Within(min = 0, strict = true)
        val default: Int = 0,
    ) : BedrockField {
        init {
            require(id.isNotBlank()) { "a custom form step-slider needs an id" }
            require(steps.isNotEmpty()) { "a custom form step-slider needs at least one step" }
            require(default in steps.indices) { "step-slider default $default is outside 0..${steps.lastIndex}" }
        }
    }
}

/**
 * Per-item presentation and interaction overrides for Bedrock Edition players.
 *
 * Used when an inventory menu is converted to a Bedrock `SIMPLE` form or when a Bedrock player activates a multi-click
 * item and sees the action picker form.
 */
@Serializable
data class BedrockItem(
    /** Button label in an auto-converted Bedrock form. Defaults to the item's `name` (and first lore line when `include-lore` is on). */
    val text: String? = null,
    /** Button icon in an auto-converted Bedrock form: an `https://` URL, a Bedrock texture path (`textures/items/diamond`), or a vanilla item key. */
    val image: String? = null,
    /** Hide this item from auto-converted Bedrock forms. */
    val hidden: Boolean = false,
    /** Button label for `click.left` in the Bedrock action picker. */
    val leftLabel: String? = null,
    /** Button label for `click.right` in the Bedrock action picker. */
    val rightLabel: String? = null,
    /** Button label for `click.shift-left` in the Bedrock action picker. */
    val shiftLeftLabel: String? = null,
    /** Button label for `click.shift-right` in the Bedrock action picker. */
    val shiftRightLabel: String? = null,
    /** Button label for `click.middle` in the Bedrock action picker. */
    val middleLabel: String? = null,
)
