# XaliMenu

**XaliMenu** is an inventory menu plugin for Folia and Paper. Menus are configured in HOCON and modeled on
AbstractMenus: activators open menus, and items carry gates (requirements) and click actions. Menus are read with
kotlinx.serialization's HOCON support, and text goes through the same color handling as
[XaliChat](../chat/README.md) (legacy codes and MiniMessage). Other plugins add their own actions, conditions and item
sources through an [addon API](#extending).

## Installation

### Requirements

| Plugin | Needed | Purpose |
|---|---|---|
| PlaceholderAPI | Yes | Placeholders in menu text and conditions; XaliMenu's own `%xalimenu_...%` placeholders. |
| VaultUnlocked (or Vault) | No | Money conditions and actions (several currencies), permission actions. |
| ItemsAdder | No | The `itemsadder:<id>` item source. Runs on **Paper only**, see [Troubleshooting](#troubleshooting). |
| Nexo | No | The `nexo:<id>` item source. |
| SkinsRestorer | No | Skins for `head:<player>`. |
| HeadDatabase | No | The `hdb:<id>` item source. |

### Modules

| Module | Description |
|---|---|
| `menu/api` | `mc.xalitoria.menu.api`: what other plugins compile against. The menu model, `Action`, `Condition`, `MenuContext`, `ItemSource`, `Variables` and `XaliMenuApi`. Shaded into the plugin's jar. |
| `menu/paper` | `mc.xalitoria.menu.paper`: the plugin. Its built-in actions and conditions are written against the same API. |

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

## Configuration

### Files

| File | Holds |
|---|---|
| `plugins/XaliMenu/config.yml` | `debug`, `language`, `click-cooldown`, `database` and `variables`. |
| `plugins/XaliMenu/language/<language>.yml` | Every message, written with the defaults on the first start. |
| `plugins/XaliMenu/menus/<name>.conf` | One menu per file. `example.conf` is written on the first start. |

Both configuration files are YAML, like XaliChat's. `/xmenu reload` reads configuration, language and menus again.
Messages and menu text accept `&` codes, `&#RRGGBB`, CMI gradients (`{#a0a>}text{#fff<}`) and MiniMessage.

### Menu files

The schema is the classes in `mc.xalitoria.menu.api.model`, with keys in kebab-case (`dynamic-amount`, `shift-left`,
`on-open`). It is XaliMenu's own schema and is **not** compatible with AbstractMenus' HOCON.

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

### How a menu behaves

Opening checks the menu's `open` gate, then draws the items. Of the items claiming a slot, the one with the lowest
`priority` (ties by name) whose `view` gate passes is drawn. `refresh` draws again, rebuilding only items marked
`update`. A click runs the item's handler for that kind of click (or `any`) if its `require` gate passes. Nothing can be
moved in or out of a menu. Everything runs on the player's region thread; console commands go to the global region
(Folia).

Item sources: a vanilla key, `head:<player>` (an online player's own skin, otherwise SkinsRestorer's or Mojang's, looked
up off-thread), `texture:<base64>`, `hdb:<id>`, `itemsadder:<id>` and `nexo:<id>`. An id that cannot be resolved is
drawn as a barrier. Menus are checked when they are read, but the checks do not ask the item sources, since those load
later. Money conditions and actions use VaultUnlocked (`currency` picks one when there are several). Permission actions
use whatever provides Vault's `Permission`.

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

All of these need `xalimenu.admin`.

| Command | Description |
|---|---|
| `/xmenu list` | List the menus. |
| `/xmenu open <menu> [player]` | Open a menu for oneself or another player. |
| `/xmenu reload` | Read the configuration, language and menus again. |
| `/xmenu import deluxemenus` | Import DeluxeMenus menus, see [below](#importing-from-deluxemenus). |
| `/xvar ...`, `/xvarp ...` | Variables, see [above](#variables). |

Each one-word entry of a menu's `commands` is registered as a command and opens the menu. Words typed after it fill the
menu's `args` (`{name}` in text), one word per name. A last name ending in `...` (`args = ["player", "reason..."]`) takes
the rest of the line; without it, extra words show the usage message.

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

What cannot be carried over is listed at the top of each file and summed up in chat and the console: `[meta]` and
`[placeholder]` commands, `open_with_item`, the `javascript`, `is near`, `is object` and `has meta` requirements, and a
few item options. A requirement of a type that cannot be run becomes `type = "never"`, so the menu fails closed instead
of opening or showing an item for everyone.

## Extending

An addon adds its own action, condition or item source in `onEnable`. List XaliMenu as a dependency of the addon
(`join-classpath` on). Menus are read after every plugin has enabled, and again when one is disabled, so they are never
read without what they use.

An action or condition is a plain class plus a function that builds it from the keys written in the menu file. No
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
    }
}
```

```java
api.registerAction(this, "myplugin:reward", settings -> new Reward(settings.getInt("points", 1)));
```

Names are lower case, `plugin:name`. `Settings` reads the keys by kind (`string`, `int`, `long`, `double`, `boolean`,
`strings`, `section` and `sections`, each with a fallback overload). `requireString` throws when the key is missing, and
the menu is then reported as broken with that message. Everything in `mc.xalitoria.menu.api` that an addon calls is made
of JDK, Bukkit and Adventure types and the API's own: functions are `Runnable` and `UnaryOperator`, and there are
overloads instead of default arguments. The test `menu/api/test/AddonSurface.kt` fails if a Kotlin type gets into a
signature. Addon actions and conditions can be read from menu files but not written back by `/xmenu import`.

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

[XaliFriends](../friends/README.md) uses XaliMenu without registering anything: its menus run `/xfriend` through the
built-in `player` action.

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

No animated or generated menus, no pagination and no WorldGuard-style activators. The item-count condition and the
economy, permission and skin hooks are not tested against a live server. The personal variables of every player are
loaded at startup.

## Development

Sources are under `menu/<submodule>/kotlin`, tests under `menu/<submodule>/test` (see the
[root README](../README.md#source-layout)).

| Part | Where |
|---|---|
| Built-in actions and conditions | `menu/paper/.../action/Actions.kt`, `condition/Conditions.kt` |
| Menu schema and reading | `menu/api/.../model`, `menu/paper/.../format` |
| Runtime: gates, items, clicks | `menu/paper/.../runtime` |
| Variables: stores and sync | `menu/paper/.../variable` |
| Addon API implementation | `menu/paper/.../api` |
| DeluxeMenus import | `menu/paper/.../deluxemenus`, free of the server API (`Materials` is an interface) |

```console
user $ ./gradlew :menu:api:test :menu:paper:test
user $ docker run -d --rm --name xali-mariadb-test -p 3307:3306 -e MARIADB_ROOT_PASSWORD=pw mariadb:11
user $ XALIMENU_TEST_MYSQL='jdbc:mariadb://127.0.0.1:3307/xali?user=root&password=pw' ./gradlew :menu:paper:test --rerun-tasks
```

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

- [XaliFriends](../friends/README.md) — friend menus built on XaliMenu
- [XaliChat](../chat/README.md) — the text formatting XaliMenu shares
- [Gradle plugin](../xali-gradle/README.md) — descriptors and library loading
- [AbstractMenus](https://github.com/AbstractMenus/minecraft-plugin) — the plugin XaliMenu is modeled on
