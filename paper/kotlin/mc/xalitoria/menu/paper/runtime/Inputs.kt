package mc.xalitoria.menu.paper.runtime

import com.xalitoria.util.message.PLAIN
import io.papermc.paper.event.player.AsyncChatEvent
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.config.MessageConfig
import net.xalitoria.menu.Action
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    enum class Verdict {
        ACCEPT, CANCEL, INVALID, TOO_LONG;

        /** Whether the reply is turned down and the prompt waits for another. */
        val retry: Boolean get() = this == INVALID || this == TOO_LONG
    }

    /** The chat, the dialog of newer clients, or an anvil's rename field. */
    enum class Channel { CHAT, DIALOG, ANVIL }

    /** What to tell the player about a reply turned down with [verdict], or null for nothing. */
    fun complaint(verdict: Verdict, messages: MessageConfig.Input = MessageConfig.Input()): String? = when (verdict) {
        Verdict.TOO_LONG -> messages.tooLong(maxLength)
        Verdict.INVALID -> invalid
        else -> null
    }

    fun verdict(reply: String): Verdict = when {
        reply.trim().equals(cancelWord, ignoreCase = true) -> Verdict.CANCEL
        reply.trim().length > maxLength -> Verdict.TOO_LONG
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

    /** Ends [prompt] if it is still the one waiting, closes what it showed, and runs its cancel actions (a tick later when [deferred]). @return whether it was */
    fun expire(prompt: Prompt, deferred: Boolean = false): Boolean {
        val player = prompt.ctx.player
        if (!waiting.remove(player.uniqueId, prompt)) return false
        dismiss(prompt)
        // From the event of a window closing, what the actions open can't be opened yet.
        if (deferred) player.scheduler.run(plugin, { prompt.ctx.run(prompt.cancelled) }, null) else prompt.ctx.run(prompt.cancelled)
        return true
    }

    /** Ends every prompt and takes away what they put on the players' screens, for when the plugin goes away. */
    fun stop() {
        var failed = 0
        for (prompt in waiting.values.toList()) runCatching { dismiss(prompt) }.onFailure { failed++ }
        waiting.clear()
        // Closing a window belongs to the player's own thread, which Folia keeps from a plugin that is going away.
        if (failed > 0) plugin.logger.warning("$failed question window(s) couldn't be closed while the plugin went away; they close when their players leave")
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
     * @return what the reply meant; a verdict that is a [Prompt.Verdict.retry] leaves the prompt waiting for another
     */
    fun submit(prompt: Prompt, reply: String): Prompt.Verdict {
        val player = prompt.ctx.player
        val verdict = prompt.verdict(reply)
        if (verdict.retry || !waiting.remove(player.uniqueId, prompt)) return verdict
        player.scheduler.run(plugin, {
            if (prompt.channel == Prompt.Channel.ANVIL) plugin.anvils.close(player)
            if (verdict == Prompt.Verdict.CANCEL) prompt.ctx.run(prompt.cancelled)
            else prompt.ctx.with(mapOf("input" to reply.trim())).run(prompt.then)
        }, null)
        return verdict
    }

    /** Tells the viewer of [prompt] that their reply was turned down, if there is anything to tell them. */
    fun reject(prompt: Prompt, verdict: Prompt.Verdict) {
        val player = prompt.ctx.player
        val text = prompt.complaint(verdict, plugin.messageConfig.data.input) ?: return
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
        val verdict = submit(prompt, reply)
        if (verdict.retry) reject(prompt, verdict)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        waiting.remove(event.player.uniqueId)
        plugin.anvils.close(event.player)
    }
}
