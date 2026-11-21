# XaliMenu

**XaliMenu** is an inventory menu plugin for Folia and Paper. Menus are configured in HOCON and modeled on
AbstractMenus: activators open menus, and items carry gates (requirements) and click actions. Menus are read with
kotlinx.serialization's HOCON support, and text goes through the same color handling as
[XaliChat](../chat/README.md) (legacy codes and MiniMessage). Other plugins add their own actions, conditions and item
sources through an [addon API](#extending).

## Contents

- [Installation](#installation): [Requirements](#requirements), [Modules](#modules),
  [Building and installing](#building-and-installing), [Removal](#removal)
- [Configuration](#configuration): [Files](#files), [Menu files](#menu-files), [How a menu behaves](#how-a-menu-behaves),
  [Item properties](#item-properties), [Actions](#actions), [Conditions](#conditions), [Paged menus](#paged-menus),
  [Animations](#animations), [Bedrock and multi-device support](#bedrock-and-multi-device-support),
  [Opening a menu by what a player does](#opening-a-menu-by-what-a-player-does), [Variables](#variables)
- [Usage](#usage): [Commands](#commands), [Importing from DeluxeMenus](#importing-from-deluxemenus)
- [Web editor](#web-editor)
- [Extending](#extending)
- [Troubleshooting](#troubleshooting)
- [Development](#development)
- [See also](#see-also)

## Installation

### Requirements

| Plugin | Needed | Purpose |
|---|---|---|
| PlaceholderAPI | Yes | Placeholders in menu text and conditions; XaliMenu's own `%xalimenu_...%` placeholders. |
| VaultUnlocked (or Vault) | No | Money conditions and actions (several currencies), permission actions. |
| ItemsAdder | No | The `itemsadder:<id>` item source. Runs on **Paper only**, see [Troubleshooting](#troubleshooting). |
| Nexo | No | The `nexo:<id>` item source. |
| SkinsRestorer | No | Skins for `head:<player>`; the `skin` and `skin-reset` actions. |
| HeadDatabase | No | The `hdb:<id>` item source. |
| FancyNpcs | No | The `npc` activator. Citizens is not supported. |
| WorldGuard | No | The `region-enter` and `region-leave` activators and the `region` condition. |
| PacketEvents or ProtocolLib | No | Drawing in the player's inventory (`inventory`); PacketEvents is used if both are installed. |
| ViaVersion | No | Tells `input` which version a player's client is, so a client that is too old for dialogs gets an anvil. Clients older than the server need it (with ViaBackwards) to join at all. |
| Floodgate or Geyser-Spigot | No | Detects Bedrock Edition players (OS, device kind, input mode, Classic/Pocket UI) and sends native Cumulus forms (`SimpleForm`, `ModalForm`, `CustomForm`, action pickers and `input` text forms). Floodgate is preferred when both are installed. |

### Modules

| Module | Description |
|---|---|
| `menu/api` | `net.xalitoria.menu`: what other plugins compile against. The menu model, `Action`, `Condition`, `MenuContext`, `ItemSource`, `Variables` and `XaliMenuApi`. Shaded into the plugin's jar. |
| `menu/paper` | `mc.xalitoria.menu.paper`: the plugin. Its built-in actions and conditions are written against the same API. |
| [`menu/editor`](editor/README.md) | `com.xalitoria.menu.editor`: the [web editor](#web-editor) (`web/`, static files) and the task that writes its `schema.json` from the plugin's classes. Not part of the plugin jar. |
| [`menu/relay`](relay/README.md) | A small Node.js mailbox for `/xmenu share`. Not a Gradle module. |
| [`menu/live`](live/README.md) | Scripts that test the plugin on a real Folia server with a bot. Not a Gradle module. |

### Building and installing

```console
user $ ./gradlew :menu:paper:shadowJar
```

Copy `build/libs/menu/xali-menu-<version>-paper.jar` into `plugins`. `./gradlew :menu:paper:runServer` downloads the
pinned Folia build and PlaceholderAPI and VaultUnlocked into the shared dev server, `run/folia` (see the
[root README](../README.md#dev-servers)), installs the plugin and starts it on the JetBrains Runtime (debugger on port
5005). Plugins that cannot be downloaded without an account (ItemsAdder, HeadDatabase, Nexo, SkinsRestorer) are copied
into `run/folia/plugins` by hand.

The `paper { }` descriptor is in `menu/build.gradle.kts`. The API is shaded into the plugin jar with `minimize` excluding
it, so an addon sees it through `join-classpath`.

### Removal

Delete the jar. `plugins/XaliMenu/` (the menus and `variables` data) and, with `database.type` `mariadb` or `mysql`, the
tables `<prefix>variable` and `<prefix>revision` stay. Plugins that registered actions or catalogs with XaliMenu
(XaliFriends, XaliRedeem) report their menus as broken until the menu files that use them are deleted.

## Configuration

### Files

| File | Holds |
|---|---|
| `plugins/XaliMenu/config.yml` | `debug`, `language`, `click-cooldown`, `database`, `variables`, `editor` and `bedrock`. |
| `plugins/XaliMenu/language/<language>.yml` | Every message, written with the defaults on the first start. |
| `plugins/XaliMenu/menus/<name>.conf` | One menu per file. `example.conf` is written on the first start. |

Both configuration files are YAML, like XaliChat's. `/xmenu reload` reads configuration, language and menus again.
Messages and menu text accept `&` codes, `&#RRGGBB`, CMI gradients (`{#a0a>}text{#fff<}`) and MiniMessage.

### Menu files

The schema is the classes in `net.xalitoria.menu.model`, with keys in kebab-case (`max-stack-size`, `shift-left`,
`on-open`). It is XaliMenu's own schema and is **not** compatible with AbstractMenus' HOCON. [`REFERENCE.md`](REFERENCE.md) lists every key, action, condition, activator and catalog with its type, default and range; it is written from the schema, so it is never behind.

**What the plugin says about a file.** A menu file is checked against the schema as it is read, and every problem is told with where it is (and the line of the file):

```text
Menu 'shop' could not be read: 2 problems:
  - items.sword.click.left.actions[0].type (line 12): 'mesage' is not an action (did you mean 'message'?)
  - items.gem.material (line 20): is required
Menu 'shop': items.apple.slott (line 8): is not a key of an item, so it is ignored (did you mean 'slot'?)
```

- A key that nothing reads (a typo, or the style of another config: `max_stack_size`) is a warning, with the key it was probably meant to be; the menu still loads.
- A missing key, a value of the wrong kind, a word that is not one of an enum's or one of the types of an action, condition, catalog or activator, make the file unreadable, and all the reasons are given at once.
- A number outside what the schema allows (`amount = 0`, `size = 100`, an `inventory-slots` past 35, a `chance` of 150 percent) is a warning, and an action or condition that can't be made with it (a `potion` with `amplifier = 300`, a `take-item` of 0) refuses the file, told with its path and line, together with the warnings of the same file, so that all of it is fixed in one round. The limits are the `@Within` of the property in the model, which the editor uses too.
- A few things may be written the easy way: one value where a list is expected (`slots = 5`, `actions = { type = close }`, `lore = "a line"`), an enum in any case (`rarity = epic`), and a number or switch as text (`slot = "3"`).
- What another plugin's action or condition holds is checked by that plugin, not here.
- `/xmenu validate` and `/xmenu reload` give the same words.

**File** `plugins/XaliMenu/menus/jobs.conf`

```hocon
title = "&8Jobs"
size = 27
commands = ["jobs", "jobs browse"]
items {
    miner {
        material = "diamond_pickaxe"      # or head:<player>, texture:<base64>, hdb:<id>, itemsadder:<id>, nexo:<id>
        slot = 10
        name = "&aMiner"
        view = { require = [{ condition = { type = "permission", node = "jobs.use" } }] }
        click.left = { actions = [{ type = "player", command = "jobs join miner" }, { type = "close" }] }
    }
}
```

Enum values in menu files are upper case. HOCON objects have no key order.

**Matrix.** Instead of listing slot numbers on every item, draw the entire menu as one global grid: `matrix.cells` is
rows of up to nine characters and `matrix.map` names the item in `items` that each character stands for. Each item goes
in every slot where its character stands, besides its own `slot` / `slots`. Because each cell holds one character, items
placed through the matrix cannot conflict with each other. A character `matrix.map` doesn't know is a blank, and a short
row ends in blanks. Quote a character that HOCON reads as something else, such as `"#"`.

```hocon
size = 27
matrix {
    cells = ["#########", "#  o   x#", "####c####"]
    map { "#" = border, o = ok, x = extra, c = close }
}
items {
    border { material = "gray_stained_glass_pane", name = " " }
    ok { material = "lime_wool", name = "Ok" }
    extra { material = "stone", name = "Extra" }
    close { material = "barrier", name = "Close", click.any.actions = [{ type = "close" }] }
}
```

**The player's own inventory.** An item of `items` with `inventory-slots` is drawn in those slots of the player's inventory as well, as the game numbers them: 0 to 8 the
hotbar, 9 to 35 the three rows above it (not armor and the off hand, which the screen doesn't show). It is the same item, so it is defined once and used in the menu and under it,
with everything an item has: `view`, `priority`, `update`, clicks. An item with only `inventory-slots` is drawn under the menu and not in it. Nothing else in the inventory can be
moved, and nothing can be picked up while the menu is open (unless an item takes items, see below).

```hocon
size = 9
items {
    close { material = "barrier", slot = 4, click.any.actions = [{ type = "close" }] }
    filler { material = "gray_stained_glass_pane", name = " ", slots = [0, 1, 7, 8], inventory-slots = [0, 1, 2, 3, 4, 5, 6, 7] }  # one item, in the menu and the hotbar
    back { material = "arrow", inventory-slots = [8], name = "Back", click.any.actions = [{ type = "open", menu = "main" }] }
    coins { material = "gold_ingot", inventory-slots = [9], name = "Coins: %xalimenu_var_coins:0%", update = true }
}
```

This needs PacketEvents (preferred) or ProtocolLib, both optional: without either the menu opens as usual, the rows are left as they are, and the console says so once. The
items only exist in the packets that fill the menu's window: the player's real inventory is never touched, so nothing can be lost, taken or duplicated
(the data saved with a menu open holds the real items only). Clicks anywhere in a menu's window are cancelled. Items of an `animation`'s frames and of
`pages` are drawn in the menu only.

**Slots that take an item.** An item with `accept` takes an item from the player: they click the slot with the item on their
cursor, or shift-click it in their inventory. With `material = "air"` the slot shows nothing. It doesn't work in a menu that
draws in the player's inventory (the console says so when the menu is read), since the clicks there are the menu's.

```hocon
items {
    deposit {
        material = "air", slot = 13
        accept {
            material = "diamond", amount = 2
            then = [{ type = "take-item", material = "gold_ingot", amount = 5 }, { type = "message", text = "Deposited {amount} {item}" }]
            reject = [{ type = "message", text = "&cOnly diamonds" }]
        }
    }
}
```

`material`, `name` and `model` say which items fit (any when none is given) and `amount` how many are taken from the stack,
which must hold at least that many. The offered item is the one the server has (on the cursor, or in the slot that was
shift-clicked), never what the client says, and **it is taken only after `then` ran to its end**: an action that aborts
(`take-item` without enough, `give-item` with no room and `overflow = "ABORT"`, a failed check) or fails leaves it where it was, so put what can
refuse before what gives. The server's state is looked at again just before the item is taken. `{item}` and `{amount}` are
in scope in `then`; `reject` runs for an item that doesn't fit. Actions that wait (`delay`) are not waited for. An `open` in `then` opens its menu once the deposit has been settled (the item taken, or not), since opening a menu gives the cursor's item back to the inventory. Only `open` is put off: a command run for the player that opens a window by itself would give the item back first, and the item then could not be taken (the console says so). While a menu has a slot like this,
the player can pick up and put down items in their own inventory, which moves nothing of the menu; everything else is still cancelled. A shift-click goes to the
first slot, in slot order, that the item fits, and does nothing if there is none.

### How a menu behaves

Opening checks the menu's `open` gate, then draws the items. Of the items claiming a slot, the one with the lowest
`priority` (ties by name) whose `view` gate passes is drawn. `refresh` draws again, rebuilding only items marked
`update`; a menu whose file names a variable placeholder (`%xalimenu_var_<name>%` and the like) is also drawn again, with no `refresh` needed, a moment after a variable changes (see Variables). A click runs the item's handler for that kind of click (or `any`) if its `require` gate passes. Nothing can be
moved in or out of a menu. Everything runs on the player's region thread; console commands go to the global region
(Folia).

Item sources: a vanilla key, `head:<player>` (an online player's own skin, otherwise SkinsRestorer's or Mojang's, looked
up off-thread), `texture:<base64>`, `hdb:<id>`, `itemsadder:<id>` and `nexo:<id>`. An id that cannot be resolved is
drawn as a barrier. Menus are checked when they are read, but the checks do not ask the item sources, since those load
later. Money conditions and actions use VaultUnlocked (`currency` picks one when there are several). Permission actions
use whatever provides Vault's `Permission`.

### Item properties

Besides `material`, `slot`, `slots`, `priority`, `amount`, `name`, `lore`, `enchants`, `hide`, `unbreakable`,
`model` (custom model data), `color` (leather and the like), `banner` (the patterns of a banner or a shield) and `base-color` (a shield's base), `potion` (custom effects), `nbt`, `update`, `view`
and `click`, an item takes the properties below. They work the same for the items of `give-item`. A property that does not
fit the item (a `trim` on a stick) is left out, a value that cannot be used is reported when the menu is read, and an item that
cannot be built at all is shown as a barrier and noted once in the log rather than breaking the menu.

**Numbers and switches that follow a placeholder.** A number or a switch of an item is written as a number (`amount = 3`) or as text with placeholders that the
plugin works out each time the item is drawn (`amount = "%player_level%"`, `glow = "%player_is_op%"`). It works for `amount`, `model`, `damage`, `max-stack-size`
(numbers) and `unbreakable`, `glow`, `hide-tooltip` (switches `true` or `false`, or text that gives `true`, `false`, `yes`, `no`, `on`, `off`, `1` or `0`). Text that does not
work out to a number (or a switch) leaves the property at its default. There is no separate `dynamic-amount`: it is `amount`. The DeluxeMenus importer turns `dynamic_amount` into it.

| Key | Does |
|---|---|
| `item-name` | The base name, plain and not italic; `name` is the renamed one and wins. |
| `damage` | Durability used up. |
| `rarity` | `COMMON`, `UNCOMMON`, `RARE` or `EPIC`, the color of the name. |
| `glow` | `true` makes the item shimmer, `false` stops an enchanted item from shimmering. |
| `max-stack-size` | 1 to 99. |
| `hide-tooltip` | No tooltip at all. |
| `item-model` | The key of a resource pack's model, such as `mypack:crown`. |
| `food` | `nutrition`, `saturation`, `always-edible`: makes anything edible. |
| `stored-enchants` | Enchantments kept in an enchanted book. |
| `potion-type` | The base potion of a potion or arrow, such as `strong_healing`. |
| `trim` | `pattern` and `material` of an armor trim, such as `coast` and `gold`. |
| `book` | `pages` (text), `title` and `author` of a written book. |
| `firework` | `power` (rockets), and `effects` with `type`, `colors`, `fade`, `flicker` and `trail`; a star shows the first. |
| `tool` | Makes the item mine blocks: `mining-speed`, `damage-per-block`, `can-destroy-in-creative` and `rules` (`blocks` as keys or `#tags`, `speed`, `drops`). |
| `fish` | `pattern`, `body-color` and `pattern-color` of a bucket of tropical fish. |
| `attributes` | A list of `attribute` (`attack_damage`), `amount`, `operation` (`ADD`, `MULTIPLY_BASE`, `MULTIPLY_TOTAL`) and `slot` (`any`, `mainhand`, `offhand`, `hand`, `head`, `chest`, `legs`, `feet`, `armor`, `body`). They replace the ones the material has. |
| `tooltip-style` | The key of a resource pack's tooltip background. |
| `equippable` | Makes the item wearable: `slot` (`HEAD`, `CHEST`, ...), `model`, `camera-overlay`, `swappable`, `dispensable`, `damage-on-hurt`. |

Shulker boxes and the like need no key of their own: their color is part of the material (`blue_shulker_box`). The
importer carries `damage`, `rarity`, `hide_tooltip`, `enchantment_glint_override` and the trim over from DeluxeMenus.

### Actions

An action is `{ type = "...", ... }`. Text in them takes colors and placeholders, and `{name}` for the menu's arguments, the
page of a paged menu and the element a slot shows. A list of actions runs in order; one that fails is logged and the rest
go on, and one that cannot do what the next depends on (`take-item` without enough items, `money` without enough money,
`stop`) ends the list.

| Type | Does | Settings |
|---|---|---|
| `player`, `console`, `chat` | Run a command as the viewer, as the console, or say something | `command`, `text` |
| `message`, `broadcast`, `actionbar`, `json` | Send text | `text`; `json` has `json`, `broadcast` |
| `title` | A title and subtitle | `title`, `subtitle`, `fade-in`, `stay`, `fade-out` (ticks) |
| `boss-bar` | A boss bar for a while | `text`, `color`, `style`, `progress` (0 to 1), `duration` |
| `book` | Open a written book | `pages` (one per page), `title`, `author` |
| `sound`, `particle` | Play a sound, show a particle | `sound`, `volume`, `pitch`, `scope`; `particle`, `count`, `spread`, `speed`, `dx`, `dy`, `dz`, `everyone` |
| `open`, `close`, `refresh`, `page` | Move between menus and pages | `menu`, `args`; `by` or `to` |
| `give-item` | Give an item (the settings of a menu item) | `item`, `overflow` (`DROP` at the feet, or `ABORT` and give nothing) |
| `take-item` | Take items, or abort if there are fewer | `material`, `amount`, `name`, `model` |
| `clear-items` | Take every matching item; with no setting, everything | `material`, `name`, `model` |
| `potion` | An effect on the viewer, or off them | `effect`, `duration`, `amplifier`, `particles`, `remove` (`effect = all` clears every one) |
| `skin` | The skin of a player or of a picture at a link, kept for the next visit (SkinsRestorer) | `skin` (a name or a link; `{arg}` works), `slim` |
| `skin-reset` | Takes that skin off again (SkinsRestorer) | |
| `teleport` | Move the viewer | `x`, `y`, `z`, `yaw`, `pitch` (a number, or `~` and `~5` relative to where they are), `world` |
| `gamemode` | Change the game mode | `mode` |
| `health`, `food` | Set, `ADD` to or `TAKE` from, held in range | `amount`, `change` (`SET`, `ADD`, `TAKE`); `food` also `saturation` |
| `experience`, `level` | Points or levels; `take` charges, and aborts if they have too few | `amount`, `take` |
| `money`, `permission`, `group` | Through Vault: pay or charge, give or take a node or a group | `amount`, `take`, `currency`; `node`, `take`; `name`, `take` |
| `meta` | A LuckPerms meta value (LuckPerms is needed); no `value` removes it | `key`, `value` |
| `input` | Close the menu and ask for text, which runs `then` as `{input}` | `prompt`, `then`, `mode` (`AUTO`, `DIALOG`, `ANVIL`, `CHAT`, `FORM`), `title`, `initial`, `submit`, `max-length`, `cancel` (word and button label, default `cancel`), `on-cancel`, `timeout`, `match` (regex the reply must fit), `invalid` |
| `all`, `random` | Run actions as a unit, or pick `count` of them at random | `actions`, `count` |
| `run-for` | Run actions for each element of a catalog, with `{index}` and its values | `catalog`, `actions`, `limit` (default 100) |
| `delay`, `chance`, `stop` | Later, by chance, end the list | `ticks`, `action`; `percent`, `action` |
| `variable` | Change a variable, see [Variables](#variables) | |
| `cooldown` | Start a cooldown for a `cooldown` condition; no `duration` means for good | `key`, `duration`, `scope` |
| `log` | Write to the server log | `text`, `level` (`INFO`, `WARNING`, `ERROR`) |
| `connect` | Send the viewer to another server of the proxy | `server` |

`input` asks the viewer to type, so it closes the menu. With `mode = AUTO` (the default) a Bedrock client (when Floodgate or Geyser is installed and `bedrock.input-forms` is on) gets a native Bedrock `CustomForm` with a text box; a Java client that has dialogs (Minecraft 1.21.6 and later) gets a dialog with a text field, `title` as its title, `initial` in the field and a `submit` and a `cancel` button; an older Java client (known through ViaVersion) gets an anvil whose rename field is the text field, taken by clicking its result. `FORM`, `DIALOG`, `ANVIL` and `CHAT` request that channel directly (`CHAT` asks in chat, where nothing else the viewer types counts until they answer and what they type is not said in chat). A reply that does not fit `match` is turned down with `invalid`
(in the dialog it comes back with the message above the field, in the anvil the message is sent to chat) and the viewer may
try again. Closing the anvil, the cancel button, typing the `cancel` word and the `timeout` run `on-cancel`; closing a dialog
with Escape leaves the question open until it times out. The anvil is a window of its own, nothing in it can be taken, and
nothing of it reaches the viewer's inventory. Chaining a `variable` action in `then` stores the answer:

```hocon
click.any.actions = [{
    type = "input"
    prompt = "&eType the new nickname, or cancel"
    match = "[A-Za-z0-9_]{3,16}"
    invalid = "&c3 to 16 letters, digits or underscores"
    then = [{ type = "variable", name = "nick", scope = "PLAYER", value = "{input}" }, { type = "open", menu = "profile" }]
}]
```

### Conditions

A condition is `{ type = "...", ... }` in a `require` of a gate: `view.require = [{ condition = { type = "free-slots", min = 3 } }]`.
Conditions are asked on every refresh, so they only look. Numbers can be placeholders. A limit that is left out doesn't limit.

| Type | Holds when | Settings |
|---|---|---|
| `permission`, `group` | the viewer has a node, or is in a group (Vault) | `node`; `name` |
| `money`, `item` | the viewer has money, or carries items | `amount`, `currency`; `material`, `amount`, `name`, `lore` |
| `held-item` | the item in a hand matches | `material`, `name`, `model`, `amount`, `hand` (`HAND`, `OFF_HAND`) |
| `free-slots` | the inventory has `min` empty slots (default 1) | `min` |
| `health`, `food`, `level`, `experience`, `online-count` | the number is between `min` and `max` | `min`, `max` |
| `gamemode`, `world` | the viewer is in one of the modes, or worlds | `modes` (`SURVIVAL`...); `names` |
| `region` | the viewer stands in a WorldGuard region (one of `names`, any when none) | `names` |
| `near` | the viewer is less than `distance` blocks from a point | `world`, `x`, `y`, `z`, `distance` |
| `online` | the player is online | `player` |
| `compare`, `text`, `regex` | numbers, text or a pattern fit; see [Variables](#variables) | `left`, `op`, `right`; `input`, `op`, `value`; `input`, `pattern` |
| `variable-exists` | a variable exists | `name`, `scope` (`GLOBAL`, `PLAYER`) |
| `cooldown` | the cooldown `key` is not running | `key`, `scope` (`PLAYER`, `GLOBAL`) |
| `client` | the viewer's client matches edition, hardware, OS, input mode or UI profile | `edition` (`JAVA`, `BEDROCK`), `devices` (`PC`, `MOBILE`, `CONSOLE`, `VR`), `os` (`ANDROID`, `IOS`, `WINDOWS`, `XBOX`, `PLAYSTATION`, `SWITCH`, ...), `inputs` (`KEYBOARD_MOUSE`, `TOUCH`, `CONTROLLER`, `VR`), `ui` (`JAVA`, `CLASSIC`, `POCKET`) |
| `and`, `or`, `one-of`, `at-least`, `not` | other conditions combine | `conditions`; `count`, `conditions`; `condition` |
| `chance` | luck decides, `percent` times in a hundred | `percent` |

A `cooldown` is started by the `cooldown` action, with a `duration`; one without a `duration` never ends, which makes
something that can be done once. The state is the variable `cooldown_<key>` (so it lives in whichever store the variable
rules give that name), and while the variables have not loaded the cooldown counts as running. `chance` is asked again on
every redraw, so it belongs in the `require` of a click, not in the `view` of an item.

```hocon
daily {
    material = "chest"
    slot = 13
    view = { require = [{ condition = { type = "cooldown", key = "daily" } }] }
    click.any.actions = [
        { type = "cooldown", key = "daily", duration = "24h" },
        { type = "give-item", item = { material = "diamond", amount = 5 }, overflow = "ABORT" },
        { type = "refresh" },
    ]
}
```

### Paged menus

A menu with `pages` lists the elements of a **catalog** a page at a time, each drawn as the same item template into the
slots named in `slots`. The `players` catalog gives the players who are online, one element each:

**File** `plugins/XaliMenu/menus/players.conf`

```hocon
title = "&8Players &7({page}/{pages})"
size = 54
commands = ["players"]
pages {
    catalog { type = "players", include-self = false }
    slots = [10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25]
    sort = "name"
    item {
        material = "head:{name}"
        name = "&e{name}"
        lore = ["&7#{index} of {total}"]
        click.left.actions = [{ type = "player", command = "msg {name} hi" }]
    }
    empty { material = "barrier", name = "&cNobody is online" }
}
items {
    next { material = "arrow", slot = 53, name = "&aPage {page_next}", click.any.actions = [{ type = "page", by = 1 }] }
}
```

`example-pages.conf` is written on the first start.

The title may hold placeholders and the page values. When its text changes, because the page turned or a placeholder
changed on a refresh, the open menu gets the new title (Paper sends the window again, so the client puts the mouse back
in the middle; this is done only when the text differs). Titles are colored like all text and handed over as legacy
text, which is all Paper can change on an open menu, so a font or a click event in a title is lost.

| Catalog | Elements | Values |
|---|---|---|
| `players` | The players online, by name. `include-self = false` leaves the viewer out. | `name`, `uuid` |
| `worlds` | The worlds of the server. | `name`, `environment` |
| `values` | The entries of `list`, and the pieces of `text` cut at `separator` (read as written, not as a pattern; placeholders are filled in first). | `value` |
| `range` | The whole numbers `from` to `to` (numbers or placeholders), `step` apart, backwards with `descending`. | `value` |
| `inventory` | The items in the viewer's inventory, by slot; `armor = true` adds the armor and the off hand, `empty = true` lists empty slots too (as `air`). | `slot`, `material`, `amount`, `name` |
| `menus` | The menus that are loaded, by name, without the one that lists them unless `include-self = true`. A menu of menus: `open { menu = "{name}" }` in the template. | `name`, `title` |

Every element also has `index`, its place in the list from 1. A catalog gives at most 10 000 elements. Other plugins add
catalogs, see [Extending](#extending).

- **Values in text.** The values of the element are `{name}` in the template's `material`, `name`, `lore`, commands and
  conditions, and in the gate of its clicks. They are cleaned like a menu's arguments (no `%`, no control characters).
- **Page numbers.** `{page}` (from 1), `{pages}`, `{total}` (the elements), `{page_next}` and `{page_prev}` (this page at
  the ends) are available in the text of every item of the menu, not just the template. Items that use them are drawn
  again when the page changes. Other plugins get them as `%xalimenu_page%`, `%xalimenu_pages%`, `%xalimenu_total%`,
  `%xalimenu_page_next%` and `%xalimenu_page_prev%` for the player's open menu.
- **Sorting and filtering.** `sort` names a value to order by (numbers as numbers, otherwise text without regard to
  case; `descending` turns it round). The template's `view` gate decides which elements are listed. It is applied to every
  element *before* the elements are cut into pages, so all pages but the last are full.
- **Slots.** The nth element of the page goes into the nth of `slots`. The template's own `slot` is not used. An item of the
  menu that claims a listed slot is drawn there on pages that have no element left for it.
- **Layout and matrix.** `layout` instead of `slots` draws the menu as it looks: rows of nine characters, where every `cell`
  character (`x` unless `cell` says otherwise) is a slot for an element, read left to right and top to bottom.
  `layout = ["#########", "#xxxxxxx#", "#xxxxxxx#", "####x####"]` lists the two rows between the border and the middle
  slot of the last row. The other characters only help to draw; put the border's items in `items` (or the menu's `matrix`).
  When different catalog slots need different item templates, use `matrix { cells = [...], templates { x { ... }, y { ... } } }`
  instead of `slots`/`layout` and `item`.
- **Opening another menu.** The element's values are in scope for every action of the template, so
  `click.left.actions = [{ type = "open", menu = "profile", args = ["{name}"] }]` opens the `profile` menu for the
  clicked element (its `args = ["who"]` then names the word).
- **Turning pages.** `{ type = "page", by = 1 }` turns forwards (`-1` back), `{ type = "page", to = "first" }` goes to
  `first`, `last` or a page number (which may be `{arg}` or a placeholder). The page stops at the first and last. A
  page that no longer exists, because the list got shorter, is replaced by the nearest.
- **Empty.** `empty`, if set, is drawn in the first slot when the catalog has no element to list.

### Animations

`animation` plays frames over a menu once it is open. A frame waits for its `delay` (`500ms`, `1s`, `1m 30s`; a second
unless said otherwise, a tick at the least), then draws its `items` on top of the menu. With `clear` (the default) what
the frames before it drew goes first, so it replaces them; with `clear = false` its items are added.

**File** `plugins/XaliMenu/menus/loading.conf`

```hocon
title = "&8Loading"
size = 27
items { frame { material = "gray_stained_glass_pane", slots = [0, 1, 2, 3, 4, 5, 6, 7, 8], name = " " } }
animation {
    loop = true
    on-start = [{ type = "sound", sound = "block.note_block.pling" }]
    frames = [
        { delay = 300ms, items.dot { material = "lime_stained_glass_pane", slot = 11, name = "&a." } },
        { delay = 300ms, clear = false, items.dot { material = "lime_stained_glass_pane", slot = 13, name = "&a.." } },
        { delay = 300ms, clear = false, items.dot { material = "lime_stained_glass_pane", slot = 15, name = "&a..." } },
        { delay = 600ms, require = { require = [{ condition { type = "permission", node = "loading.long" } }] }, items {} }
    ]
}
```

- `on-start` runs once the menu has opened, `on-end` when the last frame has played; neither for the frames of a loop, which
  start again from the menu as it is, without what the frames had drawn. A frame also has `on-start` and `on-end`.
- A frame whose `require` gate does not hold when it comes is skipped (its delay is still waited for, and no `deny` action
  runs). A frame's item shows over the menu's item in its slot as long as the item's own `view` gate passes, and
  its `click` actions work.
- Each frame schedules the next when it has played, on the player's region, so timing is not tied to a refresh. It stops
  when the menu closes.

### Bedrock and multi-device support

When Floodgate (preferred, works standalone and behind Velocity) or Geyser-Spigot is installed, XaliMenu detects each
player's edition (`JAVA` or `BEDROCK`), operating system (`ANDROID`, `IOS`, `WINDOWS`, `XBOX`, `PLAYSTATION`, `SWITCH`, ...),
device category (`PC`, `MOBILE`, `CONSOLE`, `VR`), input mode (`KEYBOARD_MOUSE`, `TOUCH`, `CONTROLLER`, `VR`) and UI profile
(`CLASSIC`, `POCKET`). Every Cumulus form callback is dispatched onto the player's Folia region thread before gates or actions run.

**Per-menu `bedrock` section.** Any menu can add a `bedrock { ... }` block to render as a native Bedrock form, redirect
specific devices to another menu, or tune how the inventory window behaves on Bedrock:

```hocon
title = "<shift:-8><glyph:shop_bg>&8Shop"
size = 27
bedrock {
    mode = "SIMPLE"                     # AUTO | INVENTORY | SIMPLE | MODAL | CUSTOM
    inputs = ["TOUCH", "CONTROLLER"]    # only touch/controller Bedrock players get the form; Bedrock PC keeps the chest
    title = "&8Shop"                    # clean title without Java resource-pack glyphs
    content = "&7Tap an item to trade"
    skip-fillers = true                 # omit border panes with no click handlers
    include-lore = true                 # show first lore line under each button label
    action-picker = true                # open a choice form for items with left + right clicks
}
items {
    diamond {
        material = "diamond", slot = 13, name = "&bDiamond", lore = ["&7100 coins"]
        bedrock { left-label = "&aBuy 1", right-label = "&cSell 1", image = "textures/items/diamond" }
        click.left.actions = [{ type = "player", command = "buy diamond" }]
        click.right.actions = [{ type = "player", command = "sell diamond" }]
    }
}
```

- **Form modes**: `SIMPLE` uses explicit `buttons` when given, otherwise converts the menu's visible items and paged
  elements automatically (skipping fillers and `bedrock.hidden = true` items, deduplicating multi-slot items, and adding
  previous/next page buttons for paged menus). `MODAL` shows a two-button dialog (`confirm` and `cancel`). `CUSTOM` shows
  interactive `fields` (`label`, `input`, `toggle`, `dropdown`, `slider`, `step-slider`) and runs `on-submit` with each
  field's answer as `{id}` (and `{id}_index` for dropdowns and step sliders).
- **Device targeting**: `devices`, `inputs` and `ui` restrict the `bedrock` block to matching Bedrock clients; non-matching
  Bedrock devices (such as Bedrock PC with keyboard, mouse and Classic UI) stay on the chest inventory. Set `bedrock.menu` to
  redirect matching devices to a dedicated menu file instead.
- **Interaction & UI adaptation**: On Bedrock inventory menus, Private Use Area font glyphs in titles are stripped when
  `bedrock.title` is absent (`bedrock.strip-private-use-title`), `inventory-slots` packet overlays are skipped on Pocket UI
  by default (`bedrock.pocket-inventory-overlay`), and clicking a multi-click item on touch/controller opens an action-picker
  form (or falls back to the item's sole click handler if only `right` or `shift-left` is defined).
- **Conditions & placeholders**: Use `{ type = "client", ... }` in any gate (`open`, `view`, `require`, `open-with`) and
  `%xalimenu_client_edition%`, `%xalimenu_client_device%`, `%xalimenu_client_os%`, `%xalimenu_client_input%`,
  `%xalimenu_client_ui%` in text.
- **Server defaults**: `bedrock.default-mode` in `config.yml` (`inventory`, `touch-form`, or `always-form`) sets what
  happens for menus that have no explicit `bedrock` form settings. `bedrock.icon-url` optionally points vanilla button icons
  at a hosted image template (`https://.../items/{material}.png`) instead of Bedrock's built-in `textures/...` paths.

### Opening a menu by what a player does

Besides `commands`, a menu lists in `open-with` the things that open it. Each entry is an object whose `type` names it:

**File** `plugins/XaliMenu/menus/hub.conf`

```hocon
open-with = [
    { type = "item", material = "compass", click = "RIGHT", sneaking = false },
    { type = "join", delay = 40 },
    { type = "chat", text = "!shop", match = "STARTS_WITH", args = ["{message}"] },
    { type = "block", types = ["#minecraft:buttons", "lever"], at { x = 12, y = 64, z = -3, world = "spawn" } },
    { type = "entity", types = ["villager"], name = "&aShop" },
    { type = "npc", npcs = ["banker"], click = "RIGHT", args = ["{npc}"] },
    { type = "swap-hands", sneaking = true }
]
```

| Type | Fires when | Keys | Values for `args` and gates |
|---|---|---|---|
| `item` | a player clicks with an item in hand | `material`, `name`, `model`, `click` (`LEFT`, `RIGHT`, `ANY`), `sneaking` | `{item}` |
| `join` | a player joins | `delay` (ticks, default 20) | |
| `chat` | a player says something that `text` matches | `text`, `match` (`EQUALS`, `EQUALS_IGNORE_CASE`, `CONTAINS`, `STARTS_WITH`, `REGEX`) | `{message}` |
| `swap-hands` | a player presses the hand-swap key | `sneaking` | |
| `block` | a player clicks, or steps on, a block | `types` (keys or `#tags`), `at` (`x`, `y`, `z`, `world`), `click` (`LEFT`, `RIGHT`, `ANY`, `PHYSICAL`), `sneaking` | `{block}`, `{world}`, `{x}`, `{y}`, `{z}` |
| `entity` | a player right-clicks an entity | `types`, `name`, `sneaking` | `{entity}`, `{entity_name}` |
| `region-enter`, `region-leave` | a player enters, or leaves, a WorldGuard region (joining inside one is not entering it) | `regions` (ids; any when empty) | `{region}`, `{world}` |
| `npc` | a player clicks an NPC of FancyNpcs | `npcs` (names or ids; any when empty), `click` (`LEFT`, `RIGHT`, `ANY`), `sneaking` | `{npc}`, `{npc_id}` |

Buttons, levers, signs and pressure plates are all `block`: name them in `types` or with a tag, and use `click =
"PHYSICAL"` for stepping on a plate. Every entry also takes:

- `require`, a gate that must hold for the menu to open. Its `deny` actions run when it doesn't.
- `cancel` (default `true`; `false` for `join`), whether the event is canceled when the menu opens, so the lever is not
  pulled and the message is not said. A `chat` entry cancels the message as soon as it matches, before the gate is asked,
  since the chat is not on the player's own thread.
- `args`, the words the menu gets for its `args`, which may use the values in the table.

Entries of all the menus are tried in the order of the menus' names and then as written; the first that matches and whose
gate holds opens its menu, and the rest are skipped. A player who has the menu open already does not get it opened again,
so stepping on a plate does not flicker it. A `name` is compared without its colors. `/xmenu reload` takes effect at once;
a mistake in an entry is reported with the menu's other problems when it is read.

### Variables

Global and per-player values that menus keep between clicks and restarts. Names are case-insensitive. A change is in
memory at once and is written in the background.

- `/xvar get|set|rem|inc|dec|mul|div <name> ...` and `/xvarp <player> get|set|...` (aliases `/var` and `/varp`;
  permission `xalimenu.admin`). `set <name> <value> [time] [replace]`, with a time such as `30m`, `1h` or `2d`.
  Numbers are exact decimals: sums are not rounded, products and quotients keep 8 places.
- The `variable` action: `{ type = "variable", name = "coins", op = "INC", value = "5" }` with `op` SET, REMOVE, INC,
  DEC, MUL or DIV, `scope` GLOBAL or PLAYER, and optional `player`, `expiry` and `replace`.
- PlaceholderAPI: `%xalimenu_var_<name>%` and `%xalimenu_varp_<name>%` (the player the text is for), and
  `%xalimenu_vart_<name>%` and `%xalimenu_varpt_<name>%` for the time left (`1h 2m 3s`). A `:<default>` after the name is
  shown when the variable does not exist: `%xalimenu_var_coins:0%`. Use them in `compare` and `text` conditions to gate
  on a variable.

An open menu follows its variables: when a variable changes (set, changed, removed, expired, or written by another server of a shared store),
the menus that name a variable placeholder in their file are drawn again about a tenth of a second later, for a personal variable only the
owner's, and a burst of changes is one redraw. Only what a draw rebuilds changes: items marked `update`, the `view` gates and a title with
placeholders. It is driven by a Kotlin `Flow` of the changes (`VariableStore.changes`), so it costs nothing while nothing changes.

#### Where a variable lives

`variables` in `config.yml` sets a store per name pattern (`*` any text, `?` one character, first match wins):

**File** `config.yml`

```yaml
variables:
  default: local                          # memory | local | network
  rules:
    - { match: 'tmp_*', store: memory }   # gone at restart
    - { match: 'net_*', store: network }  # the same on every server
  poll-interval: 1000                     # ms between looks at the shared database for others' changes
```

- **memory** is lost at restart. **local** is this server's SQLite file. **network** is the MySQL or MariaDB database in
  `database` (`type: mysql` or `mariadb`, which has its own driver), shared by every server that points at it. Without
  one of those database types, network rules fall back to local with a warning. Changing rules needs a restart and moves
  nothing.
- The SQLite file is read before the server goes on. The shared database is **connected to in the background**, so a
  slow or unreachable one never holds the server up (the connect gives up after 5 s and is tried again at 2, 4, ... 30 s
  intervals). Until it answers, its variables are *unavailable*: `/xvar` and the `variable` action tell the player so
  and change nothing, the API refuses the change, and the placeholders **are not answered at all** (the text stays
  `%xalimenu_var_net_x:0%`), not even with the `:default`. A test such as `claimed == 0` therefore fails closed instead
  of passing for want of an answer.
- Every change to a shared variable takes its turn in the database (one revision counter, locked until the change
  commits), so numbers that two servers change at once add up. Each server asks, every `poll-interval`, for what came
  after the last revision it saw, removals included (kept for 10 minutes). Consistency is eventual: `replace = false` and
  the result of a change are first decided by the local copy and corrected when the database answers. The servers'
  clocks should agree (expiry).
- A change that cannot be written because the database went away is kept and retried until it can. One the database
  refuses is dropped with a warning and the variable is read back. On shutdown the plugin waits up to 5 s per store for
  queued changes and logs how many it could not save.

## Usage

### Commands

All of these need `xalimenu.admin`. The command is `/xalimenu`, also `/xmenu` and `/menu`; the tables write `/xmenu`.

| Command | Description |
|---|---|
| `/xmenu list` | List the menus. |
| `/xmenu open <menu> [player] [args...]` | Open a menu for oneself or another player, optionally passing menu arguments. |
| `/xmenu reload [menu]` | Read the configuration, language and menus again; with a name, only that menu's file (the others stay as they are, and a file that cannot be read leaves the menu as it was). |
| `/xmenu info <menu>` | What a menu is: title, size, commands, args, items, matrix, pages, animation, bedrock mode, activators. |
| `/xmenu validate [menu]` | The problems of every menu (or one) and the files that could not be read, without opening any. The same problems are logged on every load. |
| `/xmenu inspect [player]` | Why each slot of a player's open menu shows what it shows (or the active Bedrock form), including the viewer's detected client profile. The first lines are in chat, all in the console. |
| `/xmenu client [player]` | Show a player's detected client edition, OS/device, input mode, UI profile and dialog/form support. |
| `/xmenu import deluxemenus` | Import DeluxeMenus menus, see [below](#importing-from-deluxemenus). |
| `/xvar ...`, `/xvarp ...` | Variables, see [above](#variables). |

Each one-word entry of a menu's `commands` is registered as a command and opens the menu. Words typed after it fill the
menu's `args` (`{name}` in text), one word per name. A last name ending in `...` (`args = ["player", "reason..."]`) takes
the rest of the line; without it, extra words show the usage message.

`complete` gives tab completion for them, by name: `complete { player = ["@players"], mode = ["fast", "slow"] }`. `@players`
is the players online and `@worlds` the worlds; a name with no entry offers nothing, and a name that is not one of the `args`
is reported by `validate`. A name that takes the rest of the line offers its words for each word of it. This works for the
one-word commands that are registered, not for the entries of several words.

Players type these words, so they are cleaned before they go into text and commands: `%` and control characters are
taken out, colors and MiniMessage tags in displayed text are taken out or shown as written, and in a `json` action they
are escaped. A menu author still has to put them in sensible places (a `regex` pattern built from an argument is the
author's to escape).

Entries of several words (`jobs browse`), and all of a menu's entries when it sets `register = false`, are caught as the
player types them. A command another plugin already has is taken over. Changing which one-word commands exist needs a
restart; the rest of a menu reloads live.

### Importing from DeluxeMenus

`/xmenu import deluxemenus [overwrite] [folder]` reads `config.yml` and `gui_menus/` of `plugins/DeluxeMenus`, or of
`folder`, and writes one file per menu. A menu that already has a file is kept unless `overwrite` is given. Old numeric
item ids and data values are converted by the server, so the first import of such a config makes Paper log its legacy
material warning.

The importer was checked against DeluxeMenus 1.14.1 itself: its example menus and a menu with most item options were opened
with both plugins, and the items, names, lore and the chat from clicks compared. Where they differ: names that have no
color code are italic in DeluxeMenus and not here, and a `material` that DeluxeMenus 1.14 rejects (an old name such as
`MONSTER_EGG`) is converted instead of being left out. The `data` option of a current material is its `damage`, as it is in
DeluxeMenus; only numeric ids and old names read it as a variant. `minimum_requirements` works as it does there: a
requirement that is not `optional` must hold, and at least that many must.

What cannot be carried over is listed at the top of each file and summed up in chat and the console: `[meta]` and
`[placeholder]` commands, the `javascript`, `is object` and `has meta` requirements, and a
few item options. A requirement of a type that cannot be run becomes `type = "never"`, so the menu fails closed instead
of opening or showing an item for everyone.

## Web editor

`/xmenu edit [menu]` prints a link to the editor at <https://menu.xali.to>: a form for every key of the format (built from the plugin's own
classes, with the comments as help), the window with its items, a **View as** device switcher (`Java (PC)`, `Bedrock (PC)`,
`Bedrock (Mobile)`, `Bedrock (Console)`) with live previews of Bedrock forms and device compatibility notes, problem checks while you type, and a view of the server's resource
pack for custom items and for moving glyphs in titles. With `editor.enabled` in `config.yml` the editor reads and saves the menus
on the server directly (the old file is kept as `<name>.conf.bak`); without it the link carries the menu and the editor gives a
file back. `/xmenu share [menu]` (or `share readonly [menu]`, which cannot send edits back) makes a link that works through a relay (`relay/`, set as `editor.relay.url`): the plugin only connects out,
everything is encrypted with a key that is only in the link, several people can use it, and what they save waits in `/xmenu edits` until an admin
runs `/xmenu edits apply <id>` or `drop <id>`. How to host it and how to reach a server from it: `editor/README.md`.

## Extending

An addon adds its own action, condition, catalog, activator or item source in `onEnable`. List XaliMenu as a dependency of the addon
(`join-classpath` on). Menus are read after every plugin has enabled, and again when one is disabled, so they are never
read without what they use.

An action, condition or catalog is a plain class plus a function that builds it from the keys written in the menu file. No
serializer and no Kotlin type crosses into XaliMenu, so a plugin with its own copy of the Kotlin runtime (any plugin
that uses the library loader) can register them, and so can a plugin written in Java. Compile against
`compileOnly(project(":menu:api"))` or the jar.

```kotlin
class Reward(private val points: Int) : Action {
    override fun run(ctx: MenuContext) = MyPlugin.give(ctx.player, points)
}

class MyPlugin : JavaPlugin() {
    override fun onEnable() {
        val api = XaliMenuApi.get()
        api.registerAction(this, "myplugin:reward") { settings -> Reward(settings.int("points", 1)) }   // { type = "myplugin:reward", points = 3 }
        api.registerCondition(this, "myplugin:lucky") { settings -> Lucky(settings.double("odds", 0.5)) }
        api.registerItemSource(this, MyItems)               // material = "myitems:ruby"
        api.registerCatalog(this, "myplugin:homes") { Homes() }   // pages { catalog { type = "myplugin:homes" } ... }
        emotes = api.registerActivator(this, "myplugin:emote") { settings ->   // open-with = [{ type = "myplugin:emote", name = "wave" }]
            val wanted = settings.requireString("name")
            ActivatorFilter { values -> values["emote"] == wanted }
        }
    }
}
```

```java
api.registerAction(this, "myplugin:reward", settings -> new Reward(settings.getInt("points", 1)));
```

Names are lower case, `plugin:name`. `Settings` reads the keys by kind (`string`, `int`, `long`, `double`, `boolean`,
`strings`, `section` and `sections`, each with a fallback overload). `requireString` throws when the key is missing, and
the menu is then reported as broken with that message. Everything in `net.xalitoria.menu` that an addon calls is made
of JDK, Bukkit and Adventure types and the API's own: functions are `Runnable` and `UnaryOperator`, and there are
overloads instead of default arguments. The test `menu/api/test/AddonSurface.kt` fails if a Kotlin type gets into a
signature. Addon actions, conditions and catalogs can be read from menu files but not written back by `/xmenu import`.

A `Catalog` gives a paged menu its elements: `elements(ctx)` returns a list of maps of named strings, each map one
element, and the menu's template uses the values as `{name}`. It is called on the viewer's region thread on every refresh
of the menu, so it must be cheap.

Say what the elements hold when registering, so that the editor can offer the values and draw the template with examples:
`api.registerCatalog(this, "myplugin:homes", mapOf("name" to "home1", "world" to "world")) { Homes() }`. The map is the name of each
value and an example of it. The editor reads it from `/v1/info`; without it a catalog still works, the editor just cannot
list its values. Say what its settings are too, with a second map of each key to an example, and the editor shows a field for each
instead of a block of JSON: `api.registerCatalog(this, "myplugin:homes", values, mapOf("world" to "world_nether")) { Homes() }`. A key that is
not listed can still be written; the editor keeps it as JSON.

An activator is event-driven, so the addon gets a source back and calls it when something happens:
`if (emotes.fire(player, mapOf("emote" to "wave"))) event.isCancelled = true`. The values are what the activators of the menus
are matched with (the filter their type built from the keys of the menu file, which must be cheap and must not change
anything) and reach the gate and the menu's `args` as `{emote}`. `fire` can be called from any thread: it matches on the
caller's, and asks the gate and opens the menu on the player's own. It returns whether the first matching activator asks for
the event to be canceled (`cancel`, true by default); the gate isn't known by then. `require`, `cancel` and `args` are read
as for the built-in activators, and the other keys go to the type. An activator whose type refuses its keys (or whose plugin
is gone) never fires, and `/xmenu validate` says why. A menu that names a type no plugin added doesn't load, as with an action.
`menu/live/addon/XmDemo.java` is a small plugin in Java that does this.

Actions run on the player's region thread (Folia); use `ctx.runLater` and `ctx.console` for anything that has to happen
later or elsewhere. A list of actions runs in order, and a failing one is logged. `ctx.abort()` ends the list, for a step
the rest depends on. The built-in `money`, `permission` and `experience` actions abort when they cannot take or give
what they should, so `[money take, give item]` does not give the item for free. `stop` aborts on purpose (handy inside a
`chance`). Conditions are asked on every refresh of a menu, so they must be cheap.

> [!NOTE]
> How it works: `format/Externals.kt` rewrites the objects whose `type` is a registered external name to
> `{type, values="<json>"}` (only for menus whose text mentions one), and `api/External.kt` decodes that through a
> default deserializer. Constructors with default arguments are avoided in the API because they carry
> `DefaultConstructorMarker`, a `kotlin.` type.

[XaliFriends](../friends/README.md) and [XaliRedeem](../redeem/README.md) use this API: they register their own actions
(`xalifriends:accept`, `xaliredeem:redeem`) and, for XaliRedeem, a catalog, and write their menus into `plugins/XaliMenu/menus`.

## Troubleshooting

### `itemsadder:<id>` items do not appear

ItemsAdder 4.0.18 refuses to load on Folia ("not marked as supporting Folia") and needs ProtocolLib. The `itemsadder:`
source therefore works on Paper only. Do not run Nexo and ItemsAdder together.

### A variable shows its raw placeholder text

The variable lives in the `network` store and the shared database is not connected yet (or is down). Placeholders of an
unavailable variable are not answered, on purpose. Check `database` in `config.yml` and the log; the connection is
retried every 2, 4, ... 30 seconds.

### The plugin does not enable with MariaDB

Use `database.type: mariadb`. Exposed with MariaDB 11 needs the MariaDB driver (`jdbc:mariadb`); the MySQL driver breaks
its keyword query.

### A command does nothing or belongs to another plugin

A menu's one-word `commands` entries take over labels that another plugin already has, at startup. Which one-word
commands exist is read from a quick scan of the files, so adding or removing one needs a restart.

### Not supported

No Citizens support (use FancyNpcs); an NPC that is an entity can be matched by `entity`. The plan for closing the gap to AbstractMenus is [`PLAN.md`](PLAN.md). The item-count condition and the
economy, permission and skin hooks, and the `group` and `meta` actions, are not tested against a live server. The personal variables of every player are
loaded at startup.

## Development

Sources are under `menu/<submodule>/kotlin`, tests under `menu/<submodule>/test` (see the
[root README](../README.md#source-layout)). [`CONTRIBUTING.md`](CONTRIBUTING.md) walks through adding an action, a condition,
a catalog, an item property or an activator, and the style of the documentation comments. [`PLAN.md`](PLAN.md) holds
the roadmap and what is left.

| Part | Where |
|---|---|
| Built-in actions and conditions | `menu/paper/.../action` (`Actions.kt`, `Flow.kt`, `Inventory.kt`, `Effects.kt`, `Social.kt`), `condition` (`Conditions.kt`, `Logic.kt`, `PlayerConditions.kt`) |
| Menu schema and reading | `menu/api/.../model`, `menu/paper/.../format` |
| Runtime: gates, items, clicks, paging, activators, animation | `menu/paper/.../runtime` |
| Bedrock planning, forms and hooks | `menu/paper/.../bedrock`, `menu/paper/.../hook/BedrockHook.kt` |
| Catalogs | `menu/paper/.../catalog` |
| Variables: stores and sync | `menu/paper/.../variable` |
| Addon API implementation | `menu/paper/.../api` |
| DeluxeMenus import | `menu/paper/.../deluxemenus`, free of the server API (`Materials` is an interface) |

Unit tests do not start a server; [`live/`](live/README.md) has the scripts that do, with a bot as the player.

```console
user $ ./gradlew :menu:api:test :menu:paper:test
user $ docker run -d --rm --name xali-mariadb-test -p 3307:3306 -e MARIADB_ROOT_PASSWORD=pw mariadb:11
user $ XALIMENU_TEST_MYSQL='jdbc:mariadb://127.0.0.1:3307/xali?user=root&password=pw' ./gradlew :menu:paper:test --rerun-tasks
```

Code review: [`.coderabbit.yaml`](.coderabbit.yaml) holds CodeRabbit's settings and what it is told about this code. With its CLI
installed and signed in (`coderabbit auth login`), `coderabbit review --agent -t committed` reviews what has been committed, run from
`menu/`; the same settings apply to a pull request on GitHub once the CodeRabbit app is installed on the repository.

Notes for changes:

- Construct a hook for an optional plugin only after checking that the plugin is present. VaultUnlocked 2.20 renamed the
  vault2 `Permission`; use `net.milkbowl.vault.permission.Permission`.
- Actions and conditions are separate hierarchies, so a name such as `permission` may be both.
- Exposed's `MigrationUtils` re-creates an index on SQLite on every attach, so the index is created by hand.
- Do not put a `private companion object` in a `@Serializable` class: the serializer then fails with an
  `ExceptionInInitializerError`.
- Shift-clicks from a bot reach XaliMenu as neither `SHIFT_LEFT` nor `SHIFT_RIGHT`, so menus should not rely on
  `shiftLeft`.

## See also

- [XaliFriends](../friends/README.md) and [XaliRedeem](../redeem/README.md) — plugins with menus built on XaliMenu
- [XaliChat](../chat/README.md) — the text formatting XaliMenu shares
- [Web editor](editor/README.md), [relay](relay/README.md) and [live tests](live/README.md)
- [Gradle plugin](../xali-gradle/README.md) — descriptors and library loading
- [Root README](../README.md) — the workspace

## External resources

- [AbstractMenus](https://github.com/AbstractMenus/minecraft-plugin) — the plugin XaliMenu is modeled on
- [HOCON](https://github.com/lightbend/config/blob/main/HOCON.md) — the format of the menu files
- [PlaceholderAPI](https://wiki.placeholderapi.com/) — the placeholders
