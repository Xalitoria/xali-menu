package mc.xalitoria.menu.paper.bedrock

import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.runtime.Context
import mc.xalitoria.menu.paper.runtime.LoadedMenu
import mc.xalitoria.menu.paper.runtime.Prompt
import mc.xalitoria.menu.paper.runtime.layoutPage
import mc.xalitoria.menu.paper.runtime.passes
import mc.xalitoria.menu.paper.util.bedrockText
import net.xalitoria.menu.model.BedrockButton
import net.xalitoria.menu.model.BedrockField
import net.xalitoria.menu.model.BedrockMenu
import net.xalitoria.menu.model.Item
import net.xalitoria.menu.model.Menu
import org.bukkit.entity.Player
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates native Bedrock Cumulus forms for menus, multi-click action pickers and `input` text prompts.
 *
 * Cumulus callbacks run on Geyser/Floodgate network threads, so every callback dispatches back onto the player's
 * Folia region thread via `player.scheduler.run` before evaluating gates or running actions.
 */
class BedrockForms(private val plugin: XaliMenu, val bridge: BedrockBridge?) {
    /** Active Bedrock menu form session for one player, used for pagination, refresh and `/xmenu inspect`. */
    class Session(
        val name: String,
        val target: LoadedMenu,
        val opened: Context,
        val presentation: Presentation,
        var page: Int = 0,
        @Volatile var pageValues: Map<String, String> = emptyMap(),
        var lastClick: Long = 0L,
    )

    private val sessions = ConcurrentHashMap<UUID, Session>()

    val available: Boolean get() = bridge != null

    val name: String? get() = bridge?.name

    fun session(player: Player): Session? = sessions[player.uniqueId]

    fun left(player: Player) {
        sessions.remove(player.uniqueId)
    }

    fun close(player: Player) {
        sessions.remove(player.uniqueId)
        bridge?.closeForm(player)
    }

    fun closeAll() {
        for (id in sessions.keys.toList()) {
            sessions.remove(id)
            plugin.server.getPlayer(id)?.let { runCatching { bridge?.closeForm(it) } }
        }
    }

    /** Re-opens the active Bedrock form for [player] so updated placeholders or gates take effect. */
    fun refresh(player: Player) {
        val current = sessions[player.uniqueId] ?: return
        showMenu(player, current, runOnOpen = false)
    }

    /** Turns the page of an active paged Bedrock form for [player]. */
    fun turn(player: Player, relative: Boolean, value: Int) {
        val current = sessions[player.uniqueId] ?: return
        if (current.target.menu.pages == null) return
        val next = if (relative) current.page.toLong() + value else value.toLong() - 1
        current.page = next.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        showMenu(player, current, runOnOpen = false)
    }

    /**
     * Opens [target] as a Bedrock form ([presentation]: `SimpleForm`, `ModalForm` or `CustomForm`).
     * Returns false if no Bedrock bridge is available or sending the form failed.
     */
    fun open(player: Player, target: LoadedMenu, ctx: Context, presentation: Presentation): Boolean {
        if (bridge == null) return false
        val session = Session(target.name, target, ctx, presentation)
        sessions[player.uniqueId] = session
        return showMenu(player, session, runOnOpen = true)
    }

    private fun showMenu(player: Player, session: Session, runOnOpen: Boolean): Boolean {
        val sent = when (session.presentation) {
            Presentation.SimpleForm -> showSimple(player, session)
            Presentation.ModalForm -> showModal(player, session)
            Presentation.CustomForm -> showCustom(player, session)
            else -> false
        }
        if (!sent) {
            sessions.remove(player.uniqueId, session)
            return false
        }
        if (runOnOpen) {
            debug("{0} opened {1} (Bedrock {2})", player.name, session.name, session.presentation::class.simpleName)
            plugin.actions.run(session.target.menu.onOpen, session.opened.with(session.pageValues))
        }
        return true
    }

    /** One clickable entry rendered in a Bedrock SimpleForm. */
    private sealed interface Entry {
        val spec: SimpleButtonSpec

        data class Explicit(override val spec: SimpleButtonSpec, val button: BedrockButton, val ctx: Context) : Entry
        data class MenuItem(override val spec: SimpleButtonSpec, val item: Item, val ctx: Context) : Entry
        data class TurnPage(override val spec: SimpleButtonSpec, val delta: Int) : Entry
    }

