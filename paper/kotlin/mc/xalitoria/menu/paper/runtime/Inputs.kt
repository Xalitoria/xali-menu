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
    /** Where the player types the reply. */
    val channel: Channel = Channel.CHAT,
    val title: String = "",
    val initial: String = "",
    val submit: String = "Confirm",
    val maxLength: Int = 256,
) {
    /** What a reply means to this prompt. */
    enum class Verdict { ACCEPT, CANCEL, INVALID }

    /** The chat, the dialog of newer clients, or an anvil's rename field. */
    enum class Channel { CHAT, DIALOG, ANVIL }

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
        waiting.put(prompt.ctx.player.uniqueId, prompt)?.let(::dismiss)
    }

    /** Ends [prompt] if it is still the one waiting, closes what it showed, and runs its cancel actions. @return whether it was */
    fun expire(prompt: Prompt): Boolean {
        if (!waiting.remove(prompt.ctx.player.uniqueId, prompt)) return false
        dismiss(prompt)
        prompt.ctx.run(prompt.cancelled)
        return true
    }

    /** Takes away what [prompt] put on the viewer's screen, if it is still there. */
    private fun dismiss(prompt: Prompt) {
        when (prompt.channel) {
            Prompt.Channel.CHAT -> {}
            Prompt.Channel.DIALOG -> prompt.ctx.player.closeDialog()
            Prompt.Channel.ANVIL -> plugin.anvils.close(prompt.ctx.player)
        }
    }

    /**
     * The viewer's [reply] to [prompt], from wherever they typed it and on any thread. A reply to a prompt that is no longer
     * the one waiting (it timed out, or was replaced) does nothing. What an accepted or cancelled reply triggers runs on the
     * player's own region.
     * @return what the reply meant; [Prompt.Verdict.INVALID] leaves the prompt waiting for another
     */
    fun submit(prompt: Prompt, reply: String): Prompt.Verdict {
        val player = prompt.ctx.player
        val verdict = prompt.verdict(reply)
        if (verdict == Prompt.Verdict.INVALID || !waiting.remove(player.uniqueId, prompt)) return verdict
        player.scheduler.run(plugin, {
            if (prompt.channel == Prompt.Channel.ANVIL) plugin.anvils.close(player)
            if (verdict == Prompt.Verdict.CANCEL) prompt.ctx.run(prompt.cancelled)
            else prompt.ctx.with(mapOf("input" to reply.trim())).run(prompt.then)
        }, null)
        return verdict
    }

    /** Tells the viewer of [prompt] that their reply was turned down. */
    fun reject(prompt: Prompt) {
        val player = prompt.ctx.player
        val text = prompt.invalid ?: return
        player.scheduler.run(plugin, { player.sendMessage(prompt.ctx.component(text)) }, null)
    }

    /** Whether [prompt] is still the one its viewer is answering. */
    fun waiting(prompt: Prompt): Boolean = waiting[prompt.ctx.player.uniqueId] === prompt

    /** What [player] is answering, if anything. */
    fun promptOf(player: Player): Prompt? = waiting[player.uniqueId]

    fun pending(player: Player): Boolean = waiting.containsKey(player.uniqueId)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val prompt = waiting[player.uniqueId]?.takeIf { it.channel == Prompt.Channel.CHAT } ?: return
        event.isCancelled = true
        val reply = PLAIN.serialize(event.originalMessage())
        if (submit(prompt, reply) == Prompt.Verdict.INVALID) reject(prompt)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        waiting.remove(event.player.uniqueId)
        plugin.anvils.close(event.player)
    }
}
