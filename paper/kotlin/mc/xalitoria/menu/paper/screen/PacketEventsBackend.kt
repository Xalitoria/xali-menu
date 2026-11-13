package mc.xalitoria.menu.paper.screen

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import mc.xalitoria.menu.paper.XaliMenu
import org.bukkit.inventory.ItemStack
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketStack

/** [Screens] through PacketEvents, which is used when it is installed. Only loaded then, since it names its classes. */
class PacketEventsBackend(plugin: XaliMenu, private val screens: Screens) : ScreenBackend {
    override val name = "PacketEvents"

    private val listener: PacketListenerCommon = PacketEvents.getAPI().eventManager.registerListener(object : PacketListenerAbstract(PacketListenerPriority.NORMAL) {
        override fun onPacketSend(event: PacketSendEvent) {
            val id = event.user?.uuid ?: return
            when (event.packetType) {
                PacketType.Play.Server.OPEN_WINDOW -> screens.opened(id, WrapperPlayServerOpenWindow(event).containerId)
                PacketType.Play.Server.WINDOW_ITEMS -> {
                    val packet = WrapperPlayServerWindowItems(event)
                    val overlay = screens.overlay(id, packet.windowId) ?: return
                    val items = packet.items.toMutableList()
                    overlay.rewrite(items)
                    packet.items = items
                    event.markForReEncode(true)
                }
                PacketType.Play.Server.SET_SLOT -> {
                    val packet = WrapperPlayServerSetSlot(event)
                    val overlay = screens.overlay(id, packet.windowId) ?: return
                    val replacement = overlay.replacement<PacketStack>(packet.slot) ?: return
                    packet.item = replacement
                    event.markForReEncode(true)
                }
            }
        }
    })

    override fun pack(stack: ItemStack): Any = SpigotConversionUtil.fromBukkitItemStack(stack)

    override fun stop() {
        PacketEvents.getAPI().eventManager.unregisterListener(listener)
    }
}
