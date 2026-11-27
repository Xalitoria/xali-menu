# Live tests

**The live tests** show what the unit tests cannot: the plugin on a real Folia server, with a bot (mineflayer) as the player. Nothing here
runs in `./gradlew test`; it is for checking a change by hand, and for finding what a mock would have hidden.

## Contents

- [Installation](#installation)
- [Usage](#usage): [The scripts](#the-scripts)
- [Why the setup is odd](#why-the-setup-is-odd)

## Installation

### Requirements

- Node.js and npm (for mineflayer, which `setup.sh` installs into `$XM_DIR/bot`) and Python 3 (`bot/nbt.py`).
- `JAVA_HOME` pointing at a JDK that exists. `setup.sh` falls back to `/opt/openjdk-bin-25` when it does not, so export it first.
- A dev server in `run/folia` (see the [root README](../../README.md#dev-servers)): its Folia jar and libraries are copied.

### Setting up

```console
user $ menu/live/setup.sh
user $ menu/live/server.sh start
user $ node /tmp/xm-live/bot/act.js > /tmp/xm-live/act.txt; cat /tmp/xm-live/act.txt
user $ menu/live/server.sh stop
```

## Usage

Set these before `setup.sh` (and `server.sh`):

| Variable | Default | Effect |
|---|---|---|
| `XM_DIR` | `/tmp/xm-live` | Where everything is put. |
| `XM_PORT` | `25599` | The port the server uses. |
| `XM_PAPER=<jar>` | the dev server's Folia | Run a Paper server instead (DeluxeMenus and some others do not support Folia). |
| `XM_PLUGINS="Name ..."` | none | Copy plugins of the dev server by the start of their jar name. |
| `XM_RELAY=<url>` | none | The relay of `/xmenu share`. |
| `XM_EDITOR=1` | off | Turn the web editor's endpoint on. `XM_EDITOR_PORT`, `XM_EDITOR_PACK` (a pack zip) and `XM_GLYPHS` (a Nexo glyphs folder) tune it; `/xmenu edit` then prints a link, and `curl` with its token tries the endpoint. |
| `XM_ADDON=1` | off | Build and install `addon/XmDemo.java`, the demo plugin that adds an activator and `/xmsave`. |
| `XM_EXTENSION=1` | off | Build `extension/DemoExtension.java`, an extension for PlaceholderAPI, into `plugins/XaliMenu/extensions/demo.jar`. |
| `XM_ESSENTIALS=<jar>` | none | Install that EssentialsX jar (a Folia build on the Folia server) and build `extension/EssentialsExtension.java`, an extension for it, into `plugins/XaliMenu/extensions/essentials.jar`. |

The server is offline-mode and meant to be thrown away. Do not pipe a bot into `head`: closing the pipe ends it early.

### The scripts

To test another plugin than XaliMenu, copy its jar (the newest in `build/libs/<module>/`) into `$XM_DIR/server/plugins`
before `server.sh start`, and write a bot in `bot/`.

| Script | Menu | Shows |
|---|---|---|
| `bot/act.js` | `t` (`/xt`) | The actions: items given, taken by name and cleared, potion, teleport, game mode, health and food, levels, title, boss bar, book, particle, `run-for`, `random` and the chat `input` prompt with its timeout and cancel word. |
| `bot/text.js` | `u` (`/xu`) | The text `input`: an anvil (invalid and valid names, leaving it, the timeout, nothing can be taken out of it) and a dialog (the packet, an invalid reply, a valid one, the cancel button and the timeout), answered by the bot with the packets a client sends. With ViaVersion in the server's plugins the client's version is read. |
| `bot/deposit.js` | `d` (`/xd`) | Slots that take an item (`accept`): the cursor's item 2 at a time and the last 1 refused, another item refused, a payment that aborts and one that doesn't, shift-click from the inventory, and the inventory after the menu is closed. |
| `bot/cond.js` | `c` (`/xc`) | The conditions, by which items `view` leaves in the menu after each change of state: free slots, held item, game mode, cooldowns and variables. |
| `bot/livevars.js` | `v` (`/xv`) | An open menu with no `refresh` that names variables follows them: set, increase and remove from the console (global and personal) and a burst; an `update` item shows each at once, one that is not marked does not. Needs PlaceholderAPI and `op Tester`. |
| `bot/inventory.js` | `p` (`/xp`) | A menu that draws in the player's own inventory (needs PacketEvents or ProtocolLib in the server's plugins): what is drawn over items given first, a click on a drawn slot (runs its action) and elsewhere (nothing moves), an `update` item following a variable, a `view` gate (creative mode), and the real items back on close. `XM_MODE=saved` leaves the menu open after `/xmsave` (from `XM_ADDON=1`) so that the saved player data can be read: it must hold the real items, never the menu's. |
| `bot/inventory-rejoin.js` | `p` (`/xp`) | The same menu: gives a known inventory, clicks and picks up and shift-clicks real items while it is open, closes, and compares the inventory with the one before, as the client sees it and after joining again (what the server really holds). Prints `SAME` when nothing was lost or added. |
| `bot/props.js` | `i` (`/xi`) | The item properties: it gives one item of every kind; then read the saved player data with `python3 bot/nbt.py <server>/world/players/data/<uuid>.dat` (Folia has no `/data` for entities). |
| `bot/complete.js` | `a` (`/xa`) | Tab completion of a menu's `args` from its `complete` map: players, fixed words, the rest of the line. |
| `bot/addon.js` | `e` (`/xe`) | An activator added by another plugin, `addon/XmDemo.java` (built with `XM_ADDON=1` at setup): `/xmwave` fires it with values; matching, a gate, `cancel`, and the keys it refuses. |
| `bot/extension.js` | `x` (`/xx`) | The extension `extension/DemoExtension.java` (`XM_EXTENSION=1` at setup): a listener of the extension fires its `demo:join` activator, which opens the menu when the bot joins; the `demo:papi` condition, which reaches a class of PlaceholderAPI from the extension's jar, decides which item is shown; the `demo:say` action runs on a click. Then `server.sh cmd xmenu extensions`, `info demo` and `reload` show the extension's state in `server.log`, and a menu that names `demo:` types fails to read once the jar is removed and reloaded. |
| `bot/essentials.js` | `es` (`/xes`), `esw`, `esh`, `esk` | The EssentialsX extension (`XM_ESSENTIALS=<jar>` at setup; needs `op Tester`): the catalogs `essentials:warps`, `homes` and `kits` (a paged menu each), the conditions `state`, `home` and `kit`, and the actions `warp`, `home`, `sethome`, `delhome`, `kit`, `back` and `afk`, which answer in EssentialsX's own words. It prints where the bot stands after a warp, a home and `back`. Opening the kits menu says nothing in chat (EssentialsX's own checks would). |
| `bot/layout.js` | `l` (`/xl`) | A menu `matrix` (`cells` + `map`): the item in every slot of the window. |
| `bot/npc.js` | `n` (`/xn`) | FancyNpcs `npc` activators: creates an NPC and right- and left-clicks it (set up with `XM_PLUGINS=FancyNpcs`). |
| `bot/skin.js` | `s` (`/xs`) | The `skin` and `skin-reset` actions with SkinsRestorer (`XM_PLUGINS=SkinsRestorer`): what it stores for the player after each, and a skin that does not exist. |
| `bot/compare.js` | imported DeluxeMenus menus | Opens each menu with DeluxeMenus and with XaliMenu and prints what differs (items, names, lore, components, the chat from clicks). Needs `XM_PLUGINS=DeluxeMenus` on Paper (`XM_PAPER=<paper jar>`, DeluxeMenus does not support Folia) and `xmenu import deluxemenus` run first; `dm/compare_menu.yml` is a menu with most item options for it. |
| `bot/region.js` | `r` (`/xr`) | WorldGuard: `region-enter` and `region-leave` activators and the `region` condition (`XM_PLUGINS="FastAsyncWorldEdit worldguard"` on Paper). |
| `bot/editor.js` | none | Joins, prints the link of `/xmenu edit` (set up with `XM_EDITOR=1`, and `op Tester` from the console once it has joined) and stays online so that `curl` with the link's token can try the endpoint for a player who is on the server, such as `POST /v1/elements` with `{"catalog": {"type": "players"}}`. Ends when its stdin closes. |
| `bot/nexo.js` | `nx` (`/xnx`) | Needs Nexo (its jar and folder copied into the server's plugins; in its `settings.yml` set `pack.dispatch.send_pre_join: false` and `mandatory: false`, since a bot cannot accept the pack). Opens the menu and prints the slots: items of Nexo with their `item_model`, a name, a count, and an item that does not exist (a barrier, with a warning in the log that says why and what it was probably meant to be). |
| `bot/relay.js` | none | `/xmenu share` through the relay (`XM_RELAY=<url>` at setup, `node menu/relay/relay.js` running), then the page's side of the link (`editor/web/src/relay.js`): reads the menus, saves one, which is held; `/xmenu edits`, `apply` and `drop` are then run from the console. Needs `op Tester`. |
| `bot/tools.js` | `c` | `/xmenu inspect`, `info`, `validate` and `reload <menu>` (the answers are in `server/server.log`). |

`menus/` has the menus these use; `i.conf` holds one item that is wrong on purpose, for `/xmenu validate`.

## Why the setup is odd

- **mineflayer** knows protocol 775 (Minecraft 26.1) and the dev server's Folia speaks 776 (26.2), so `setup.sh` gives the 26.1
  data the number 776 and joins as `26.1`. Packet warnings are noise (`hideErrors`).
- The **plugin** asks the server for `com.xalitoria:util` at the version of the workspace, which may not be published yet. `setup.sh`
  puts the jars the workspace just built into the server's library folder, where the loader finds them.
- Item names from mineflayer are those of 26.1; the window title and custom names are right.
- Kill the server with `server.sh stop`, never by pattern with `pkill -f`.

## See also

- [XaliMenu](../README.md) — the plugin these scripts test
- [Web editor](../editor/README.md) and [relay](../relay/README.md) — the pieces `bot/editor.js` and `bot/relay.js` exercise
- [Root README](../../README.md#dev-servers) — the dev servers
