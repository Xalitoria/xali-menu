package mc.xalitoria.menu.paper.hook

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.WorldGuard
import org.bukkit.Location

/** The regions of WorldGuard. Its classes are only touched when the plugin is here, so make this only then. */
class Regions {
    /** The ids of the regions that hold the block at [location]; none if the world has no regions. */
    fun at(location: Location): Set<String> {
        val manager = WorldGuard.getInstance().platform.regionContainer.get(BukkitAdapter.adapt(location.world)) ?: return emptySet()
        return manager.getApplicableRegions(BlockVector3.at(location.blockX, location.blockY, location.blockZ)).regions.mapTo(HashSet()) { it.id }
    }
}
