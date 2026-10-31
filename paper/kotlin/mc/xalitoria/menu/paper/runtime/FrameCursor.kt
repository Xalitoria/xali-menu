package mc.xalitoria.menu.paper.runtime

/** Where an animation of [count] frames is, and what comes after the frame that has just played. */
class FrameCursor(private val count: Int, private val loop: Boolean) {
    /** The frame that plays next, from 0. */
    var index = 0
        private set

    enum class Step { NEXT, WRAPPED, ENDED }

    /**
     * Moves past the frame that played. [Step.WRAPPED] is a loop starting again at the first frame, [Step.ENDED] the last
     * frame of an animation that does not loop (the cursor stays after it).
     */
    fun advance(): Step {
        if (index + 1 < count) {
            index++
            return Step.NEXT
        }
        if (!loop) return Step.ENDED
        index = 0
        return Step.WRAPPED
    }
}
