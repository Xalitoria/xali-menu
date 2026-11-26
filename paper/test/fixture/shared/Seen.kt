package fixture.shared

import net.xalitoria.menu.ActivatorSource
import java.util.concurrent.CopyOnWriteArrayList

/** What the fixture extensions did, kept where both the test and the extension's own class loader can see it. */
object Seen {
    val log = CopyOnWriteArrayList<String>()

    @Volatile
    var depLoader: ClassLoader? = null

    @Volatile
    var activator: ActivatorSource? = null

    fun reset() {
        log.clear()
        depLoader = null
        activator = null
    }
}
