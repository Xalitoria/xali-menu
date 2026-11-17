package net.xalitoria.menu.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * The numbers a property of the menu model can take, from [min] to [max] (either may be left open). It is on the model, which is what reads a menu file, so the
 * plugin when it reads a file, the editor's boxes and the editor's problem list all give the same limits. On a list it is the limit of each entry.
 *
 * A number out of range is told and the menu still loads (the plugin uses the nearest number), unless [strict]: then the file is refused, for a class that
 * cannot be made with it.
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Within(val min: Long = Long.MIN_VALUE, val max: Long = Long.MAX_VALUE, val strict: Boolean = false)
