# Contributing to XaliMenu

This file describes how the code is laid out, how to add the common kinds of feature, how to test a change, and the style
for documentation comments. For what the plugin does, see `README.md`. For the work done and left, see `PLAN.md`.

## Layout

The project has two modules.

| Module | Contains |
|---|---|
| `api` | What addons and the menu files are made of: the `Action`, `Condition`, `Catalog` interfaces, `XaliMenuApi`, and the menu model in `model/` (`Menu`, `Item`, `Gate`, `Activator`, ...). It does not depend on the server, except for Bukkit types. |
| `paper` | The plugin. It implements the API and runs menus on Paper and Folia. |
| `editor` | The web editor (`web/`, static files for GitHub Pages) and the code that writes its `schema.json` from the plugin's classes and comments. See `editor/README.md`. |

The packages of `paper`, under `mc.xalitoria.menu.paper`:

| Package | Contains |
|---|---|
| `action`, `condition`, `catalog` | The built-in actions, conditions and catalogs. One file per group, one class per type. |
| `runtime` | Everything that happens while a menu is open: `MenuManager` (loading and opening), `MenuView` (one open menu), `ItemFactory` (items), `Gates`, `Activators` and `ActivatorListener`, `Context`. |
| `format` | Reading and writing menu files (HOCON). |
| `api` | The implementation of `XaliMenuApi` and the registry of types, including what other plugins add. |
| `command` | `/xmenu`, `/xmenu variable` and the commands that open menus. |
| `config`, `variable` | `config.yml`, `messages.yml`, and the variable store with its SQL and file backends. |
| `hook` | One class per other plugin that is used: Vault, SkinsRestorer, WorldGuard, FancyNpcs, HeadDatabase. |
| `deluxemenus` | The importer for DeluxeMenus files. |

The plugin side of the editor is in `paper/.../editor`. After a change to the menu model, a built-in action or condition, or a comment on either, run `./gradlew :menu:editor:schema`; `:menu:editor:test` fails when `editor/web/schema.json` is out of date.

Tests are in `paper/test`, `api/test` and `editor/test`; the page has its own in `editor/web/test`, run with `node --test`. Menus and bots for testing on a real server are in `live/`.

## Adding a feature

An action, a condition or a catalog:

1. Add a `@Serializable` class with a `@SerialName` to the file of its group. The `type` of the menu file is the serial name.
2. Register it in the `register` function of that file.
3. Add a test that reads it from HOCON and writes it back (see `paper/test/MoreActions.kt`).
4. Add a row to the matching table in `README.md`.

An item property:

1. Add the field to `Item` in `api` with a KDoc comment. A number has its limits with `@Within(min, max)` (the plugin's check and the editor's boxes use it); a number or a switch that may follow a placeholder is a ``DynamicInt` or `DynamicBoolean``.
2. Apply it in `ItemFactory.construct` and report wrong values in `ItemFactory.validate`.
3. Add it to `paper/test/ItemProperties.kt` and `live/menus/i.conf`. `./gradlew :menu:editor:schema` then shows it in the editor's form; `ConfigCheck` fails if a menu that ships with the plugin gets a warning.

An activator:

1. Add a subclass of `Activator` and a list in `ActivatorIndex`.
2. Add a pure `matches` function in `Activators.kt`, and call it from `ActivatorListener`.
3. Add a case in `MenuManager.activatorProblems`.

## Testing

Run the unit tests from the root of the workspace:

```console
user $ ./gradlew :menu:api:test :menu:paper:test
```

Unit tests cannot show what a real server does. For a change that touches the game, also run it with the bot, see
`live/README.md`. Fix what the test finds before the change is committed.

## Documentation comments

Write KDoc in the style of the Gentoo wiki: short, factual and neutral.

- Document every public class, function and property, except where the name says everything.
- Start with what the thing is or does. Use the present tense: "Returns the slot", not "Will return the slot".
- Write full sentences with an ending period. Use sentence case, not title case.
- Use the third person. Do not use "we", "I" or "you" except in an instruction to the reader of an API ("Register the type in `onEnable`").
- Do not use contractions: write "does not", not "doesn't".
- Do not use words that judge or sell: "simply", "just", "easy", "powerful", "obviously", "of course".
- Prefer one idea per sentence. Put the exceptions after the rule, in the next sentence.
- Name the unit of a number: ticks, slots, blocks, milliseconds.
- Put keys, values, commands and code in backticks. Link other declarations with `[Name]`.
- Say what happens on failure when it is not obvious.
- Keep code comments (`//`) for the reason of a line, not for what it does.

## Version control

The repositories of the workspace use jj. Commit when a step works and its tests pass. A commit message states what the commit
does in one line, in the imperative: "Menu: add the tool item component".
