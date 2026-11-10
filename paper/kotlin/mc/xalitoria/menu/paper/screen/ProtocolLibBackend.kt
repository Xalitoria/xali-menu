package mc.xalitoria.menu.paper.screen

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import mc.xalitoria.menu.paper.XaliMenu
import org.bukkit.inventory.ItemStack

/** [Screens] through ProtocolLib, which is used when PacketEvents is not installed. Only loaded then, since it names its classes. */
class ProtocolLibBackend(private val plugin: XaliMenu, private val screens: Screens) : ScreenBackend {
    override val name = "ProtocolLib"

    private val adapter = object : PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.OPEN_WINDOW, PacketType.Play.Server.WINDOW_ITEMS, PacketType.Play.Server.SET_SLOT) {
        override fun onPacketSending(event: PacketEvent) {
            val id = event.player?.uniqueId ?: return
            val windowId = event.packet.integers.read(0)
            when (event.packetType) {
                PacketType.Play.Server.OPEN_WINDOW -> screens.opened(id, windowId)
                PacketType.Play.Server.WINDOW_ITEMS -> {
                    val overlay = screens.overlay(id, windowId) ?: return
                    // The packet may be shared with other players, so it is copied before it is changed.
                    val packet = event.packet.deepClone()
                    val items = packet.itemListModifier.read(0).toMutableList()
                    overlay.rewrite(items)
                    packet.itemListModifier.write(0, items)
                    event.packet = packet
                }
                PacketType.Play.Server.SET_SLOT -> {
                    val overlay = screens.overlay(id, windowId) ?: return
                    val replacement = overlay.replacement<ItemStack>(event.packet.integers.read(2)) ?: return
                    val packet = event.packet.deepClone()
                    packet.itemModifier.write(0, replacement)
                    event.packet = packet
                }
            }
        }
    }

    init {
        ProtocolLibrary.getProtocolManager().addPacketListener(adapter)
    }

    /** ProtocolLib's packets carry the server's own stacks. */
    override fun pack(stack: ItemStack): Any = stack

    override fun stop() {
        ProtocolLibrary.getProtocolManager().removePacketListener(adapter)
    }
}
