package mc.xalitoria.menu.paper.extension

import java.net.URLClassLoader
import java.nio.file.Path

/**
 * Loads the classes of one extension jar. A class is looked for in the [parent] first (XaliMenu, its libraries, Bukkit), so the
 * API and the Kotlin runtime are the ones the plugin uses, then in the jar, and last in the plugins that are enabled, which is how
 * an extension reaches the classes of the plugin it is written for without a `plugin.yml` of its own.
 */
internal class ExtensionClassLoader(
    jar: Path,
    parent: ClassLoader,
    private val plugins: () -> Collection<ClassLoader>,
) : URLClassLoader(arrayOf(jar.toUri().toURL()), parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            try {
                return super.loadClass(name, resolve)
            } catch (_: ClassNotFoundException) {
                // Not in XaliMenu or in the jar; another plugin may have it.
            }
            for (loader in plugins()) {
                try {
                    return Class.forName(name, false, loader)
                } catch (_: ClassNotFoundException) {
                    // The next one.
                }
            }
            throw ClassNotFoundException(name)
        }
    }
}
