# XaliMenu

Inventory menus for Folia (and Paper), configured in HOCON and modelled on AbstractMenus: activators open menus,
items carry gates (requirements) and click actions. Menus are read with kotlinx.serialization's HOCON support, text
goes through the same colour handling as XaliChat (legacy codes and MiniMessage), and PlaceholderAPI is required.
VaultUnlocked (several currencies), ItemsAdder, Nexo, SkinsRestorer and HeadDatabase are used when present.

## Modules

- `api` (`mc.xalitoria.menu.api`): what other plugins compile against: the menu model, `Action`, `Condition`, `MenuContext`,
  `ItemSource`, `Variables` and `XaliMenuApi`. It is shaded into the plugin's jar, so an addon needs XaliMenu as a dependency
  and `compileOnly(project(":menu:api"))` (or the jar).
- `paper` (`mc.xalitoria.menu.paper`): the plugin. Its built-in actions and conditions are written against the same API.

## Extending

An addon adds its own action, condition or item source in `onEnable` (list XaliMenu as a dependency, `join-classpath` on). Menus
are read after every plugin has enabled, and again if one is disabled, so they are never read without what they use.

An action or condition is a plain class plus a function that builds it from the keys written in the menu file. No serializer
and no Kotlin type crosses into XaliMenu, so a plugin with its own copy of the Kotlin runtime (any that uses the library loader)
can register them, and so can one written in Java:

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
    }
}
```

```java
api.registerAction(this, "myplugin:reward", settings -> new Reward(settings.getInt("points", 1)));
```

Names are lower case, `plugin:name`. `Settings` reads the keys by kind (`string`, `int`, `long`, `double`, `boolean`, `strings`,
`section`, `sections`, each with a fallback overload; `requireString` throws when the key is missing, and the menu is then
reported as broken with that message). Everything in `mc.xalitoria.menu.api` that an addon calls is made of JDK, Bukkit and
Adventure types and the API's own: functions are `Runnable` and `UnaryOperator`, there are overloads instead of default
arguments, and a test (`menu/api/test/AddonSurface.kt`) fails if a Kotlin type gets into a signature. Addon actions and
conditions can be read from menu files but not written back by `/xmenu import`.

Actions run on the player's region thread (Folia); use `ctx.runLater` and `ctx.console` for anything that has to happen later
or elsewhere. A list of actions runs in order and a failing one is logged; `ctx.abort()` ends the list, for a step the rest
depends on. The built-in `money`, `permission` and `experience` actions abort when they can't take or give what they
should, so `[money take, give item]` doesn't give the item for free; `stop` aborts on purpose (handy inside a `chance`).
Conditions are asked on every refresh of a menu, so they must be cheap.

## GUI packs

`packs` in `config.yml` installs the zipped GUI packs of a release (the friend menus' glyphs and icons, built by
`:friends:resourcepack:zipPacks`, see [friends/resourcepack](../friends/resourcepack/README.md)) into the plugin that serves
your resource pack. Nothing is bundled and nothing is downloaded unless you set a handler:

```yaml
packs:
  handler: none       # none (the default), nexo or itemsadder
  url: https://github.com/audizian/xalicraft/releases/latest/download
  install: [friends-gui]
```

For `friends-gui` and `nexo` the plugin fetches `<url>/friends-gui-nexo.zip.sha256` and, unless that is the checksum it
installed last time, `<url>/friends-gui-nexo.zip`, verifies it and unpacks it into `plugins/Nexo` (`plugins/ItemsAdder` for
`itemsadder`), off the main thread. A name that would leave that folder, a zip that doesn't match its checksum, a `url` that
isn't `https://` and a missing handler plugin are refused with a message; a failed download changes nothing. Reload the
handler afterwards (`/nexo reload`, `/iazip`). `/xmenu pack update` does it again on demand (`xalimenu.admin`).

The default `url` is the repository's release; the repository is private, so a server that is not logged in to it gets a 404.
Point `url` at wherever the zips are published.

## Variables

Global and per-player values that menus keep between clicks and restarts. Names are case-insensitive; a change is in
memory at once and written in the background.

- `/xvar get|set|rem|inc|dec|mul|div <name> ...` and `/xvarp <player> get|set|...` (alias `/var`, `/varp`; `xalimenu.admin`).
  `set <name> <value> [time] [replace]`, with a time like `30m`, `1h`, `2d`. Numbers are exact decimals (no rounding of
  sums; products and quotients keep 8 places).
- The `variable` action: `{ type = "variable", name = "coins", op = "INC", value = "5" }` with `op` SET, REMOVE, INC, DEC, MUL or
  DIV, `scope` GLOBAL or PLAYER, optional `player`, `expiry` and `replace`.
- PlaceholderAPI: `%xalimenu_var_<name>%`, `%xalimenu_varp_<name>%` (the player the text is for), and `%xalimenu_vart_<name>%` /
  `%xalimenu_varpt_<name>%` for the time left (`1h 2m 3s`). `:<default>` after the name is shown when the variable doesn't
  exist: `%xalimenu_var_coins:0%`. Use them in `compare` and `text` conditions to gate on a variable.

### Where a variable lives

`variables` in `config.yml` sets a store per name pattern (`*` any text, `?` one character, first match wins):

