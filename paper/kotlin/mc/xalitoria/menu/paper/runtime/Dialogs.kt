package mc.xalitoria.menu.paper.runtime

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.event.player.PlayerCustomClickEvent
import net.kyori.adventure.key.Key
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import mc.xalitoria.menu.paper.XaliMenu

/** The text input of a client that has dialogs: a window with one text field, and a confirm and a cancel button. */
class Dialogs(private val plugin: XaliMenu) : Listener {
    /** Shows the dialog of [prompt] to its viewer, with [error] above the field when the last reply was turned down, and [initial] in it. */
    fun show(prompt: Prompt, error: String? = null, initial: String = prompt.initial) {
        val ctx = prompt.ctx
        val field = DialogInput.text(FIELD, ctx.component(prompt.title))
            .width(WIDTH).labelVisible(false).initial(ctx.parse(initial).take(prompt.maxLength)).maxLength(prompt.maxLength).build()
        val confirm = ActionButton.builder(ctx.component(prompt.submit)).width(BUTTON).action(
            DialogAction.customClick(CONFIRM, null),
        ).build()
        val cancel = ActionButton.builder(ctx.component(prompt.cancelWord)).width(BUTTON).action(
            DialogAction.customClick(CANCEL, null),
        ).build()
        val body = listOfNotNull(error?.let { DialogBody.plainMessage(ctx.component(it)) })
        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(DialogBase.builder(ctx.component(prompt.title)).canCloseWithEscape(true).body(body).inputs([field]).build())
                .type(DialogType.confirmation(confirm, cancel))
        }
        ctx.player.showDialog(dialog)
    }

    /**
     * A button of the dialog, found by the key it was made with rather than a callback (those are queued for a tick that
     * Folia may not run). The reply is the player's own current prompt's; a click with none waiting does nothing. One that is
     * turned down brings the dialog back with what was wrong.
     */
    @EventHandler
    fun onClick(event: PlayerCustomClickEvent) {
        val key = event.identifier
        if (key != CONFIRM && key != CANCEL) return
        val player = (event.commonConnection as? PlayerGameConnection)?.player ?: return
        val prompt = plugin.inputs.promptOf(player)?.takeIf { it.channel == Prompt.Channel.DIALOG } ?: return
        val reply = if (key == CANCEL) prompt.cancelWord else event.dialogResponseView?.getText(FIELD).orEmpty()
        val verdict = plugin.inputs.submit(prompt, reply)
        if (!verdict.retry) return
        player.scheduler.run(plugin, { if (plugin.inputs.waiting(prompt)) show(prompt, prompt.complaint(verdict, plugin.messageConfig.data.input) ?: plugin.messageConfig.data.input.notAccepted, reply) }, null)
    }

    private companion object {
        val CONFIRM = Key.key("xalimenu", "input/confirm")
        val CANCEL = Key.key("xalimenu", "input/cancel")
        const val FIELD = "text"
        const val WIDTH = 300
        const val BUTTON = 120
    }
}
