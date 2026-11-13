package mc.xalitoria.menu.paper.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Groups what comes in bursts: the first value opens a batch, everything that follows within [window] joins it (each once), and the
 * batch goes on when the window is over. Unlike `debounce` nothing is dropped, so what the batch is for (which variables changed) is
 * all there, and unlike `sample` an idle flow costs nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.batched(window: Duration): Flow<Set<T>> = channelFlow {
    val incoming = produce(capacity = kotlinx.coroutines.channels.Channel.UNLIMITED) { collect { send(it) } }
    while (true) {
        val batch = LinkedHashSet<T>()
        batch += incoming.receiveCatching().getOrNull() ?: break
        var open = true
        withTimeoutOrNull(window) {
            while (true) {
                val next = incoming.receiveCatching()
                if (next.isClosed) { open = false; break }
                batch += next.getOrThrow()
            }
        }
        send(batch)
        if (!open) break
    }
}