    private fun showSimple(player: Player, session: Session): Boolean {
        val bridge = bridge ?: return false
        val menu = session.target.menu
        val cfg = menu.bedrock ?: BedrockMenu()
        val defaults = plugin.config.data.bedrock
        val iconTemplate = defaults.iconUrl
        val stripPua = defaults.stripPrivateUseTitle

        var pageCtx = session.opened
        val entries = mutableListOf<Entry>()

        if (cfg.buttons.isNotEmpty()) {
            for (button in cfg.buttons) {
                if (!button.view.passes(pageCtx, run = false)) continue
                val label = pageCtx.bedrockText(button.text, stripPua)
                val icon = BedrockIcons.resolve(null, button.image?.let(pageCtx::parse), iconTemplate)
                entries += Entry.Explicit(SimpleButtonSpec(label, icon), button, pageCtx)
            }
        } else {
            val paged = menu.pages
            var hasPrev = false
            var hasNext = false
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Item, Boolean>())
            val pagedSlots = paged?.listed()?.toSet().orEmpty()

            if (paged != null) {
                val listed = runCatching { paged.catalog.elements(session.opened) }.getOrElse { emptyList() }
                val layout = layoutPage(listed, paged.listed(), paged.sort, paged.descending, session.page) {
                    paged.item?.view.passes(session.opened.with(it), run = false)
                }
                session.page = layout.state.page
                session.pageValues = layout.state.values()
                pageCtx = session.opened.with(session.pageValues)
                hasPrev = layout.state.page > 0
                hasNext = layout.state.page + 1 < layout.state.pages

                if (layout.empty) {
                    paged.empty?.let { emptyItem ->
                        if (emptyItem.bedrock?.hidden != true) {
                            entries += entryForItem(emptyItem, pageCtx, cfg, iconTemplate, stripPua)
                        }
                    }
                } else {
                    val templates = paged.templates()
                    for ([slot, element] in layout.placed) {
                        val elCtx = pageCtx.with(element)
                        val template = templates.getValue(slot)
                        if (template.bedrock?.hidden == true) continue
                        if (template !== paged.item && !template.view.passes(elCtx, run = false)) continue
                        entries += entryForItem(template, elCtx, cfg, iconTemplate, stripPua)
                    }
                }
            }

            // Include non-paged menu items (skipping slots owned by paged elements and deduplicating multi-slot items).
            val orderedSlots = session.target.bySlot.keys.sorted()
            for (slot in orderedSlots) {
                if (slot in pagedSlots) continue
                val item = session.target.bySlot[slot]?.firstOrNull { it.second.view.passes(pageCtx, run = false) }?.second ?: continue
                if (!seen.add(item)) continue
                if (cfg.skipFillers && BedrockPlanner.isFiller(item)) continue
                if (item.bedrock?.hidden == true) continue
                entries += entryForItem(item, pageCtx, cfg, iconTemplate, stripPua)
            }

            if (hasPrev) {
                entries += Entry.TurnPage(SimpleButtonSpec(pageCtx.bedrockText(cfg.prevButton, stripPua), BedrockIcons.resolve("arrow", null, iconTemplate)), -1)
            }
            if (hasNext) {
                entries += Entry.TurnPage(SimpleButtonSpec(pageCtx.bedrockText(cfg.nextButton, stripPua), BedrockIcons.resolve("arrow", null, iconTemplate)), 1)
            }
        }

        val title = pageCtx.bedrockText(cfg.title?.takeIf { it.isNotBlank() } ?: menu.title, stripPua)
        val content = if (cfg.content.isNotBlank()) pageCtx.bedrockText(cfg.content, stripPua) else ""
        val form = SimpleFormSpec(title, content, entries.map { it.spec })

