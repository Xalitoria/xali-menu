package mc.xalitoria.menu.paper.runtime

import io.papermc.paper.event.player.AsyncChatEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.xalitoria.menu.Action
import mc.xalitoria.menu.paper.XaliMenu
import com.xalitoria.util.message.PLAIN
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

/** What a player's next chat message is waiting to become: the prompt of an `input` action. */
class Prompt(
    val ctx: Context,
    val then: List<Action>,
    val cancelled: List<Action>,
    val cancelWord: String,
    val match: Regex?,
    val invalid: String?,
) {
    /** What a reply means to this prompt. */
    enum class Verdict { ACCEPT, CANCEL, INVALID }

    fun verdict(reply: String): Verdict = when {
        reply.trim().equals(cancelWord, ignoreCase = true) -> Verdict.CANCEL
        match != null && !match.matches(reply.trim()) -> Verdict.INVALID
        else -> Verdict.ACCEPT
    }
}

/**
 * The chat prompts of the `input` action: while a player has one, their next message is theirs to answer it, and is not
 * said in chat. The chat event comes off the main threads, so the answer is only looked at there; what it triggers runs
 * on the player's own region.
 */
class Inputs(private val plugin: XaliMenu) : Listener {
    private val waiting = ConcurrentHashMap<UUID, Prompt>()

    /** Starts [prompt] for the viewer of its context; one they had before is dropped. */
    fun ask(prompt: Prompt) {
        waiting[prompt.ctx.player.uniqueId] = prompt
    }

    /** Ends [prompt] if it is still the one waiting, and runs its cancel actions. @return whether it was */
    fun expire(prompt: Prompt): Boolean {
        if (!waiting.remove(prompt.ctx.player.uniqueId, prompt)) return false
        prompt.ctx.run(prompt.cancelled)
        return true
    }

    fun pending(player: Player): Boolean = waiting.containsKey(player.uniqueId)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val prompt = waiting[player.uniqueId] ?: return
        event.isCancelled = true
        val reply = PLAIN.serialize(event.originalMessage())
        when (prompt.verdict(reply)) {
            Prompt.Verdict.INVALID -> prompt.invalid?.let { text ->
                player.scheduler.run(plugin, { player.sendMessage(prompt.ctx.component(text)) }, null)
            }
            Prompt.Verdict.CANCEL -> {
                if (waiting.remove(player.uniqueId, prompt)) player.scheduler.run(plugin, { prompt.ctx.run(prompt.cancelled) }, null)
            }
            Prompt.Verdict.ACCEPT -> {
                if (waiting.remove(player.uniqueId, prompt)) {
                    player.scheduler.run(plugin, { prompt.ctx.with(mapOf("input" to reply.trim())).run(prompt.then) }, null)
                }
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        waiting.remove(event.player.uniqueId)
    }
}
