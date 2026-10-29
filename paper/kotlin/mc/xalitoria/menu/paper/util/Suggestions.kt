package mc.xalitoria.menu.paper.util

import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import java.util.concurrent.CompletableFuture

/** Suggests the [names] that start with what has been typed so far, ignoring case. */
fun SuggestionsBuilder.suggestMatching(names: Collection<String>): CompletableFuture<Suggestions> {
    names.filter { it.startsWith(remaining, ignoreCase = true) }.forEach { suggest(it) }
    return buildFuture()
}
