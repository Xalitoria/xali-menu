package mc.xalitoria.menu.paper.bedrock

import net.xalitoria.menu.model.ClientEdition
import net.xalitoria.menu.model.DeviceKind
import net.xalitoria.menu.model.DeviceOs
import net.xalitoria.menu.model.InputMode
import net.xalitoria.menu.model.UiProfile

/**
 * What the server knows about one player's client: whether it is Java or Bedrock Edition, its operating system, how
 * the player interacts with it, and which inventory UI layout it uses.
 */
data class ClientProfile(
    val edition: ClientEdition,
    val os: DeviceOs,
    val input: InputMode,
    val ui: UiProfile,
) {
    val device: DeviceKind get() = os.deviceKind()
    val isBedrock: Boolean get() = edition == ClientEdition.BEDROCK
    val isTouch: Boolean get() = input == InputMode.TOUCH
    val isPocket: Boolean get() = ui == UiProfile.POCKET

    /** Whether this client uses a non-desktop Bedrock UI or control scheme where chest menus are awkward. */
    val prefersForm: Boolean get() = isBedrock && (isTouch || isPocket || input == InputMode.CONTROLLER || input == InputMode.VR)

    /**
     * Whether this profile matches every non-empty filter given. Empty lists and a null [edition] impose no constraint.
     */
    fun matches(
        edition: ClientEdition? = null,
        devices: List<DeviceKind> = [],
        os: List<DeviceOs> = [],
        inputs: List<InputMode> = [],
        ui: List<UiProfile> = [],
    ): Boolean {
        if (edition != null && this.edition != edition) return false
        if (devices.isNotEmpty() && this.device !in devices) return false
        if (os.isNotEmpty() && this.os !in os) return false
        if (inputs.isNotEmpty() && this.input !in inputs) return false
        if (ui.isNotEmpty() && this.ui !in ui) return false
        return true
    }

    companion object {
        val JAVA = ClientProfile(ClientEdition.JAVA, DeviceOs.JAVA, InputMode.KEYBOARD_MOUSE, UiProfile.JAVA)
    }
}
