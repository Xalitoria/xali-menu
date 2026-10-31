package mc.xalitoria.menu.api.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable
import mc.xalitoria.menu.api.Action

/**
 * Frames that play over a menu once it is open, one after the other: each [Frame] waits for its `delay`, then draws its
 * items on top of the menu. A [loop] starts again from the menu as it is, without what the frames had drawn.
 * [onStart] runs when the menu has opened, [onEnd] when the last frame has played (never for a loop).
 */
@Serializable
data class Animation(
    val frames: List<Frame>,
    val loop: Boolean = false,
    val onStart: List<Action> = [],
    val onEnd: List<Action> = [],
) {
    init {
        require(frames.isNotEmpty()) { "an animation needs at least one frame" }
    }
}

/**
 * One step of an [Animation]. After [delay] (counted from the frame before, at least one tick) its [items] are drawn;
 * with [clear], what the frames before it drew goes first, so it replaces them, and without it the items stay on top.
 * A frame whose [require] gate does not hold at that moment is skipped. A slot that an item of the frame claims shows
 * that item instead of the menu's own, as long as the item's `view` gate passes.
 */
@Serializable
data class Frame(
    @Serializable(with = DurationAsString::class)
    val delay: Duration = 1.seconds,
    val clear: Boolean = true,
    val require: Gate? = null,
    val onStart: List<Action> = [],
    val onEnd: List<Action> = [],
    val items: Map<String, Item> = emptyMap(),
)
