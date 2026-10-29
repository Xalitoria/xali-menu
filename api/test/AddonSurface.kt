import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import mc.xalitoria.menu.api.Action

/**
 * A plugin that carries its own copy of the Kotlin runtime must be able to call this API. Every class it loads has the
 * runtime of its own plugin, so a Kotlin type in a signature here (a function type, a `KClass`, a serializer, the marker
 * that default constructor arguments add) is a different class on each side, and the call fails with a LinkageError.
 * So what an addon touches is made of JDK, Bukkit, Adventure and this package, and this test keeps it that way.
 */
class AddonSurface {
    private val root: File = File(Action::class.java.protectionDomain.codeSource.location.toURI())

    /** Every class of the API except the menu model, which is read from files and not called by addons. */
    private val classes: List<Class<*>> = File(root, "mc/xalitoria/menu/api").walkTopDown()
        .filter { it.extension == "class" && "/model/" !in it.invariantSeparatorsPath }
        .map { Class.forName(it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.')) }
        .toList()

    private fun foreign(type: Class<*>): Boolean {
        var element = type
        while (element.isArray) element = element.componentType
        return element.name.startsWith("kotlin.") || element.name.startsWith("kotlinx.")
    }

    @Test
    fun noKotlinTypeInAnySignature() {
        assertTrue(classes.size > 10, "the classes of the API are found")
        val found = buildList {
            for (type in classes) {
                for (method in type.declaredMethods) {
                    if (!Modifier.isPublic(method.modifiers) && !Modifier.isProtected(method.modifiers)) continue
                    if (method.isSynthetic && method.name.startsWith("access\$")) continue // plumbing between a class and its companion
                    if (foreign(method.returnType) || method.parameterTypes.any(::foreign)) add("${type.name}.${method.name}")
                }
                for (constructor in type.declaredConstructors) {
                    if (!Modifier.isPublic(constructor.modifiers) && !Modifier.isProtected(constructor.modifiers)) continue
                    if (type.name.endsWith("\$Companion")) continue // only the class itself calls it
                    if (constructor.parameterTypes.any(::foreign)) add("${type.name}.<init>")
                }
                for (field in type.declaredFields) {
                    if (!Modifier.isPublic(field.modifiers) && !Modifier.isProtected(field.modifiers)) continue
                    if (foreign(field.type)) add("${type.name}.${field.name}")
                }
                if (type.superclass?.let(::foreign) == true || type.interfaces.any(::foreign)) add("${type.name} extends a Kotlin type")
            }
        }
        assertEquals(emptyList(), found)
    }

    @Test
    fun noDefaultImplementationsOfInterfaceMethods() {
        // They would live in a DefaultImpls class that an addon implementing the interface has to load from here.
        assertEquals(emptyList(), classes.filter { it.name.endsWith("\$DefaultImpls") }.map { it.name })
    }
}