```yaml
variables:
  default: local                          # memory | local | network
  rules:
    - { match: 'tmp_*', store: memory }   # gone at restart
    - { match: 'net_*', store: network }  # the same on every server
  poll-interval: 1000                     # ms between looks at the shared database for others' changes
```

- **memory** is lost at restart; **local** is this server's SQLite file; **network** is the MySQL/MariaDB database in
  `database` (`type: mysql` or `mariadb`, which has its own driver), shared by every server that points at it. Without a
  database `type` of those, network rules fall back to local with a warning. Changing rules needs a restart and moves nothing.
- The SQLite file is read before the server goes on. The shared database is **connected to in the background**, so one that
  is slow or down never holds the server up (the connect gives up after 5 s and is tried again at 2, 4, ... 30 s intervals).
  Until it answers, its variables are *unavailable*: `/xvar` and the `variable` action tell the player so and change
  nothing, the API refuses the change, and the placeholders **are not answered at all** (the text stays `%xalimenu_var_net_x:0%`),
  not even with the `:default`, so a test such as `claimed == 0` fails closed instead of passing for want of an answer.
- Every change to a shared variable takes the turn in the database (one revision counter, locked until the change commits),
  so numbers that two servers change at once add up. Each server asks for what came after the last revision it saw every
  `poll-interval`, removals included (kept for 10 minutes). Consistency is eventual: `replace = false` and the result of a
  change are first decided by the local copy and corrected when the database answers. Servers' clocks should agree (expiry).
- A change that can't be written because the database went away is kept and retried until it can; one the database refuses is
  dropped with a warning and the variable is read back. On shutdown the plugin waits up to 5 s per store for queued changes
  and logs how many it could not save.

## Configuration

`config.yml` holds `debug`, `language`, `click-cooldown`, the `database` and the `variables` rules; `language/<language>.yml` holds every message and is written with the
defaults on first start. Both are YAML like XaliChat's and `/xmenu reload` reads them again. Messages (and menu text)
take `&` codes, `&#RRGGBB`, CMI gradients (`{#a0a>}text{#fff<}`) and MiniMessage.

## Menu files

`plugins/XaliMenu/menus/<name>.conf` (an `example.conf` is written on the first start); the schema is the classes in
`mc.xalitoria.menu.model`, with keys in kebab-case (`dynamic-amount`, `shift-left`, `on-open`):

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

## Commands

- `/xmenu list`, `/xmenu open <menu> [player]`, `/xmenu reload` (config, language and menus), `/xmenu import deluxemenus`
  (all `xalimenu.admin`).
- Each one-word entry of a menu's `commands` is registered as a command and opens the menu; words typed after it fill the
  menu's `args` (`{name}` in text), one word per name. A last name ending in `...` (`args = ["player", "reason..."]`) takes
  the rest of the line; without it extra words show the usage message. Players type these words, so they are cleaned before
  they go into text and commands: `%`, control characters, and in displayed text colors and MiniMessage tags are taken out or
  shown as written, and in a `json` action they are escaped. A menu author still has to put them in sensible places (a
  `regex` pattern built from an argument is the author's to escape). Entries of several words (`jobs browse`), and all of a menu's entries when it sets
  `register = false`, are caught as the player types them. A command another plugin already has is taken over. Changing
  which one-word commands exist needs a restart; the rest of a menu reloads live.

## How a menu behaves

Opening checks the menu's `open` gate, then draws the items: of those claiming a slot, the one with the lowest `priority`
(ties by name) whose `view` gate passes. `refresh` draws again, rebuilding only items marked `update`. A click runs the
item's handler for that kind of click (or `any`) if its `require` gate passes. Nothing can be moved in or out of a menu.
Everything runs on the player's region thread; console commands go to the global region (Folia).

Item sources: a vanilla key, `head:<player>` (an online player's own skin, otherwise SkinsRestorer's or Mojang's, looked up
off-thread), `texture:<base64>`, `hdb:<id>`, `itemsadder:<id>`, `nexo:<id>`. Money conditions and actions use VaultUnlocked
(`currency` picks one when there are several); permission actions use whatever provides Vault's `Permission`.

## Importing from DeluxeMenus

`/xmenu import deluxemenus [overwrite] [folder]` (permission `xalimenu.admin`) reads `config.yml` and `gui_menus/` of
`plugins/DeluxeMenus`, or of `folder`, and writes one file per menu. A menu that already has a file is kept unless
`overwrite` is given. Old numeric item ids and data values are converted by the server, so the first import of such
a config makes Paper log its legacy material warning.

What can't be carried over is listed at the top of each file and summed up in chat and the console: `[meta]` and
`[placeholder]` commands, `open_with_item`, the `javascript`, `is near`, `is object` and `has meta`
requirements, and a few item options. A requirement of a type that can't be run becomes `type = "never"`, so the menu
fails closed instead of opening or showing an item for everyone.

## Running

`./gradlew :menu:paper:runServer` downloads the pinned Folia build and PlaceholderAPI/VaultUnlocked into the shared dev
server, `run/folia` (see the [root README](../README.md)), installs the plugin and starts it on the JetBrains Runtime
(debugger on port 5005). Plugins that can't be downloaded without an account (ItemsAdder, HeadDatabase, Nexo,
SkinsRestorer) go into `run/folia/plugins` by hand.