        return bridge.sendSimple(
            player = player,
            form = form,
            onSelect = { index ->
                player.scheduler.run(plugin, {
                    if (sessions[player.uniqueId] !== session) return@run
                    if (!canClick(session)) return@run
                    val entry = entries.getOrNull(index) ?: return@run
                    when (entry) {
                        is Entry.TurnPage -> turn(player, relative = true, value = entry.delta)
                        is Entry.Explicit -> {
                            sessions.remove(player.uniqueId, session)
                            if (entry.button.require.passes(entry.ctx, run = true)) {
                                plugin.actions.run(entry.button.actions, entry.ctx)
                            }
                        }
                        is Entry.MenuItem -> {
                            val choices = BedrockPlanner.choicesFor(entry.item)
                            if (choices.isEmpty()) {
                                sessions.remove(player.uniqueId, session)
                            } else if (choices.size == 1 || !cfg.actionPicker) {
                                val chosen = choices.first()
                                // Keep session alive if the action only turns pages or refreshes.
                                val keepsForm = chosen.handler.actions.any {
                                    it is mc.xalitoria.menu.paper.action.Actions.Page || it is mc.xalitoria.menu.paper.action.Actions.Refresh
                                }
                                if (!keepsForm) sessions.remove(player.uniqueId, session)
                                if (chosen.handler.require.passes(entry.ctx, run = true)) {
                                    plugin.actions.run(chosen.handler.actions, entry.ctx)
                                }
                            } else {
                                sessions.remove(player.uniqueId, session)
                                showActionPicker(player, entry.item, entry.ctx, choices)
                            }
                        }
                    }
                }, null)
            },
            onClose = {
                player.scheduler.run(plugin, {
                    if (sessions.remove(player.uniqueId, session)) {
                        plugin.actions.run(cfg.onClose, pageCtx)
                        plugin.actions.run(menu.onClose, pageCtx)
                    }
                }, null)
            },
        )
    }

    private fun entryForItem(item: Item, ctx: Context, cfg: BedrockMenu, iconTemplate: String, stripPua: Boolean): Entry.MenuItem {
        val rawOverride = item.bedrock?.text?.let { ctx.bedrockText(it, stripPua) }
        val rawName = item.name?.let { ctx.bedrockText(it, stripPua) }
        val firstLore = item.lore.firstOrNull { it.isNotBlank() }?.let { ctx.bedrockText(it, stripPua) }
        val material = ctx.parse(item.material)
        val label = BedrockPlanner.buttonLabel(rawOverride, rawName, material, firstLore, cfg.includeLore)
        val icon = BedrockIcons.resolve(material, item.bedrock?.image?.let(ctx::parse), iconTemplate)
        return Entry.MenuItem(SimpleButtonSpec(label, icon), item, ctx)
    }

    private fun showModal(player: Player, session: Session): Boolean {
        val bridge = bridge ?: return false
        val menu = session.target.menu
        val cfg = menu.bedrock ?: BedrockMenu()
        val ctx = session.opened
        val stripPua = plugin.config.data.bedrock.stripPrivateUseTitle

        val confirm = cfg.confirm
        val cancel = cfg.cancel
        val title = ctx.bedrockText(cfg.title?.takeIf { it.isNotBlank() } ?: menu.title, stripPua)
        val content = if (cfg.content.isNotBlank()) ctx.bedrockText(cfg.content, stripPua) else ""
        val form = ModalFormSpec(
            title = title,
            content = content,
            button1 = ctx.bedrockText(confirm?.text ?: "&aConfirm", stripPua),
            button2 = ctx.bedrockText(cancel?.text ?: "&cCancel", stripPua),
        )

        return bridge.sendModal(
            player = player,
            form = form,
            onChoose = { first ->
                player.scheduler.run(plugin, {
                    if (!sessions.remove(player.uniqueId, session)) return@run
                    val choice = if (first) confirm else cancel
                    if (choice != null && choice.require.passes(ctx, run = true)) {
                        plugin.actions.run(choice.actions, ctx)
                    }
                }, null)
            },
            onClose = {
                player.scheduler.run(plugin, {
                    if (sessions.remove(player.uniqueId, session)) {
                        plugin.actions.run(cfg.onClose, ctx)
                        plugin.actions.run(menu.onClose, ctx)
                    }
                }, null)
            },
        )
    }

    private fun showCustom(player: Player, session: Session): Boolean {
        val bridge = bridge ?: return false
        val menu = session.target.menu
        val cfg = menu.bedrock ?: BedrockMenu()
        val ctx = session.opened
        val stripPua = plugin.config.data.bedrock.stripPrivateUseTitle

        val specs = mutableListOf<CustomFieldSpec>()
        val bindings = mutableListOf<BedrockField>()
        for (field in cfg.fields) {
            when (field) {
                is BedrockField.Label -> specs += CustomFieldSpec.Label(ctx.bedrockText(field.text, stripPua))
                is BedrockField.Input -> {
                    specs += CustomFieldSpec.Input(
                        ctx.bedrockText(field.text, stripPua),
                        ctx.bedrockText(field.placeholder, stripPua),
                        ctx.parse(field.default),
                    )
                    bindings += field
                }
                is BedrockField.Toggle -> {
                    specs += CustomFieldSpec.Toggle(ctx.bedrockText(field.text, stripPua), field.default)
                    bindings += field
                }
                is BedrockField.Dropdown -> {
                    val options = field.options.map { ctx.bedrockText(it, stripPua) }
                    specs += CustomFieldSpec.Dropdown(ctx.bedrockText(field.text, stripPua), options, field.default.coerceIn(0, options.lastIndex))
                    bindings += field
                }
                is BedrockField.Slider -> {
                    val def = (field.default ?: field.min).coerceIn(field.min, field.max)
                    specs += CustomFieldSpec.Slider(ctx.bedrockText(field.text, stripPua), field.min, field.max, field.step, def)
                    bindings += field
                }
                is BedrockField.StepSlider -> {
                    val steps = field.steps.map { ctx.bedrockText(it, stripPua) }
                    specs += CustomFieldSpec.StepSlider(ctx.bedrockText(field.text, stripPua), steps, field.default.coerceIn(0, steps.lastIndex))
                    bindings += field
                }
            }
        }

        val title = ctx.bedrockText(cfg.title?.takeIf { it.isNotBlank() } ?: menu.title, stripPua)
        return bridge.sendCustom(
            player = player,
            form = CustomFormSpec(title, specs),
            onSubmit = { rawValues ->
                player.scheduler.run(plugin, {
                    if (!sessions.remove(player.uniqueId, session)) return@run
                    if (!player.isOnline || plugin.menus.menu(session.name)?.menu !== menu) return@run
                    if (!canSubmitCustom(menu, ctx)) {
                        menu.open?.let { plugin.actions.run(it.deny, ctx) }
                        return@run
                    }
                    val scope = bindCustomValues(bindings, rawValues, ctx)
                    plugin.actions.run(cfg.onSubmit, ctx.with(scope))
                }, null)
            },
            onClose = {
                player.scheduler.run(plugin, {
                    if (sessions.remove(player.uniqueId, session)) {
                        plugin.actions.run(cfg.onClose, ctx)
                        plugin.actions.run(menu.onClose, ctx)
                    }
                }, null)
            },
        )
    }

    /**
     * Opens a follow-up SimpleForm letting a Bedrock player pick among multiple click handlers on [item]
     * (for example, `Left click` to buy vs `Right click` to sell).
     */
    fun showActionPicker(player: Player, item: Item, ctx: Context, choices: List<ClickChoice>): Boolean {
        val bridge = bridge ?: return false
        val stripPua = plugin.config.data.bedrock.stripPrivateUseTitle
        val title = item.name?.let { ctx.bedrockText(it, stripPua) }?.ifBlank { null } ?: BedrockPlanner.humanMaterial(ctx.parse(item.material))
        val body = item.lore.joinToString("\n") { ctx.bedrockText(it, stripPua) }
        val buttons = choices.map { SimpleButtonSpec(ctx.bedrockText(it.label, stripPua)) }
        return bridge.sendSimple(
            player = player,
            form = SimpleFormSpec(title, body, buttons),
            onSelect = { index ->
                player.scheduler.run(plugin, {
                    val chosen = choices.getOrNull(index) ?: return@run
                    if (chosen.handler.require.passes(ctx, run = true)) {
                        plugin.actions.run(chosen.handler.actions, ctx)
                    }
                }, null)
            },
            onClose = {},
        )
    }

    /**
     * Shows a native Bedrock `CustomForm` for an `input` action [prompt], with optional validation [error] above the
     * text field and [initial] pre-filled.
     */
    fun showInput(prompt: Prompt, error: String? = null, initial: String = prompt.initial): Boolean {
        val bridge = bridge ?: return false
        val ctx = prompt.ctx
        val player = ctx.player
        val stripPua = plugin.config.data.bedrock.stripPrivateUseTitle
        val fields = buildList {
            if (!error.isNullOrBlank()) add(CustomFieldSpec.Label("§c${ctx.bedrockText(error, stripPua)}"))
            add(CustomFieldSpec.Input(ctx.bedrockText(prompt.title, stripPua), "", ctx.parse(initial).take(prompt.maxLength)))
        }
        return bridge.sendCustom(
            player = player,
            form = CustomFormSpec(ctx.bedrockText(prompt.title, stripPua), fields),
            onSubmit = { values ->
                val reply = values.firstOrNull().orEmpty()
                val verdict = plugin.inputs.submit(prompt, reply)
                if (verdict.retry) {
                    player.scheduler.run(plugin, {
                        if (plugin.inputs.waiting(prompt)) {
                            val reason = prompt.complaint(verdict, plugin.messageConfig.data.input) ?: plugin.messageConfig.data.input.notAccepted
                            showInput(prompt, reason, reply)
                        }
                    }, null)
                }
            },
            onClose = {
                player.scheduler.run(plugin, { plugin.inputs.expire(prompt) }, null)
            },
        )
    }

    private fun canClick(session: Session): Boolean {
        val now = System.nanoTime()
        if (session.lastClick != 0L && now - session.lastClick < plugin.config.data.clickCooldown * 1_000_000) return false
        session.lastClick = now
        return true
    }

    companion object {
        /** Re-checks the menu's opening authorization gate before running `CUSTOM` form `on-submit` actions. */
        fun canSubmitCustom(menu: Menu, ctx: Context): Boolean = menu.open.passes(ctx, run = false)

        /** Maps submitted values of [fields] to `{id}` (and `{id}_index` for dropdowns and step sliders), validating bounds. */
        fun bindCustomValues(fields: List<BedrockField>, rawValues: List<String>, parse: (String) -> String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            fields.forEachIndexed { index, field ->
                val raw = rawValues.getOrNull(index).orEmpty()
                when (field) {
                    is BedrockField.Input -> out[field.id] = raw
                    is BedrockField.Toggle -> out[field.id] = (raw.trim().lowercase().toBooleanStrictOrNull() ?: field.default).toString()
                    is BedrockField.Slider -> out[field.id] = normalizeSlider(field, raw)
                    is BedrockField.Dropdown -> {
                        val chosen = raw.toIntOrNull()?.coerceIn(0, field.options.lastIndex) ?: 0
                        out[field.id] = parse(field.options[chosen])
                        out["${field.id}_index"] = chosen.toString()
                    }
                    is BedrockField.StepSlider -> {
                        val chosen = raw.toIntOrNull()?.coerceIn(0, field.steps.lastIndex) ?: 0
                        out[field.id] = parse(field.steps[chosen])
                        out["${field.id}_index"] = chosen.toString()
                    }
                    is BedrockField.Label -> Unit
                }
            }
            return out
        }

        private fun normalizeSlider(field: BedrockField.Slider, raw: String): String {
            val min = BigDecimal(field.min.toString())
            val max = BigDecimal(field.max.toString())
            val step = BigDecimal(field.step.toString())
            val fallback = BigDecimal((field.default ?: field.min).coerceIn(field.min, field.max).toString())
            val parsed = raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { runCatching { BigDecimal(raw.trim()) }.getOrNull() } ?: fallback
            val clamped = parsed.coerceIn(min, max)
            val steps = clamped.subtract(min).divide(step, 0, RoundingMode.HALF_UP)
            val snapped = min.add(steps.multiply(step)).coerceIn(min, max)
            val normalized = snapped.stripTrailingZeros()
            return (if (normalized.scale() < 0) normalized.setScale(0) else normalized).toPlainString()
        }

        private fun bindCustomValues(fields: List<BedrockField>, rawValues: List<String>, ctx: Context): Map<String, String> =
            bindCustomValues(fields, rawValues, ctx::parse)
    }
}
