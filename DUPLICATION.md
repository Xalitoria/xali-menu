# Inventory-menu duplication glitches

Research notes for whoever audits or hardens XaliMenu's click handling next: the known ways that a menu (a custom inventory window on a Bukkit
server) has been made to duplicate, lose or hand out items, what each one needs, and where XaliMenu stands against it. Nothing here changes the
plugin; the [findings](#findings-in-xalimenu-today) and the [test matrix](#test-matrix) are the work that follows from it.

Written 2026-11-27 against XaliMenu 0.6.0 on Paper and Folia 26.x. Read [`README.md`](README.md) ("Slots that take an item", "How a menu behaves")
for what the plugin promises, and [`CONTRIBUTING.md`](CONTRIBUTING.md) for how to test a change.

## Contents

- [How far to trust this page](#how-far-to-trust-this-page)
- [Vocabulary](#vocabulary)
- [How XaliMenu handles a click today](#how-xalimenu-handles-a-click-today)
- [The catalog](#the-catalog): [A](#a-clicks-that-a-plugin-forgot-to-cancel), [B](#b-event-order-and-the-life-of-a-window), [C](#c-one-gesture-run-twice), [D](#d-windows-with-logic-of-their-own), [E](#e-the-client-and-the-protocol), [F](#f-bedrock-and-geyser), [G](#g-persistence-and-crashes), [H](#h-values-that-a-menu-can-be-made-to-create)
- [Findings in XaliMenu today](#findings-in-xalimenu-today)
- [Test matrix](#test-matrix)
- [Hardening backlog](#hardening-backlog)
- [Out of scope](#out-of-scope)
- [Sources](#sources)

## How far to trust this page

The research was done with web search and page fetches on 2026-11-27. It is thinner than the title suggests, for two reasons:

- **Spigot's Jira refused every automated fetch (HTTP 403).** Every `SPIGOT-` ticket below is known only from a search-result summary, which is
  model-written and can be wrong in detail. The tickets are named so that they can be opened by hand.
- **Most reports are old.** The bulk of the Bukkit-level reports are from 1.8 to 1.17. Paper has changed inventory handling since, so an old
  report is a hypothesis to test on the current server, not a fact about it.

Each entry carries one of these evidence tags:

| Tag | Meaning |
|---|---|
| **read** | The page was fetched and read for this write-up. |
| **snippet** | Known from a search-result summary only; the page was not opened. |
| **knowledge** | Not sourced here. It comes from general knowledge of the Bukkit and vanilla inventory protocol and should be checked before it is relied on. |

Entries marked **Status: open** in XaliMenu mean that the code gives no reason to believe the problem is handled, or that the answer depends on a
real server. They are not confirmed bugs. Nothing below was reproduced against XaliMenu.

Search results also surfaced public exploit tooling (dupe modules for cheat clients, "dupe utility" plugins). None of it was opened or is endorsed
here. It is a reminder that a hole that is published gets used within days.

## Vocabulary

- **Top** and **bottom** inventory: the menu's own slots, and the player's inventory rows shown under them. `InventoryClickEvent.clickedInventory`
  says which one was clicked; `null` is a click outside the window.
- **Cursor**: the stack the player holds with the mouse. It is part of the player's state, not of the window.
- **Cancel**: `event.isCancelled = true`, which in Bukkit is `event.result = DENY`. The server then resends the affected slots and the cursor to the
  client. Cancelling only stops the *server's* change; whether the *client* ends up showing the truth depends on that resend.
- **Ghost item**: an item that the client shows and the server does not have. It looks like a duplicate and, until the next resync, can be clicked
  like one. Most "dupe" reports on forums are ghost items; a few are real.
- **Real dupe**: the server holds two stacks where there was one (or one stack and a reward that was meant to cost it).
- **Action replay**: one player gesture runs a menu's actions more than once. It duplicates the *reward* of an action, not an item.

## How XaliMenu handles a click today

Read from the code, not tested. File paths are under `paper/kotlin/mc/xalitoria/menu/paper/`.

1. **Everything is cancelled first.** `runtime/MenuListener.kt:22` (`onClick`, priority `HIGHEST`) cancels *every* click in a window whose holder
   is a `MenuView`, before it looks at the click type, the clicked inventory or the player. The only exception is described in step 3.
2. **Drags are cancelled** for the same holder (`MenuListener.kt:109`).
3. **Deposit slots (`accept`) let some clicks through.** With a slot that takes an item on screen, a `LEFT` or `RIGHT` click in the *bottom*
   inventory gets its original result back (`MenuListener.kt:34`-`37`), so a player can pick items up to the cursor. Every other click type stays
   cancelled. A click on the slot itself, or a shift-click in the bottom inventory, is turned into an `Offer`: the item is read from the server's
   own state, taken first, and given back if the slot's actions do not run to their end (`runtime/Deposits.kt`, `reserving`).
4. **Handlers.** After the checks, `handler()` (`MenuListener.kt:95`) picks the handler for `LEFT`, `RIGHT`, `SHIFT_LEFT`, `SHIFT_RIGHT` or
   `MIDDLE`. **Any other click type** (`DOUBLE_CLICK`, `NUMBER_KEY`, `DROP`, `CONTROL_DROP`, `SWAP_OFFHAND`, `CREATIVE`, `UNKNOWN`) gets `null`
   and falls back to the item's `click.any` handler (`MenuListener.kt:105`).
5. **Rate limit.** `click-cooldown` (default 150 ms, `config/MainConfig.kt:16`) is kept in `MenuView.lastClick` (`runtime/MenuView.kt:87`),
   per open view. A value of 0 means "no click yet", so the first click of a new view is never limited.
6. **Opening and closing are deferred.** `MenuManager.open(player, name, args)` schedules the real open on the player's scheduler
   (`runtime/MenuManager.kt:419`); the `close` action does the same (`runtime/ActionRunner.kt:43`); the Bedrock action picker closes through the
   scheduler too. Nothing opens or closes a window inside the click event, which is what the Paper javadoc asks for (see [B1](#b-event-order-and-the-life-of-a-window)).
7. **A menu that draws in the player's inventory never touches it.** The items exist only in rewritten window packets
   (`screen/Screens.kt`, `PacketEventsBackend`, `ProtocolLibBackend`); the server's inventory keeps the real items. `Screens.end` resends the
   inventory after the window goes (`Screens.kt:122`). The live test `live/bot/inventory.js` with `XM_MODE=saved` reads saved player data to prove it.
8. **The text-input anvil marks its paper** with a persistent-data key `xalimenu:anvil_input`, clears the window on close, removes the paper from
   drops and pickups, and sweeps the player's inventory, cursor and ender chest on join and after any anvil closes (`runtime/Anvils.kt:75`).
9. **Menus never hold real items.** The window's slots are display items from templates or item sources; `accept` takes an item *from* the
   player and never stores it in the window. This removes a whole family of dupes (see [B3](#b-event-order-and-the-life-of-a-window) and [G](#g-persistence-and-crashes)).

What no test covers: `paper/test` has no test that names `ClickType`, `InventoryAction` or `InventoryClickEvent`. The live bots
(`live/bot/deposit.js`, `inventory.js`) use plain clicks (`clickWindow(slot, 0, 0)`) and shift-clicks (`clickWindow(slot, 0, 1)`) only. Number
keys, offhand swaps, drops, double-clicks, drags and creative-mode clicks have never been driven against the plugin.

## The catalog

Each entry says what happens, the evidence, XaliMenu's status and, where it helps, how to try it. The click mode numbers are the vanilla window
click packet's `mode`, which is what mineflayer's `bot.clickWindow(slot, mouseButton, mode)` takes (see [Test matrix](#test-matrix)).

**Status** is one of *covered* (the code handles it by construction), *partly*, *open* (not handled, or depends on a real server) or *n/a*.

### A. Clicks that a plugin forgot to cancel

A plugin that cancels only clicks on its own slots, or only some click types, lets the rest do what vanilla does to the window. XaliMenu cancels
before it looks, so most of this group is *covered*; it is here because it is the largest family of reports and the checklist for an audit.

- **A1. Shift-click from the player's inventory into the menu** (`MOVE_TO_OTHER_INVENTORY`, `SHIFT_LEFT`/`SHIFT_RIGHT`, mode 1). A handler that
  tests `clickedInventory == topInventory` ignores it, and the item lands in the menu or, with a cancelled click, appears in both. *Evidence:*
  snippet (Spigot threads 179948 and 382121, and the "chest that cancels clicks" report below). *Status:* covered (`MenuListener.kt:26`); the
  deposit path is the one place where it is meant to move something and is read from server state.
- **A2. Double-click "collect to cursor"** (`COLLECT_TO_CURSOR`, `DOUBLE_CLICK`, mode 6, sent *after* a plain pickup). It gathers every stack of
  the same material from **both** inventories, wherever the click was, so a guard that checks only the clicked slot misses a double-click that
  starts in the player's own inventory and pulls items out of the menu. *Evidence:* read (joshymc-plugin PR #849: trade, giveaway editor and sell
  GUIs; the author notes the fix was not tested on a live client). *Status:* covered; with `accept` slots a `LEFT` pickup in the bottom inventory
  is allowed but the following `DOUBLE_CLICK` is not (it is not in `OFFERING`, `MenuListener.kt:91`).
- **A3. Shift-double-click fires one event per matching stack.** `ClickType.DOUBLE_CLICK` is reported only for a non-shift double-click; with
  shift held the client sends one shift-click per slot that holds the same item, so a handler can run dozens of times from one gesture. *Evidence:*
  snippet (Spigot thread 478897; SPIGOT-5672 appeared for the same query and was not opened). *Status:* partly: cancelled, and `click-cooldown`
  drops the burst, but see [finding F1](#findings-in-xalimenu-today) for the handler side.
- **A4. Number-key swap** (`HOTBAR_SWAP`, `NUMBER_KEY`, mode 2, button 0 to 8). Swaps the clicked slot with a hotbar slot. Cancelled correctly,
  it can leave a ghost item in the hotbar until a resync. *Evidence:* snippet (SPIGOT-6145 and SPIGOT-6291 below). *Status:* covered for the
  server; client resync is **open**.
- **A5. Offhand swap (F key)** (`SWAP_OFFHAND`, mode 2, button 40). Reported on 1.16.4: cancelling the click did not stop the item being copied
  into the offhand, and a ghost copy stayed in the menu. SPIGOT-6145 and PLUG-409 were closed *Invalid*; SPIGOT-6291 was closed as a duplicate.
  The workaround in the thread is to resend the offhand slot on the next tick. *Evidence:* snippet. *Status:* **open** (never tried on 26.x).
- **A6. Drop keys over a menu item** (`DROP`, `CONTROL_DROP`, mode 4). If the slot held a real stack, an uncancelled drop would throw it into
  the world. `DROP_ALL_CURSOR`/`DROP_ONE_CURSOR` (a click outside the window, `WINDOW_BORDER_LEFT/RIGHT`) throw the cursor. *Evidence:* knowledge.
  *Status:* covered (cancelled); XaliMenu's display items are not real stacks anyway.
- **A7. Drag across slots** (`InventoryDragEvent`, mode 5 in three phases). It is a different event from `InventoryClickEvent`; a plugin that
  handles only the click lets a drag spread the cursor over menu slots. *Evidence:* knowledge; Paper's javadoc describes the event
  (cancelling means none of the planned changes apply); SPIGOT-1299 (2015, snippet) says a cancelled drag cannot also change the cursor.
  *Status:* covered (`MenuListener.kt:109`); the cursor after a cancelled drag has not been looked at.
- **A8. Middle-click and creative-mode clicks** (`CLONE_STACK`, `MIDDLE`, mode 3; `CREATIVE`, `InventoryCreativeEvent`). In creative mode the
  client is authoritative over its own inventory, so cancelling does not always stop the client from keeping what it did. *Evidence:* snippet:
  SPIGOT-6346 (2021) says a cancelled click in creative mode leaves the item in the slot **and** puts it on the cursor, and a maintainer said it
  probably cannot be fixed; SPIGOT-2816 (2016, 1.11, resolved) is the same symptom; SPIGOT-8007 (1.21.4) says cancelling a creative click crashed
  the vanilla client. The Paper javadoc says `InventoryCreativeEvent` is a subclass of `InventoryClickEvent` (read). *Status:* **open**: see
  [F2](#findings-in-xalimenu-today); this matters most where a menu shows real items (XaliAuctions' `xaliauctions:` item source).
- **A9. Bundle actions** (`PICKUP_FROM_BUNDLE`, `PICKUP_ALL_INTO_BUNDLE`, `PICKUP_SOME_INTO_BUNDLE`, `PLACE_FROM_BUNDLE`, `PLACE_ALL_INTO_BUNDLE`,
  `PLACE_SOME_INTO_BUNDLE`). A guard that lists the `InventoryAction`s it knows about (an allow- or deny-list) misses these newer ones; one that
  cancels on any action does not. *Evidence:* read (the action list is in the Paper 1.21.11 javadoc: 25 actions). *Status:* covered (no list).
- **A10. Unknown future click types.** `ClickType.UNKNOWN` and `InventoryAction.UNKNOWN` exist, and the javadoc says UNKNOWN "should never be
  relied upon". Deny by default is the only safe rule. *Evidence:* read. *Status:* covered.
- **A11. Another plugin un-cancels.** A later handler at the same priority, or a plugin that sets `result = ALLOW`, re-enables the vanilla move.
  *Evidence:* knowledge. *Status:* partly: `HIGHEST` is the last priority before `MONITOR`, but it is not the last word among handlers at
  `HIGHEST`, and nothing watches at `MONITOR` for a menu click that ended up allowed.
- **A12. Which inventory the event names.** `event.inventory` is the window's top inventory even when the click was in the bottom one, and
  `clickedInventory` says where the click was. A handler must identify *its* window by the first and apply slot rules by the second.
  *Evidence:* knowledge. *Status:* covered (the holder check is on `event.inventory`, the slot rules on `clickedInventory`).

### B. Event order and the life of a window

- **B1. Opening or closing a window inside the click handler.** The Paper javadoc for `InventoryClickEvent` says the event fires in the middle of
  an inventory modification, that view-changing methods (`HumanEntity.closeInventory()`, `openInventory(...)`, `InventoryView.close()`) must not
  be called from its handler, and that they should be scheduled. Done inline, the cursor is returned to the inventory and then vanilla keeps
  processing the same click against a window that has changed. *Evidence:* read (javadoc, 1.21.11). *Status:* covered (step 6 above).
- **B2. Close right after a cancelled click.** Shift-click in a menu whose clicks are cancelled, then close at once with Escape, leaves a ghost
  item; easier at high ping. *Evidence:* snippet (SPIGOT-6727, 2021). *Status:* **open**. XaliMenu resends the inventory on close only for a
  menu that draws in it (`Screens.end`), not for an ordinary chest menu.
- **B3. A close handler and a cancel path both return the same snapshot.** The pattern that duplicates in GUIs that *hold* real items (trade,
  listing editor, kit editor, collect window): a player cancels after proceeding, the cancel path returns the held item, then the close event
  returns it again. The fix was to settle each snapshot exactly once. *Evidence:* snippet (DonutOrders PR #6, "Fix delivery item duplication on
  cancel after proceed"). *Status:* n/a for XaliMenu itself (it holds nothing); **applies to any extension or addon that stores items in a
  menu** (`live/extension/EssentialsKitEditorExtension.java` is the nearest case) and to XaliAuctions' draft listing.
- **B4. The cursor on open and close.** Opening a window returns the cursor's item to the inventory. A reward given and then an `open` that
  returns the item leaves the player with both. *Evidence:* knowledge; the same reasoning is in `runtime/Deposits.kt` (`reserving`,
  `afterSettling`). *Status:* covered for deposits (taken first, an `open` is deferred until the deposit settles). Not relevant to ordinary
  buttons, which never touch the cursor.
- **B5. Cancelled click that deletes the cursor item.** SPIGOT-2250 (snippet, unresolved when seen) and Bukkit threads 472034 and 308621
  report the cursor item vanishing, or a "duplicate" that is really a ghost, after a cancel; the usual advice is `player.updateInventory()`.
  *Evidence:* snippet. *Status:* **open**, as A4 and B2.
- **B6. Two views at once.** Opening a second menu while the first is open must not run the first's `on-close` as if the player had closed it.
  XaliMenu registers the new view first so the old window's close event finds a different view and does nothing (`MenuManager.kt:483`).
  *Evidence:* the code comment. *Status:* covered.

### C. One gesture run twice

These duplicate the reward of an action, not an item, and they are the cause of most "free purchase" reports.

- **C1. The client sends two clicks for a double-click.** A purchase on right-click ran twice in every plugin; the Spigot maintainer closed
  SPIGOT-1658 (2016) as a duplicate and *won't fix*, because the client really sends the second click. *Evidence:* snippet. *Status:* partly:
  see [F1](#findings-in-xalimenu-today).
- **C2. Click spam on a confirm or buy button.** A buy/sell GUI where "if you are fast enough, you can get another one of the item for free"
  (ChestCommands report); DonutOrders v2.8 fixed duplication in a collect GUI under rapid clicks; Order and Auction GUI 2.0.9 added a per-order
  lock; a DonutShop release fixed confirm spam buying or charging twice. The fixes are per-player or per-order locks, a cooldown, or one guarded
  database statement, not anything Folia-specific. *Evidence:* snippet (vendor changelogs). *Status:* partly: sequential per player on the region
  thread, plus the cooldown; the protection stops at the plugin's border (the action list is not transactional).
- **C3. Two players, one listing.** Both click "buy" in the same tick on different region threads. *Evidence:* snippet (DonutOrders v2.8 "duplicate
  orders can't be accepted by multiple players at once"). *Status:* out of the menu's hands: XaliAuctions' and XaliEco's rule is one guarded
  `UPDATE ... WHERE state = ...` (see `AGENTS.md`). A menu only has to make sure its `open`/`command` actions carry the listing id and not a slot.
- **C4. Resetting the cooldown by reopening.** `lastClick` belongs to the view, a new view starts at 0, and `open` makes a new view. *Evidence:*
  the code. *Status:* **open**, minor ([F4](#findings-in-xalimenu-today)).
- **C5. Stale slot mapping (refresh, animation, page turn).** The client clicks what it saw one tick ago; the server maps the slot to what it
  shows *now*. A menu that swaps "Cancel" and "Buy" on refresh, or a paged shop that turns the page, runs the wrong item's actions.
  *Evidence:* knowledge. *Status:* **open** (not a dupe, but the same audit); bind ids, not slots, into the action and confirm.

### D. Windows with logic of their own

`menu.type` accepts any `InventoryType` name (`MenuManager.kt:214`, `:472`), so a menu may be a crafting table, anvil, furnace and so on.
Those windows run vanilla slot logic (results, recipes) that is not all click-driven.

- **D1. Recipe-book auto-fill bypasses click events.** Clicking a recipe in the recipe book moves items between the grid and the inventory
  with no `InventoryClickEvent`, which defeated EssentialsX's `/recipe` (a plugin put recipe items in a crafting window and cancelled clicks until
  close). Closed via PaperMC/Paper PR #3351 (2020); a commenter says the autocraft permission went away in 1.12.1 and the dupe was fixed in
  1.13.1. *Evidence:* read (Paper issue #1783). *Status:* **open** for a `type = "crafting"`/`"workbench"` menu; Paper has a recipe-book click event
  (name from memory: `PlayerRecipeBookClickEvent`, verify) that has not been wired in. The sensible rule is to refuse these types or to cancel it.
- **D2. Result-slot shift-click.** A batch of vanilla tickets (MC-271708, MC-271697, MC-271810, all marked resolved) report duplication when
  shift-clicking out of crafting tables; MC-44291, MC-104799 and MC-207172 (duplicates of older tickets) are about items lost when the inventory is
  nearly full. *Evidence:* snippet. *Status:* covered while every click is cancelled; **open** for types whose results do not need a click.
- **D3. Anvil, smithing, grindstone, stonecutter, cartography, loom.** The result is computed by vanilla (`PrepareAnvilEvent` and relatives).
  XaliMenu's own text-input anvil is the one place it uses one, and it is hardened (step 8). A *menu file* with `type = "anvil"` is not.
  *Evidence:* knowledge. *Status:* open for configured menus.
- **D4. Windows that exist only on one side.** A server that closes a window a Bedrock client still shows, see [F2](#f-bedrock-and-geyser) below.

### E. The client and the protocol

- **E1. The client's claim about the stacks.** Click packets carry the client's idea of the changed slots and of the cursor (full stacks before
  1.21.5, hashes after). The server compares against its own and resyncs on a mismatch, but it does not take the client's word. A plugin that
  trusts `event.cursor` or the `ItemStack` the packet implied for payment is trusting the wrong side. *Evidence:* knowledge. *Status:* covered
  for deposits, which read `player.itemOnCursor` or `player.inventory.getItem(slot)` at the moment of the offer (`Deposits.kt:32`, `:51`).
- **E2. Out-of-range slots and stale window ids.** A hacked client sends slot numbers beyond the window, or clicks for a window id that was
  closed. *Evidence:* knowledge. *Status:* covered: `shown.getOrNull` and `inventoryItemAt` return null for a bad slot; Paper ignores clicks for
  the wrong window id.
- **E3. The packet-rewritten overlay.** A menu that draws in the player's rows shows items that exist only in packets. The risks are display-only
  (a menu item left on the hotbar after the window closes, or shown in a different window) and the data saved with the window open. *Evidence:*
  the README states it and `live/bot/inventory.js` checks it. *Status:* covered, with one unknown: what the *creative* client sends back for a
  slot whose stack was rewritten (not exercised).
- **E4. Click rate.** Client mods that spread or move items (Mouse Tweaks-style shift-drag) send many click packets per tick; cheat clients send
  more. *Evidence:* knowledge. *Status:* covered by the cooldown for actions; the cancel path costs one event per packet regardless.

### F. Bedrock and Geyser

Geyser translates Bedrock's item-stack requests into Java clicks, so its idea of a click is different from the Java client's.

- **F1. Bedrock sends changes, not buttons.** Geyser's inventory-transaction work notes that the Bedrock client does not say whether it left- or
  right-clicked, only the changed amounts, and that "sometimes an item ghosts around in the custom inventories". *Evidence:* snippet (Geyser PR
  #67 and issue #212, "remaining inventory bugs", closed in 2020 with no per-bug fix). *Status:* partly: XaliMenu answers with forms
  (`bedrock.click-fallback`, `action-picker`) on touch, controller and Pocket UI (`MenuListener.kt:66`-`83`), but the plain-chest path for a
  Bedrock player has only been tested in unit tests (`PLAN.md`, "Not yet tested live").
- **F2. `closeInventory` does not close a Bedrock player's window.** The server closes it and the client keeps showing it, so the client goes on
  clicking a window the server no longer has. Fixed mostly by Geyser PR #5346 (early 2025) except horse inventories. *Evidence:* read (Geyser
  issue #5259). *Status:* open for old Geyser builds; the `close` action and the form path use `closeInventory()`.
- **F3. Recipe-book auto-craft reverts on Bedrock.** The client plays the craft, the server reverts it (Geyser issue #6563, open, 2026,
  Geyser 2.10.1). It is a ghost, not a dupe. *Evidence:* read. *Status:* n/a unless a Bedrock player is shown a crafting-type menu.

### G. Persistence and crashes

- **G1. A window that holds real items when the server stops.** Custom inventories are not saved by vanilla; whatever a plugin keeps in one is lost or
  returned twice, depending on when the plugin writes it. *Evidence:* knowledge. *Status:* n/a for XaliMenu; the overlay design was chosen to
  avoid it, and `XM_MODE=saved` in `live/bot/inventory.js` proves the saved data holds the real items.
- **G2. Reload or disable with windows open.** `MenuManager.closeAll()` closes them (`MenuManager.kt:512`). A window of a plugin instance that
  has gone returns its content to the player when it closes. *Evidence:* the code comment in `Anvils.kt`. *Status:* covered for the anvil paper;
  nothing else is returned because nothing else is held.
- **G3. Rollback on a kick or a failed save.** A packet that makes the server disconnect a player *after* the world changed but *before* their data
  was written lets the player log in to an older save, so anything that left the world or was given since is gone or doubled. The 2024-25
  book-and-quill dupe on Paper 1.20.6 to 1.21.1 (title over 15 characters, `container_set_slot` encode error, kick, relog) is the clearest case; it is
  patched. *Evidence:* read (Quartz note). *Status:* n/a for menu code; a reminder that giving an item and then `connect`-ing or kicking in the same
  tick leaves the order to the server.
- **G4. Dropping while disconnecting.** Older Paper processed packets from a player who had already disconnected, which let items be thrown in
  the gap. Paper's maintainers said it no longer does. *Evidence:* snippet (a long-running Paper issue; the page was not opened). *Status:* n/a.

### H. Values that a menu can be made to create

Not inventory duplication, but the same family: a number that a player can influence reverses an action's direction.

- **H1. A negative amount.** `money`, `experience` and (through placeholders) several other numeric fields parse a string, with no sign check.
  *Evidence:* the code ([F6](#findings-in-xalimenu-today)); no outside source. *Status:* **open**.
- **H2. An argument that is part of a command.** A menu argument is typed by the player (cleaned of `%` and control characters, not of `-`, quotes or
  spaces in a last `...` argument) and is put into `{name}` in text and commands. *Evidence:* the README ("Players type these words"). *Status:*
  partly (cleaned, not validated).

## Findings in XaliMenu today

Ordered by how likely each is to matter. All are from reading the code; none was run.

### F1. `click.any` runs for double-click, number-key, drop and offhand clicks

`handler()` returns the item's `any` handler for every click type that has no handler of its own (`MenuListener.kt:95`-`105`). A double-click is
sent as a plain pickup followed by `PICKUP_ALL` (`DOUBLE_CLICK`), which Bukkit reports as two events; the vanilla client's double-click window is
about 250 ms (from memory, not verified), and the default `click-cooldown` is 150 ms (`MainConfig.kt:16`). A deliberate double-click on a button
with only an `any` handler can therefore run its actions twice. `NUMBER_KEY` and `DROP` over a button also run it, which is surprising for a button
the player did not click. Action replay is the likeliest real-world loss in the catalog (C1, C2).

*Try:* `live/bot` with a menu whose only handler is `click.any` and whose action is `variable add`; send a click and a double-click and count.
*Options:* resolve a handler only for the five click types the schema names and ignore the rest; or raise the cooldown above the double-click
window; or make the cooldown per item as well as per view.

### F2. Creative mode is not handled

There is no reference to `GameMode`, `InventoryCreativeEvent` or `CLONE_STACK` in the click path. A creative player can middle-click a menu item
(mode 3); on the server `CLONE_STACK` is cancelled with everything else, but the creative client is authoritative about what it holds (A8), so a copy
of the item may still end up on the cursor or in the inventory. For an ordinary display item the harm is a stray item with the menu's name. Where a
menu shows a **real** item (XaliAuctions draws a listing as the item itself with its own name and lore, per `AGENTS.md`), the copy is a copy of a real item.

*Try:* a creative bot middle-clicks a menu slot and a shift-middle; compare the inventory and cursor before and after, and the saved player data.
*Options:* mark every item the menu builds with a persistent-data key (as `Anvils.kt:146` does for its paper), and remove marked items on pickup,
inventory click, join and close; or deny `CLONE_STACK` and `CREATIVE` and resend the inventory next tick.

### F3. No resync of the player's inventory after a cancelled click or a close

`Screens.end` resends the inventory only for menus that draw in it. An ordinary menu relies on the server's own resend after a cancel. A4, A5,
B2 and B5 are reports that this resend is incomplete for hotbar and offhand swaps and for a close right after a click, at least on older versions.
Nothing in the repository shows whether 26.x has the problem.

*Try:* number-key swap onto a menu slot, offhand swap, shift-click then close in the same tick, with the bot at 150 ms latency (mineflayer has no
latency option; use `tc netem` on loopback or a proxy). Compare `bot.inventory` with the server's `/data get entity`.
*Options:* `player.updateInventory()` on the next tick after a cancelled click of those types, and on close.

### F4. The first click of every view is not rate-limited

`view.lastClick != 0L &&` (`MenuListener.kt:41`) skips the check for a new view, and an `open` of the same menu creates a new view. It does not
help a player past a real cost (the money or item is taken by the action), so it is minor; it matters for an action that is free to run and has a
side effect that should be rare.

### F5. Menu items carry no marker

Only the anvil paper is tagged. If a menu item ever leaves a window (F2, a plugin that un-cancels, a client bug), nothing recognizes it and
nothing removes it. A marker costs little for display items; for a real item drawn from an item source it changes what the copy looks like to
`isSimilar`, so the marker belongs on the *shown* stack only, never on what a `give-item` or deposit hands over.

### F6. Negative amounts reverse `money` and `experience`

`Money` parses `ctx.parse(amount)` with `toBigDecimalOrNull()` and hands the value to the economy (`action/Actions.kt:166`); `Experience` uses
`toIntOrNull()` and then `giveExp(if (take) -value else value)` (`:184`). With a player-typed argument or a placeholder a player controls, `-50`
makes `take` give 50 experience points and, depending on the Vault provider, `money take -50` is a deposit (`hook/Economy.kt:36` does not check
the sign). `take-item` and the fixed `amount` fields reject values below 1, so only the string-typed ones are open.

*Try:* a menu with `args = ["n"]` and `experience { amount = "{n}", take = true }`; run it with `-50`.
*Options:* require a positive value in both actions, and in the `Within` annotation of the editor.

### F7. `menu.type` accepts windows with vanilla logic

See D1 and D3. The config check (`MenuManager.kt:214`) only checks that the name is an `InventoryType`. A menu of type `workbench`, `anvil`, `smithing`
and so on has result slots and recipe logic that the click cancel does not reach.

*Options:* allow the types that are plain containers (chest, hopper, dropper, dispenser, shulker box, barrel) and warn on the rest, or add a
handler for the recipe-book click event.

### What is already right

Worth keeping when the code changes: the unconditional cancel before any logic (`MenuListener.kt:26`); `HIGHEST` priority; drag cancelled;
deposits read from the server and taken first (`Deposits.kt`); `open` and `close` deferred; the new view registered before the old one is closed;
the overlay that never touches the real inventory; the anvil paper's marker, sweep and clear. The README's sentences "nothing can be lost, taken
or duplicated" and "Nothing can be moved in or out of a menu" hold as far as the code is concerned and are not yet backed by a test.

## Test matrix

Add `live/bot/clicks.js` (same shape as `deposit.js`). For each row: before the click take a snapshot of the bot's inventory, cursor and offhand,
and of the menu's slots; after it wait 700 ms (as `deposit.js` does), take another, and read the server's data for the player. Expected result for
every row: **nothing moved, the cursor is empty or unchanged, and any action ran at most once**.

mineflayer's `bot.clickWindow(slot, mouseButton, mode)` sends the vanilla packet; window slots count the menu first, then the player's rows
(`HOT0 = 54` in a 3-row chest, see `deposit.js`). The modes and buttons below are the protocol's, written from memory and not checked against
mineflayer's source: confirm each with a first run that logs what the server's `InventoryClickEvent` saw (`click` and `action`).

| Case | Call | Bukkit |
|---|---|---|
| Plain click on a button | `clickWindow(s, 0, 0)` / `(s, 1, 0)` | `LEFT` / `RIGHT` |
| Shift-click on a button, and on an own item | `clickWindow(s, 0, 1)` | `SHIFT_LEFT` |
| Number key onto a menu slot | `clickWindow(s, n, 2)`, n 0 to 8 | `NUMBER_KEY` |
| Offhand swap | `clickWindow(s, 40, 2)` | `SWAP_OFFHAND` |
| Middle click (creative bot) | `clickWindow(s, 2, 3)` | `MIDDLE`, `CREATIVE` |
| Drop, Ctrl-drop | `clickWindow(s, 0, 4)` / `(s, 1, 4)` | `DROP` / `CONTROL_DROP` |
| Click outside the window with a cursor item | `clickWindow(-999, 0, 0)` | `WINDOW_BORDER_LEFT` |
| Drag across menu slots | `clickWindow` with mode 5, buttons 0, 1, 2 for start, add, end | `InventoryDragEvent` |
| Double-click on an own stack, on a button | `clickWindow(s, 0, 0)` then `(s, 0, 6)` within 100 ms | `LEFT`, `DOUBLE_CLICK` |
| Two clicks 160 ms apart (above the cooldown) | two `clickWindow` calls | counts actions run |
| Click, then close in the same tick | `clickWindow` then `closeWindow` | B2 |
| Open the same menu repeatedly, click at once | the menu's own command in a loop | C4 |
| Each of the above in creative mode | `gamemode creative` first | A8 |
| Each of the above with an `accept` slot on screen | `deposit.js` menu | the pass-through path |
| Each of the above in a menu that draws in the player's inventory | `inventory.js` menu | the overlay |

Mineflayer works out the predicted slot changes of a click itself, so a click that the server should refuse can still be sent as a *raw* packet
(`bot._client.write('window_click', {...})`) with a lying cursor or changed-slots list, which is the way to try E1. Do not trust a test that only
asserts on the bot's own view: assert on the server (`xvar`, the saved player data via `/xmsave`, `/data get entity`).

Unit tests (`paper/test`) can cover what is pure: a table-driven test that every `ClickType` resolves to the handler that the schema promises, and
that `UNKNOWN` resolves to none, once F1 is decided. A mocked server cannot show client resync; do not add a test that pretends to.

## Hardening backlog

In the order that the evidence in this page supports. Each is a separate decision; none is done.

1. Decide what `click.any` means (F1) and make the double-click case run at most once.
2. Run the test matrix on the dev Folia server and record what is real in this page (replace the *open* statuses). This is the only way to find
   out whether F2 and F3 exist on 26.x.
3. Positive-only `money` and `experience` (F6).
4. A marker on shown stacks and a sweeper (F5), or deny creative clones (F2), depending on what step 2 shows.
5. `updateInventory()` on the next tick for the click types in A4/A5 and on close (F3).
6. Limit `menu.type` or handle the recipe-book event (F7).
7. A MONITOR-priority check that logs, in debug, a menu click that ended up allowed outside the deposit path (A11).
8. Re-run the matrix on every Paper and Geyser bump: Paper #5222 (read) shows a Paper API change (inventory titles became components) turning a
   GUI into one players could take items from, and was fixed only in later builds.

## Out of scope

Dupes that do not go through an inventory window, listed so nobody searches for them here: the book-and-quill dupe (see G3), the trident dupe
(patched in Paper 1.21.4 by 2025-02-16 per a community plugin's notes), shulker-box tile entity removal (a Forge port of Paper's fixes lists it),
and TNT, carpet and rail dupes (Paper fixed them and keeps an unsupported setting to bring vanilla's behavior back, per Paper's documentation).
All are **snippet** evidence. Chunk, piston, portal and entity-transfer dupes are not covered by this page.

## Sources

Read in full for this page:

- [PaperMC/Paper #5222, "GUI Issues & Dupes"](https://github.com/PaperMC/Paper/issues/5222): a Paper 1.16 title change, not a click bug.
- [PaperMC/Paper #1783, recipe book click event](https://github.com/PaperMC/Paper/issues/1783): auto-fill moves items with no event; EssentialsX `/recipe`.
- [LiamBMX/joshymc-plugin PR #849](https://github.com/LiamBMX/joshymc-plugin/pull/849) and [issue #1116](https://github.com/LiamBMX/joshymc-plugin/issues/1116): collect-to-cursor; the audit checklist.
- [Paper javadoc, `InventoryClickEvent`](https://jd.papermc.io/paper/1.21.11/org/bukkit/event/inventory/InventoryClickEvent.html), [`InventoryAction`](https://jd.papermc.io/paper/1.21.11/org/bukkit/event/inventory/InventoryAction.html), [`ClickType`](https://jd.papermc.io/paper/1.21.11/org/bukkit/event/inventory/ClickType.html).
- [GeyserMC #5259](https://github.com/GeyserMC/Geyser/issues/5259), [#6563](https://github.com/GeyserMC/Geyser/issues/6563), [#212](https://github.com/GeyserMC/Geyser/issues/212).
- [Quartz note, "PaperMC Dupe Glitch"](https://quartz.durocodes.me/Minecraft/PaperMC-Dupe-Glitch).

Known from search-result summaries only (open them by hand before quoting):

- Spigot Jira, all 403 to automated fetches: [SPIGOT-6145](https://hub.spigotmc.org/jira/browse/SPIGOT-6145) (HOTBAR_SWAP and offhand), [SPIGOT-6280](https://hub.spigotmc.org/jira/browse/SPIGOT-6280), [SPIGOT-6291](https://hub.spigotmc.org/jira/browse/SPIGOT-6291) (ghost item after a cancelled swap), [SPIGOT-6346](https://hub.spigotmc.org/jira/browse/SPIGOT-6346) and [SPIGOT-2816](https://hub.spigotmc.org/jira/browse/SPIGOT-2816) (creative cancel dupe), [SPIGOT-6727](https://hub.spigotmc.org/jira/browse/SPIGOT-6727) (ghost item on close), [SPIGOT-8007](https://hub.spigotmc.org/jira/browse/SPIGOT-8007), [SPIGOT-2250](https://hub.spigotmc.org/jira/browse/SPIGOT-2250), [SPIGOT-1299](https://hub.spigotmc.org/jira/browse/SPIGOT-1299), [SPIGOT-1658](https://hub.spigotmc.org/jira/browse/SPIGOT-1658) (double-click runs a purchase twice), [SPIGOT-5672](https://hub.spigotmc.org/jira/browse/SPIGOT-5672), [PLUG-409](https://hub.spigotmc.org/jira/browse/PLUG-409) (CraftEnhance, offhand).
- Forum threads: [shift double-click (478897)](https://www.spigotmc.org/threads/issue-with-inventory-double-shift-clicks.478897/), [item duping with shift click (179948)](https://www.spigotmc.org/threads/item-duping-with-shift-click.179948/), [inventory click control (382121)](https://www.spigotmc.org/threads/inventory-click-control.382121/), [cancel deletes item (308621)](https://www.spigotmc.org/threads/cancel-inventoryclickevent-deletes-item.308621/), [Bukkit 472034](https://bukkit.org/threads/cancel-inventoryclickevent-deletes-item.472034/).
- Plugin fixes: [DonutOrders PR #6](https://github.com/MeherBenSalem/DonutOrders/pull/6), [DonutOrders updates](https://builtbybit.com/resources/donutorders.69065/updates), [Order & Auction GUI updates](https://builtbybit.com/resources/order-auction-gui-plugin.82221/updates?page=5), [ChestCommands "item dup"](https://bitbucket.org/facha/chestmaster-v2.0/issues/6/item-dup).
- Geyser: [PR #67 inventory transactions](https://github.com/GeyserMC/Geyser/pull/67).
- Vanilla: Mojang tickets MC-271708, MC-271697, MC-271810, MC-44291, MC-104799, MC-207172, MC-156644, MC-58039 (bugs.mojang.com).
- Paper and others: [FixTridentDupe](https://github.com/milkteamc/FixTridentDupe), [PaperFixes](https://www.curseforge.com/minecraft/mc-mods/paperfixes).
