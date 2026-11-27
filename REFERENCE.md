# Menu file reference

Every key of a menu file, XaliMenu 0.6.0. This page is written by `node editor/tools/reference.mjs` from `editor/web/schema.json`,
which comes from the plugin's classes and their comments, so it lists what the plugin reads. Do not edit it by hand: change the comment on
the key, then run `./gradlew :menu:editor:schema` and the command above.

A file is HOCON, `menus/<name>.conf`, with keys in kebab-case (`max-stack-size`) and enum values in upper case (`SLOT`).
The plugin warns about a key it does not read and names the one it thinks you meant; see "What the plugin says about a file" in the [README](README.md).
A value that is a list may be written as the one value, and an enum in any case is read.

## Contents

- [Menu](#menu)
- [Accept](#accept)
- [Animation](#animation)
- [AttributeModifier](#attributemodifier)
- [BannerLayer](#bannerlayer)
- [BedrockButton](#bedrockbutton)
- [BedrockChoice](#bedrockchoice)
- [BedrockItem](#bedrockitem)
- [BedrockMenu](#bedrockmenu)
- [BlockAt](#blockat)
- [Book](#book)
- [Check](#check)
- [Clicks](#clicks)
- [Equippable](#equippable)
- [Firework](#firework)
- [FireworkEffect](#fireworkeffect)
- [Fish](#fish)
- [Food](#food)
- [Frame](#frame)
- [Gate](#gate)
- [Handler](#handler)
- [Item](#item)
- [Matrix](#matrix)
- [MenuMatrix](#menumatrix)
- [Pages](#pages)
- [PotionEffect](#potioneffect)
- [Tool](#tool)
- [ToolRule](#toolrule)
- [Trim](#trim)
- [Actions](#actions)
- [Conditions](#conditions)
- [Activators](#activators)
- [Catalogs](#catalogs)
- [Bedrock form fields](#bedrock-form-fields)

## Menu

A menu, as stored in `menus/<name>.conf`; the file name is the menu's name.

`size` is the number of slots of a chest menu, a multiple of 9 up to 54; other `type`s have a size of their own. Each of `commands` opens the menu, and `args` name the words that must follow it, one word each. A last name that ends in `...` (`reason...`) takes the rest of the line instead. Players type these words, so they are cleaned (no `%`, no control characters) before they are put into `{name}` in text and commands.

- `title`: text; required

  The window title. Colors, placeholders and `{name}` arguments are allowed.

- `size`: whole number; default `54`; 9 to 54

  The number of slots of a chest menu.

- `type`: text; default `"chest"`

  `chest`, or the name of another inventory type, such as `hopper` or `dispenser`.

- `commands`: list of text; default `[]`

  The commands that open the menu. An entry of several words is not registered as a command.

- `register`: `true` or `false`; default `true`

  Whether `commands` are registered as commands of the server, or only usable through `open` actions.

- `args`: list of text; default `[]`

  The names of the words that follow a command, in order.

- `args-usage`: text

  The usage text shown when a player gives too few or too many words. Built from `args` when absent.

- `complete`: map from a name to list of text; default `[object Object]`

  What tab completion offers for each of `args`, by name: the words themselves, or `@players` for the names of the players online and `@worlds` for those of the worlds. An argument with no entry gets nothing.

- `refresh`: time, such as `5s`, `500ms` or `1m`

  How often the menu draws the items marked `update` again; never when absent.

- `open`: [Gate](#gate)

  Must hold for the menu to open.

- `on-open`: list of [an action](#actions); default `[]`

  Run when the menu has opened.

- `on-close`: list of [an action](#actions); default `[]`

  Run when the player closes the menu or it is replaced by another.

- `items`: map from a name to [Item](#item); default `[object Object]`

  The items of the menu, by name. The name is used to refer to an item from `matrix` and in messages.

- `matrix`: [MenuMatrix](#menumatrix)

  The menu as a single character grid mapped to `items`: `cells` are rows of at most nine characters and `map` assigns each character to an item name, placing that item in every matching slot besides its own `slot` and `slots`.

- `open-with`: list of [an activator](#activators); default `[]`

  What a player can do to open the menu besides its `commands`.

- `animation`: [Animation](#animation)

  Frames that play over the menu once it is open.

- `pages`: [Pages](#pages)

  Makes the menu list the elements of a catalog, a page at a time.

- `bedrock`: [BedrockMenu](#bedrockmenu)

  How this menu is displayed and interacted with by Bedrock Edition players (through Floodgate or Geyser).

## Accept

What an item that `Item.accept`s takes. The offered item is the one the server has (on the player's cursor, or in the slot they shift-clicked), never what the client says. It is taken only once `then` has run to its end: an action that aborts or fails leaves it where it was, so put what can refuse (a check, a payment) first. In `then` `{item}` is the material of what was offered and `{amount}` how many are taken.

- `then`: list of [an action](#actions); required

  Runs, in order, for an item that fits.

- `material`: text

  Which materials fit; any item when absent. May contain placeholders.

- `name`: text

  The name an item must have, as it reads without colors.

- `model`: whole number

  The custom model data an item must have.

- `amount`: whole number; default `1`; 1 to 99

  How many are taken from the stack, which must hold at least that many.

- `reject`: list of [an action](#actions); default `[]`

  Runs for an item that does not fit.

## Animation

Frames that play over a menu once it is open, one after the other: each `Frame` waits for its `delay`, then draws its items on top of the menu. A `loop` starts again from the menu as it is, without what the frames had drawn. `onStart` runs when the menu has opened, `onEnd` when the last frame has played (never for a loop).

- `frames`: list of [Frame](#frame); required

- `loop`: `true` or `false`; default `false`

  Whether the animation starts again after the last frame.

- `on-start`: list of [an action](#actions); default `[]`

- `on-end`: list of [an action](#actions); default `[]`

## AttributeModifier

A change to an attribute, such as `attack_damage` or `max_health`. `slot` is where the item must be for it to count: `any`, `mainhand`, `offhand`, `hand`, `head`, `chest`, `legs`, `feet`, `armor` or `body`.

- `attribute`: text; required

  The attribute key, such as `attack_damage`.

- `amount`: number; required

- `operation`: one of `ADD`, `MULTIPLY_BASE`, `MULTIPLY_TOTAL`; default `"ADD"`

- `slot`: text; default `"any"`

## BannerLayer

A pattern on a banner.

- `color`: text; required

  A dye color, such as `red`.

- `pattern`: text; required

  A banner pattern key, such as `stripe_top`.

## BedrockButton

One button of a Bedrock `SIMPLE` form.

- `text`: text; required

  Label shown on the button. Use `\n` for a second line. Colors and placeholders are allowed.

- `image`: text

  Optional button icon: an `https://` URL, a Bedrock texture path (`textures/items/diamond`), or a vanilla item key (`diamond`).

- `view`: [Gate](#gate)

  Must hold for the button to appear in the form.

- `actions`: list of [an action](#actions); default `[]`

  Actions run in order when the button is tapped.

- `require`: [Gate](#gate)

  Must hold for `actions` to run when tapped.

## BedrockChoice

One of the two buttons of a Bedrock `MODAL` form.

- `text`: text; required

  Label shown on the button. Colors and placeholders are allowed.

- `actions`: list of [an action](#actions); default `[]`

  Actions run in order when this button is chosen.

- `require`: [Gate](#gate)

  Must hold for `actions` to run when chosen.

## BedrockItem

Per-item presentation and interaction overrides for Bedrock Edition players.

Used when an inventory menu is converted to a Bedrock `SIMPLE` form or when a Bedrock player activates a multi-click item and sees the action picker form.

- `text`: text

  Button label in an auto-converted Bedrock form. Defaults to the item's `name` (and first lore line when `include-lore` is on).

- `image`: text

  Button icon in an auto-converted Bedrock form: an `https://` URL, a Bedrock texture path (`textures/items/diamond`), or a vanilla item key.

- `hidden`: `true` or `false`; default `false`

  Hide this item from auto-converted Bedrock forms.

- `left-label`: text

  Button label for `click.left` in the Bedrock action picker.

- `right-label`: text

  Button label for `click.right` in the Bedrock action picker.

- `shift-left-label`: text

  Button label for `click.shift-left` in the Bedrock action picker.

- `shift-right-label`: text

  Button label for `click.shift-right` in the Bedrock action picker.

- `middle-label`: text

  Button label for `click.middle` in the Bedrock action picker.

## BedrockMenu

How a menu behaves for Bedrock Edition players (connected through Floodgate or Geyser).

A menu can render as a native Bedrock form (`SIMPLE`, `MODAL` or `CUSTOM`), redirect to another menu (`menu`), or stay an inventory window (`INVENTORY`) with Bedrock-safe title handling and click adaptation. Filters (`devices`, `inputs`, `ui`) restrict form or redirect behavior to matching Bedrock devices (for example, showing a `SIMPLE` form to touch and controller players while Bedrock PC players keep the chest inventory).

- `mode`: one of `AUTO`, `INVENTORY`, `SIMPLE`, `MODAL`, `CUSTOM`; default `"AUTO"`

  How this menu opens for matching Bedrock players. `AUTO` picks `CUSTOM` when `fields` are set, `MODAL` when `confirm` is set, `SIMPLE` when `buttons` are set, or follows `bedrock.default-mode` in `config.yml`.

- `devices`: list of one of `PC`, `MOBILE`, `CONSOLE`, `VR`, `UNKNOWN`; default `[]`

  Bedrock device categories this configuration applies to; empty means every Bedrock device.

- `inputs`: list of one of `KEYBOARD_MOUSE`, `TOUCH`, `CONTROLLER`, `VR`, `UNKNOWN`; default `[]`

  Bedrock input modes this configuration applies to; empty means any input mode.

- `ui`: list of one of `JAVA`, `CLASSIC`, `POCKET`; default `[]`

  Bedrock UI profiles this configuration applies to; empty means any UI profile.

- `menu`: text

  Another menu to open instead for matching Bedrock players, such as a mobile-tailored layout.

- `title`: text

  Title shown to Bedrock players instead of the menu's Java title, avoiding resource-pack font glyphs.

- `content`: text; default `""`

  Body text shown above the buttons of a `SIMPLE` or `MODAL` form. Colors, placeholders and `\n` line breaks are allowed.

- `buttons`: list of [BedrockButton](#bedrockbutton); default `[]`

  Explicit buttons of a `SIMPLE` form. When empty, visible items (and paged elements) of the menu become buttons automatically.

- `confirm`: [BedrockChoice](#bedrockchoice)

  First button of a `MODAL` form.

- `cancel`: [BedrockChoice](#bedrockchoice)

  Second button of a `MODAL` form.

- `fields`: list of [a bedrockfield](#bedrock-form-fields); default `[]`

  Interactive controls and labels of a `CUSTOM` form.

- `on-submit`: list of [an action](#actions); default `[]`

  Actions run when a `CUSTOM` form is submitted. Each field's answer is in scope as `{id}`.

- `on-close`: list of [an action](#actions); default `[]`

  Actions run when a Bedrock form is closed without tapping a button or submitting.

- `skip-fillers`: `true` or `false`; default `true`

  Skip decorative items that have no click actions when converting an inventory menu into a `SIMPLE` form.

- `include-lore`: `true` or `false`; default `true`

  Include the first non-blank lore line under the item name on auto-converted `SIMPLE` form buttons.

- `action-picker`: `true` or `false`; default `true`

  Open a follow-up choice form when a Bedrock player activates an item with multiple click handlers (such as left and right click).

- `inventory-overlay`: `true` or `false`; default `false`

  Whether `inventory-slots` packet overlays are drawn for Bedrock Pocket UI players.

- `prev-button`: text; default `"&e« Previous ({page_prev}/{pages})"`

  Label for the previous-page button on an auto-converted paged `SIMPLE` form.

- `next-button`: text; default `"&eNext ({page_next}/{pages}) »"`

  Label for the next-page button on an auto-converted paged `SIMPLE` form.

## BlockAt

One block, by `world` (any world when absent) and coordinates.

- `x`: whole number; required

- `y`: whole number; required

- `z`: whole number; required

- `world`: text

## Book

The text of a written book. A page is text like any other, with colors and placeholders.

- `pages`: list of text; required

- `title`: text; default `"Book"`

- `author`: text; default `""`

## Check

A `Condition` with what to run when it fails or holds. A check marked `optional` may fail, as long as `Gate.minimum` checks hold.

- `condition`: [a condition](#conditions); required

- `optional`: `true` or `false`; default `false`

- `pass`: list of [an action](#actions); default `[]`

  Run when the condition holds.

- `deny`: list of [an action](#actions); default `[]`

  Run when the condition does not hold.

## Clicks

What a click on an item does, per kind of click. `any` handles the kinds with no handler of their own.

- `any`: [Handler](#handler)

- `left`: [Handler](#handler)

- `right`: [Handler](#handler)

- `shift-left`: [Handler](#handler)

- `shift-right`: [Handler](#handler)

- `middle`: [Handler](#handler)

## Equippable

Makes an item wearable in `slot` (`HEAD`, `CHEST`, `LEGS`, `FEET`, `BODY`, `OFF_HAND`...). `model` is the key of the equipment model drawn on the wearer, `cameraOverlay` the key of a texture drawn over the view of the wearer.

- `slot`: text; required

- `model`: text

- `camera-overlay`: text

- `swappable`: `true` or `false`; default `true`

  Whether a right click swaps the item with what the player wears in the slot.

- `dispensable`: `true` or `false`; default `true`

  Whether a dispenser can put the item on a player.

- `damage-on-hurt`: `true` or `false`; default `true`

  Whether the item loses durability when the wearer is hurt.

## Firework

Firework rocket or star settings. A rocket flies for `power` (1 to 3) and bursts with every effect; a star shows the first.

- `effects`: list of [FireworkEffect](#fireworkeffect); default `[]`

  The bursts.

- `power`: whole number; default `1`; 0 to 127

  The flight duration of a rocket, 1 to 3.

## FireworkEffect

One burst. `colors` and `fade` are `#rrggbb`.

- `type`: one of `BALL`, `BALL_LARGE`, `STAR`, `BURST`, `CREEPER`; default `"BALL"`

- `colors`: list of text; required

- `fade`: list of text; default `[]`

- `flicker`: `true` or `false`; default `false`

- `trail`: `true` or `false`; default `false`

## Fish

A bucket of tropical fish: the look of the fish in it. Patterns are the names the game uses, such as `KOB` or `BRINELY`.

- `pattern`: text; required

- `body-color`: text; default `"WHITE"`

  A dye color name, such as `RED`.

- `pattern-color`: text; default `"WHITE"`

  A dye color name, such as `BLUE`.

## Food

An item that can be eaten, whatever it is made of. `saturation` is what is added to the player's, not a multiple.

- `nutrition`: whole number; required; at least 0

  The food points restored, in half drumsticks.

- `saturation`: number; at least 0

- `always-edible`: `true` or `false`; default `false`

  Whether the item can be eaten with a full food bar.

## Frame

One step of an `Animation`. After `delay` (counted from the frame before, at least one tick) its `items` are drawn; with `clear`, what the frames before it drew goes first, so it replaces them, and without it the items stay on top. A frame whose `require` gate does not hold at that moment is skipped. A slot that an item of the frame claims shows that item instead of the menu's own, as long as the item's `view` gate passes.

- `delay`: time, such as `5s`, `500ms` or `1m`; default `"1s"`

  The wait before the frame, as a duration such as `500ms` or `2s`.

- `clear`: `true` or `false`; default `true`

  Whether the frame replaces what the frames before it drew.

- `require`: [Gate](#gate)

  Must hold when the frame comes up, or the frame is skipped.

- `on-start`: list of [an action](#actions); default `[]`

  Run when the frame is drawn.

- `on-end`: list of [an action](#actions); default `[]`

  Run when the frame is replaced by the next.

- `items`: map from a name to [Item](#item); default `[object Object]`

  The items the frame draws.

## Gate

A set of checks that decide whether something happens: opening a menu, showing an item, or a click doing its actions. All the `require` checks must hold, except the `Check.optional` ones, and at least `minimum` of the checks must (the optional ones count too; to accept any two of three, mark the three optional with a minimum of 2). With `stopAtSuccess` optional checks after the `minimum`th that holds are not looked at; non-optional checks must still hold. `pass` and `deny` run for the gate as a whole, after the checks' own.

- `require`: list of [Check](#check); default `[]`

  The checks, tried in order.

- `minimum`: whole number

  How many checks must hold, optional ones included. All non-optional checks must hold regardless.

- `stop-at-success`: `true` or `false`; default `false`

- `pass`: list of [an action](#actions); default `[]`

  Run when the gate holds.

- `deny`: list of [an action](#actions); default `[]`

  Run when the gate does not hold.

## Handler

Runs `actions` if the `require` gate, when there is one, passes.

- `actions`: list of [an action](#actions); default `[]`

  Run in order.

- `require`: [Gate](#gate)

  Must hold for the actions to run.

## Item

An item of a menu.

`material` is a Minecraft item key (`diamond_pickaxe`), or a prefixed source: `head:<player>` (a player's head, with their SkinsRestorer skin when it is installed), `texture:<base64>`, `hdb:<id>` (HeadDatabase), `itemsadder:<id>` and `nexo:<id>`. The item sits in `slot` and `slots`; when several items claim a slot, the one with the lowest `priority` whose `view` gate passes is shown.

- `material`: text; required

  The item key or prefixed source. May contain placeholders.

- `skull`: text

  Player name, texture URL (`https://textures.minecraft.net/texture/...`), or base64 texture for a `player_head` (alternative to `head:<name>` / `texture:<base64>` in `material`). May contain placeholders.

- `slot`: whole number; at least 0

  The slot the item occupies. Slots count from 0 at the top left.

- `slots`: list of whole number; default `[]`; at least 0

  More slots the item occupies, in addition to `slot`.

- `inventory-slots`: list of whole number; default `[]`; 0 to 35

  Slots of the player's own inventory this item is drawn in as well, as the game numbers them: 0 to 8 the hotbar, 9 to 35 the three rows above it. It is the same item, so it is defined once and used in the menu and in the rows under it. The item takes the place of what the player has there, which comes back when the menu closes; nothing can be moved in or out.

- `priority`: whole number; default `0`

  Decides between items that claim the same slot: the lowest value is tried first.

- `amount`: whole number, or text with placeholders that works out to one; default `1`; 1 to 99

  The number of items in the stack, 1 to 99: a number, or text with placeholders that works out to one when the item is drawn.

- `name`: text

  The display name. Colors, placeholders and `{name}` arguments are allowed.

- `lore`: list of text; default `[]`

  The lines under the name, with the same formatting as `name`.

- `enchants`: map from a name to whole number; default `[object Object]`

  Enchantment key to level.

- `hide`: list of text; default `[]`

  Data component keys (`enchantments`, `attribute_modifiers`, ...) left out of the tooltip.

- `unbreakable`: `true` or `false`, or text with placeholders that works out to one; default `false`

  Whether the item takes no durability damage. A switch, or text with placeholders that works out to one.

- `model`: whole number, or text with placeholders that works out to one; at least 0

  The custom model data number that resource packs use to choose a model. A number, or text with placeholders.

- `color`: text

  Dye color of leather armor and the like, as `#rrggbb`.

- `banner`: list of [BannerLayer](#bannerlayer); default `[]`

  The patterns of a banner, from the bottom layer to the top.

- `base-color`: text

  The dye color of a shield's base, such as `red`; the patterns of a shield are the `banner` ones.

- `potion`: list of [PotionEffect](#potioneffect); default `[]`

  Custom effects of a potion, splash potion, lingering potion or tipped arrow.

- `potion-type`: text

  The base potion of a potion, tipped arrow or the like, such as `strong_healing`.

- `stored-enchants`: map from a name to whole number; default `[object Object]`

  Enchantment key to level, kept in an enchanted book rather than put on the item.

- `damage`: whole number, or text with placeholders that works out to one; at least 0

  How worn out the item is, in points of durability; the bar shows once it is more than 0. A number, or text with placeholders.

- `item-name`: text

  The base name, which stays plain (not italic) and which renaming in an anvil replaces; `name` is the renamed one.

- `rarity`: one of `COMMON`, `UNCOMMON`, `RARE`, `EPIC`

  The rarity, which sets the color of the name.

- `glow`: `true` or `false`, or text with placeholders that works out to one

  Whether the item shimmers like an enchanted one: true forces it, false takes it away from an enchanted item. A switch, or text with placeholders.

- `max-stack-size`: whole number, or text with placeholders that works out to one; 1 to 99

  How many fit in a stack, 1 to 99. A number, or text with placeholders.

- `hide-tooltip`: `true` or `false`, or text with placeholders that works out to one; default `false`

  Show no tooltip at all, not even the name. A switch, or text with placeholders.

- `item-model`: text

  The key of the model resource packs give the item, such as `mypack:crown`.

- `food`: [Food](#food)

  Makes the item edible.

- `trim`: [Trim](#trim)

  The trim of a piece of armor.

- `book`: [Book](#book)

  The text of a written book.

- `firework`: [Firework](#firework)

  The effects of a firework rocket or firework star.

- `fish`: [Fish](#fish)

  The look of the fish in a bucket of tropical fish.

- `attributes`: list of [AttributeModifier](#attributemodifier); default `[]`

  Attribute modifiers the item gives while it is held or worn; they replace the ones the material has of its own.

- `tooltip-style`: text

  The key of the tooltip background a resource pack gives the item, such as `mypack:fancy`.

- `equippable`: [Equippable](#equippable)

  Makes the item wearable in a slot.

- `tool`: [Tool](#tool)

  Makes the item mine blocks.

- `nbt`: text

  Extra custom data SNBT (`{menu:"jobs",tier:3}`) or bracketed item data components (`[minecraft:profile={name:"Steve"}]`).

- `update`: `true` or `false`; default `false`

  Draw this item again on every menu refresh, to keep its placeholders current.

- `view`: [Gate](#gate)

  Must hold for the item to be shown. An item that fails it leaves the slot to the next item that claims it.

- `click`: [Clicks](#clicks)

  What a click on the item does.

- `accept`: [Accept](#accept)

  Makes the slot take an item from the player: clicking it with the item on the cursor, or shift-clicking the item in the player's inventory. With `material = air` the slot shows nothing. It doesn't work in a menu that draws in the player's inventory.

- `bedrock`: [BedrockItem](#bedrockitem)

  Optional display and click-label overrides for Bedrock Edition players.

## Matrix

A drawing of a paged menu's slots as rows of characters, with the item template each character stands for written next to it, as in AbstractMenus' `matrix { cells, templates }`. It is used by `Pages.matrix`, where the elements of a catalog fill the cells.

`cells` are rows of at most nine characters, read left to right and top to bottom, so the nth character of a row is the nth column of the window. `templates` name the characters that stand for something: a key is one character, and its value is the item drawn in every cell where that character stands. A character with no template is a blank, and a row of fewer characters ends in blanks. A character that HOCON reads as something else needs quotes (`"#"`).```hocon cells = ["#########", "#xxxxxxx#", "#xxxxxxx#", "####c####"] templates { x { material = "head:{name}", name = "&e{name}" } c { material = "barrier", name = "&cClose" } }```

- `cells`: list of text; required

  The rows of the drawing, at most nine characters each.

- `templates`: map from a name to [Item](#item); required

  The item of each character that stands for something, by that character.

## MenuMatrix

A drawing of the whole menu's slots as rows of characters, with each character mapped to an item in `Menu.items`.

`cells` are rows of at most nine characters, read left to right and top to bottom, so the nth character of a row is the nth column of the window. `map` names the item in `Menu.items` that each character stands for: the item goes in every slot where its character stands, besides its own `slot` and `slots`. Because each cell holds at most one character, items placed through the matrix cannot conflict with each other inside the grid. A character that `map` does not know is a blank, and a row of fewer characters ends in blanks. Quote a character that HOCON reads as something else (`"#"`).```hocon matrix { cells = [ "#########" "#  o   x#" "####c####" ] map { "#" = border o = ok x = extra c = close } }```

- `cells`: list of text; default `[]`

  The rows of the drawing, at most nine characters each.

- `map`: map from a name to text; default `[object Object]`

  The character of `cells` to the name of the item in `Menu.items` that goes where it stands.

## Pages

What makes a menu paged: the elements of `catalog` are drawn one page at a time into the slots of the menu.

The elements are sorted (see `sort`), then the ones for which the `item`'s `view` gate does not hold are left out, and only then split into pages, so every page but the last is full. The gate and everything the item shows can use the element's values as `{name}`. The item's own `slot` and `slots` are not used; the nth element of the page goes into the nth of the slots. An item of the menu in one of the slots is drawn when the page has no element for it.

The slots come from one of three places, and only one may be given:

- `slots`, a list of slot numbers, drawn with `item`; - a `layout`, rows of nine characters, the way the menu looks (`#########`, `#xxxxxxx#`): every `cell` character is a slot for an element, read left to right and top to bottom, and the other characters are only there to draw by. A row of fewer characters is taken as ending in blanks. It is drawn with `item`; - a `matrix`, which names an item for each of several characters, so the cells of one drawing can show different things. The elements still fill the cells in order, and each is drawn as the template of the cell it falls in. `item` is not used.

With a `matrix` there is no single gate to leave elements out by before they are split into pages. The `view` of a template is asked after the element has its cell, and an element whose template does not show leaves its cell to what the menu has there.

- `catalog`: [a catalog](#catalogs); required

  The source of the elements.

- `slots`: list of whole number; default `[]`

  The slots the elements go into.

- `item`: [Item](#item)

  The item drawn for each element when the slots come from `slots` or `layout`.

- `layout`: list of text; default `[]`

  Rows of nine characters that mark the slots of the elements instead of `slots`.

- `cell`: text; default `"x"`

  The character of `layout` that stands for an element.

- `sort`: text

  The name of the element value to order by, such as `name`; numbers are ordered as numbers. The catalog's own order when absent.

- `descending`: `true` or `false`; default `false`

  Whether the order of `sort` is reversed.

- `empty`: [Item](#item)

  Drawn in the first of the slots when the catalog has no element to list.

- `matrix`: [Matrix](#matrix)

  Rows of characters and the item of each, instead of `slots` or `layout` with one `item`.

## PotionEffect

One effect of a potion.

- `type`: text; required

  The effect key, such as `speed`.

- `duration`: whole number; required; at least 0

  The length in ticks. 20 ticks are one second.

- `amplifier`: whole number; default `0`; 0 to 255

  The level minus one: 0 is level I.

## Tool

Makes an item mine blocks, whatever it is made of. `miningSpeed` is the speed for blocks no rule covers (1 is the speed of a hand) and `damagePerBlock` the durability it loses for each block mined.

- `mining-speed`: number; at least 0

- `damage-per-block`: whole number; default `1`; at least 0

- `can-destroy-in-creative`: `true` or `false`; default `true`

  Whether a player in creative mode can break blocks with the item.

- `rules`: list of [ToolRule](#toolrule); default `[]`

## ToolRule

One rule of a `Tool`: for the `blocks` (block keys such as `stone`, or tags such as `#minecraft:mineable/pickaxe`) the mining speed is `speed` if there is one, and `drops` says whether they drop their items when mined with it.

- `blocks`: list of text; required

- `speed`: number

- `drops`: `true` or `false`

## Trim

An armor trim: `pattern` and `material` are keys such as `coast` and `gold`.

- `pattern`: text; required

- `material`: text; required

## Actions

What a click, an open or a close does: one entry of a list, with `type` naming the action.

[`actionbar`](#action-actionbar), [`all`](#action-all), [`book`](#action-book), [`boss-bar`](#action-boss-bar), [`broadcast`](#action-broadcast), [`chance`](#action-chance), [`chat`](#action-chat), [`clear-items`](#action-clear-items), [`close`](#action-close), [`connect`](#action-connect), [`console`](#action-console), [`cooldown`](#action-cooldown), [`delay`](#action-delay), [`experience`](#action-experience), [`food`](#action-food), [`gamemode`](#action-gamemode), [`give-item`](#action-give-item), [`group`](#action-group), [`health`](#action-health), [`input`](#action-input), [`json`](#action-json), [`level`](#action-level), [`log`](#action-log), [`message`](#action-message), [`meta`](#action-meta), [`money`](#action-money), [`open`](#action-open), [`page`](#action-page), [`particle`](#action-particle), [`permission`](#action-permission), [`player`](#action-player), [`potion`](#action-potion), [`random`](#action-random), [`refresh`](#action-refresh), [`run-for`](#action-run-for), [`skin`](#action-skin), [`skin-reset`](#action-skin-reset), [`sound`](#action-sound), [`stop`](#action-stop), [`take-item`](#action-take-item), [`teleport`](#action-teleport), [`title`](#action-title), [`variable`](#action-variable)

### Action `actionbar`

Shows `text` above the hotbar of the viewer.

- `text`: text; required

### Action `all`

Runs `actions` in order, as a unit: one that stops ends this list only, and the list it is in goes on.

- `actions`: list of [an action](#actions); required

### Action `book`

Opens a written book for the viewer, one entry of `pages` to a page.

- `pages`: list of text; required

- `title`: text; default `"Book"`

- `author`: text; default `""`

### Action `boss-bar`

Shows the viewer a boss bar with `text` for `duration`, then takes it away.

- `text`: text; required

- `color`: one of `PINK`, `BLUE`, `RED`, `GREEN`, `YELLOW`, `PURPLE`, `WHITE`

- `style`: one of `PROGRESS`, `NOTCHED_6`, `NOTCHED_10`, `NOTCHED_12`, `NOTCHED_20`

- `progress`: number; 0 to 1

- `duration`: time, such as `5s`, `500ms` or `1m`; default `"5s"`

### Action `broadcast`

Sends `text` in chat to every player on the server.

- `text`: text; required

### Action `chance`

Runs `action` with a chance of `percent` (0 to 100). If it aborts, so does the list this is in.

- `percent`: number; required; 0 to 100

- `action`: [an action](#actions); required

### Action `chat`

The viewer says `text` in chat.

- `text`: text; required

### Action `clear-items`

Takes every item that matches from the viewer: with nothing to match on, the whole inventory.

- `material`: text

- `name`: text

- `model`: whole number; at least 0

### Action `close`

Closes the menu of the viewer.

It has no keys of its own.

### Action `connect`

Sends the viewer to another server of the proxy.

- `server`: text; required

### Action `console`

The console runs `command`.

- `command`: text; required

### Action `cooldown`

Starts the cooldown `key` for the viewer (or for everybody with `scope` `GLOBAL`), which the `cooldown` condition with the same key holds off until `duration` has passed. Without a `duration` it never ends, which makes a thing that can be done once. The state is the variable `cooldown_<key>`, so it is kept where the variables are.

- `key`: text; required

- `duration`: time, such as `5s`, `500ms` or `1m`

- `scope`: one of `GLOBAL`, `PLAYER`

### Action `delay`

Runs `action` after `ticks`.

- `ticks`: whole number; required; at least 0

- `action`: [an action](#actions); required

### Action `experience`

Gives the viewer `amount` points of experience, or with `take` removes them. Aborts the list when the viewer has too few.

- `amount`: text; required

- `take`: `true` or `false`; default `false`

### Action `food`

Sets the viewer's food level (0 to 20) to `amount`, or with `change` adds or takes that much.

- `amount`: text; required

- `change`: one of `SET`, `ADD`, `TAKE`; default `"SET"`

- `saturation`: text

### Action `gamemode`

Puts the viewer in a game mode: `survival`, `creative`, `adventure` or `spectator`.

- `mode`: text; required

### Action `give-item`

Gives the viewer `item` (the same settings as an item of a menu, without its slot and clicks).

- `item`: [Item](#item); required

- `overflow`: one of `DROP`, `ABORT`; default `"DROP"`

### Action `group`

Puts the viewer in the permission group `name`, or with `take` takes them out of it, through Vault.

- `name`: text; required

- `take`: `true` or `false`; default `false`

### Action `health`

Sets the viewer's health to `amount`, or with `change` adds or takes that much. It stays between 0 and their maximum.

- `amount`: text; required

- `change`: one of `SET`, `ADD`, `TAKE`; default `"SET"`

### Action `input`

Closes the menu and asks the viewer to type something. `mode` says where: a dialog with a text field on clients that have them, an anvil's rename field on older ones (found through ViaVersion), or in chat. What they type is not said in chat; it runs `then` as `{input}`. Typing `cancel` (or waiting longer than `timeout`) runs `onCancel` instead, and so do the cancel button of the dialog and leaving the anvil. With `match`, a regular expression the whole reply must fit, anything else is turned down with `invalid` and the viewer may try again. `title` is what the dialog and the anvil are called (the `prompt` when it is empty), `initial` what is already in the field, `submit` the label of the dialog's confirm button, and `maxLength` how long the reply may be.

- `prompt`: text; required

- `then`: list of [an action](#actions); required

- `mode`: one of `AUTO`, `DIALOG`, `ANVIL`, `CHAT`, `FORM`; default `"AUTO"`

- `title`: text; default `""`

- `initial`: text; default `""`

- `submit`: text; default `"Confirm"`

- `max-length`: whole number; default `256`; at least 1

- `cancel`: text; default `"cancel"`

- `on-cancel`: list of [an action](#actions); default `[]`

- `timeout`: time, such as `5s`, `500ms` or `1m`; default `"30s"`

- `match`: text

- `invalid`: text

### Action `json`

A component written as JSON, shown to the viewer or, with `broadcast`, to everyone.

- `json`: text; required

- `broadcast`: `true` or `false`; default `false`

### Action `level`

Gives the viewer `amount` experience levels, or with `take` takes that many (never below 0).

- `amount`: text; required

- `take`: `true` or `false`; default `false`

### Action `log`

Writes `text` to the server's log.

- `text`: text; required

- `level`: one of `INFO`, `WARNING`, `ERROR`; default `"INFO"`

### Action `message`

Sends `text` to the viewer in chat. Colors, placeholders and `{name}` arguments are allowed.

- `text`: text; required

### Action `meta`

Sets the LuckPerms meta `key` of the viewer to `value`, or without a value removes it. LuckPerms has to be installed. It is done through its command, which does the saving and the telling of other servers.

- `key`: text; required

- `value`: text

### Action `money`

Pays `amount` to the viewer, or with `take` charges it. `amount` may be a placeholder. `currency` picks one of the economy's currencies (VaultUnlocked has several); without it the default one is used.

- `amount`: text; required

- `take`: `true` or `false`; default `false`

- `currency`: text

### Action `open`

Opens the menu `menu`, handing it `args` for its own `args`.

- `menu`: text; required

- `args`: list of text; default `[]`

### Action `page`

Turns the pages of a paged menu: `by` pages forward (negative: back), or `to` page `first`, `last` or a number from 1, which may be a placeholder or `{name}` argument. It stops at the first and the last page. One of the two is needed.

- `by`: whole number

- `to`: text

### Action `particle`

Shows a particle at the viewer's feet, moved by `dx`, `dy`, `dz`. `count` of them are spread by `spread` blocks. With `everyone` the players around see it too. Only particles that need no extra data work.

- `particle`: text; required

- `count`: whole number; default `10`; 1 to 1000

- `spread`: number; default `0.5`; at least 0

- `speed`: number; default `0`; at least 0

- `dx`: number; default `0`

- `dy`: number; default `1`

- `dz`: number; default `0`

- `everyone`: `true` or `false`; default `false`

### Action `permission`

Gives the viewer `node`, or with `take` removes it, through whatever provides Vault's permissions.

- `node`: text; required

- `take`: `true` or `false`; default `false`

### Action `player`

The viewer runs `command`.

- `command`: text; required

### Action `potion`

Gives the viewer a potion effect, or with `remove` takes `effect` off them (every effect when `effect` is `all`).

- `effect`: text; required

- `duration`: time, such as `5s`, `500ms` or `1m`; default `"30s"`

- `amplifier`: whole number; default `0`; 0 to 255

- `particles`: `true` or `false`; default `true`

- `remove`: `true` or `false`; default `false`

### Action `random`

Runs `count` of the `actions`, picked at random and each at most once.

- `actions`: list of [an action](#actions); required

- `count`: whole number; default `1`; at least 1

### Action `refresh`

Draws the open menu again, so items that depend on placeholders or requirements are re-evaluated.

It has no keys of its own.

### Action `run-for`

Runs `actions` once for each element of `catalog`, with the element's values as `{name}` (and `{index}`, from 1). The first `limit` elements are used. Each run is a list of its own, so one that stops does not end the others.

- `catalog`: [a catalog](#catalogs); required

- `actions`: list of [an action](#actions); required

- `limit`: whole number; default `100`; at least 1

### Action `skin`

Gives the viewer the skin of the player `skin`, or of the picture at that link, with SkinsRestorer, which keeps it for their next visit. `slim` picks the thinner arms for a picture. It is looked up in the background and put on when found, so the actions after it do not wait; one that cannot be found is noted in the console.

- `skin`: text; required

- `slim`: `true` or `false`; default `false`

### Action `skin-reset`

Takes the skin a menu gave the viewer off them again, with SkinsRestorer.

It has no keys of its own.

### Action `sound`

Plays `sound` to the viewer, a sound key such as `entity.experience_orb.pickup`.

- `sound`: text; required

- `volume`: number; at least 0

- `pitch`: number; 0.5 to 2

- `scope`: one of `PLAYER`, `WORLD`, `SERVER`; default `"PLAYER"`

### Action `stop`

Ends the list of actions it is in; the ones after it do not run. Mostly for a `chance`.

It has no keys of its own.

### Action `take-item`

Takes `amount` of the items that match from the viewer. If they have fewer nothing is taken and the actions after this one do not run, so it can stand before what is given for them.

- `material`: text

- `amount`: whole number; default `1`; at least 1

- `name`: text

- `model`: whole number; at least 0

### Action `teleport`

Moves the viewer to `x`, `y`, `z` in `world` (the one they are in without it), looking `yaw` and `pitch`. Each may be a placeholder or `{name}`, and a coordinate may be `~` or `~5` for relative to where they are now. A coordinate that is left out stays as it is.

- `x`: text; default `"~"`

- `y`: text; default `"~"`

- `z`: text; default `"~"`

- `world`: text

- `yaw`: text; default `"~"`

- `pitch`: text; default `"~"`

### Action `title`

Shows the viewer a title and a subtitle in the middle of the screen. The times are in ticks, as the game's `/title`.

- `title`: text; default `""`

- `subtitle`: text; default `""`

- `fade-in`: whole number; default `10`; at least 0

- `stay`: whole number; default `70`; at least 0

- `fade-out`: whole number; default `20`; at least 0

### Action `variable`

Changes a variable. `op` says how: set it to `value`, remove it, or change its number by `value`. It is a global variable, or with `scope` `PLAYER` the personal one of the viewer, or of `player` (a name; may be a placeholder). `expiry` makes a set variable last that long; with `replace` false a variable that already exists is kept. If the variable's store is not ready yet the viewer is told so and nothing changes.

- `name`: text; required

- `op`: one of `SET`, `REMOVE`, `INC`, `DEC`, `MUL`, `DIV`; default `"SET"`

- `value`: text; default `""`

- `scope`: one of `GLOBAL`, `PLAYER`; default `"GLOBAL"`

- `player`: text

- `expiry`: time, such as `5s`, `500ms` or `1m`

- `replace`: `true` or `false`; default `true`

## Conditions

A check that is true or false: one entry of a `require` list, with `type` naming the condition.

[`and`](#condition-and), [`at-least`](#condition-at-least), [`chance`](#condition-chance), [`client`](#condition-client), [`compare`](#condition-compare), [`cooldown`](#condition-cooldown), [`experience`](#condition-experience), [`food`](#condition-food), [`free-slots`](#condition-free-slots), [`gamemode`](#condition-gamemode), [`group`](#condition-group), [`health`](#condition-health), [`held-item`](#condition-held-item), [`item`](#condition-item), [`level`](#condition-level), [`money`](#condition-money), [`near`](#condition-near), [`never`](#condition-never), [`not`](#condition-not), [`one-of`](#condition-one-of), [`online`](#condition-online), [`online-count`](#condition-online-count), [`or`](#condition-or), [`permission`](#condition-permission), [`regex`](#condition-regex), [`region`](#condition-region), [`text`](#condition-text), [`variable-exists`](#condition-variable-exists), [`world`](#condition-world)

### Condition `and`

Holds when all of `conditions` do.

- `conditions`: list of [a condition](#conditions); required

### Condition `at-least`

Holds when `count` or more of `conditions` do.

- `count`: whole number; required; at least 0

- `conditions`: list of [a condition](#conditions); required

### Condition `chance`

Holds `percent` times in a hundred (0 to 100). It is asked again on every redraw of an item, so it belongs in the `require` of a click, not in the `view` of an item.

- `percent`: number; required; 0 to 100

### Condition `client`

Holds when the viewer's client matches every given filter: `edition` (`JAVA` or `BEDROCK`), `devices` (`PC`, `MOBILE`, `CONSOLE`, `VR`), `os` (`ANDROID`, `IOS`, `WINDOWS`, `XBOX`, ...), `inputs` (`KEYBOARD_MOUSE`, `TOUCH`, `CONTROLLER`, `VR`) and `ui` (`JAVA`, `CLASSIC`, `POCKET`).

- `edition`: one of `JAVA`, `BEDROCK`

- `devices`: list of one of `PC`, `MOBILE`, `CONSOLE`, `VR`, `UNKNOWN`; default `[]`

- `os`: list of one of `JAVA`, `ANDROID`, `IOS`, `OSX`, `LINUX`, `AMAZON`, `GEAR_VR`, `HOLOLENS`, `WINDOWS`, `WINDOWS_PHONE`, `DEDICATED`, `TVOS`, `PLAYSTATION`, `SWITCH`, `XBOX`, `UNKNOWN`; default `[]`

- `inputs`: list of one of `KEYBOARD_MOUSE`, `TOUCH`, `CONTROLLER`, `VR`, `UNKNOWN`; default `[]`

- `ui`: list of one of `JAVA`, `CLASSIC`, `POCKET`; default `[]`

### Condition `compare`

Compares `left` and `right` as numbers; if either is not one it never holds.

- `left`: text; required

- `op`: one of `GT`, `GTE`, `EQ`, `NEQ`, `LT`, `LTE`; required

- `right`: text; required

### Condition `cooldown`

Holds when the cooldown `key` of the viewer is not running (or, with `scope` `GLOBAL`, the one everybody shares). The cooldown is started by the `cooldown` action, which keeps it as the variable `cooldown_<key>` with an expiry; a store that has not loaded yet counts as running, so waiting for it cannot be used to get around one.

- `key`: text; required

- `scope`: one of `GLOBAL`, `PLAYER`

### Condition `experience`

The viewer's experience points in all (as `experience` charges them) are between `min` and `max`.

- `min`: text

- `max`: text

### Condition `food`

The viewer's food level (0 to 20) is between `min` and `max`.

- `min`: text

- `max`: text

### Condition `free-slots`

The viewer has `min` or more empty slots in their inventory (not counting armor and the off hand).

- `min`: whole number; default `1`; 0 to 36

### Condition `gamemode`

The viewer is in one of the game modes `modes`.

- `modes`: list of one of `CREATIVE`, `SURVIVAL`, `ADVENTURE`, `SPECTATOR`; required

### Condition `group`

The viewer is in the permission group `name`, as the permission plugin behind Vault knows it.

- `name`: text; required

### Condition `health`

The viewer's health is between `min` and `max`, each a number or a placeholder.

- `min`: text

- `max`: text

### Condition `held-item`

The viewer holds at least `amount` of an item that matches, in `hand`.

- `material`: text

- `name`: text

- `model`: whole number; at least 0

- `amount`: whole number; default `1`; at least 1

- `hand`: one of `HAND`, `OFF_HAND`, `FEET`, `LEGS`, `CHEST`, `HEAD`, `BODY`, `SADDLE`; default `"HAND"`

### Condition `item`

The viewer carries `amount` or more of `material`; `name` and `lore`, when set, must match the item's.

- `material`: text; required

- `amount`: whole number; default `1`; at least 1

- `name`: text

- `lore`: list of text; default `[]`

### Condition `level`

The viewer's experience level is between `min` and `max`.

- `min`: text

- `max`: text

### Condition `money`

The viewer has at least `amount` (a number or a placeholder) of `currency`, the default one without it.

- `amount`: text; required

- `currency`: text

### Condition `near`

The viewer is less than `distance` blocks from the point `x`, `y`, `z` of the world `world`.

- `world`: text; required

- `x`: number; required

- `y`: number; required

- `z`: number; required

- `distance`: number; required; at least 0

### Condition `never`

Never holds. The importer writes it for a test XaliMenu cannot run (a JavaScript expression, say), so the menu fails closed instead of quietly showing an item or opening for everyone.

It has no keys of its own.

### Condition `not`

Holds when `condition` does not.

- `condition`: [a condition](#conditions); required

### Condition `one-of`

Holds when exactly one of `conditions` does.

- `conditions`: list of [a condition](#conditions); required

### Condition `online`

The player called `player` (a name; may be a placeholder) is online.

- `player`: text; required

### Condition `online-count`

The number of players online is between `min` and `max`.

- `min`: text

- `max`: text

### Condition `or`

Holds when any of `conditions` does.

- `conditions`: list of [a condition](#conditions); required

### Condition `permission`

The viewer has the permission `node` (placeholders allowed).

- `node`: text; required

### Condition `regex`

`input` matches the whole of `pattern`; a pattern that is not valid never matches.

- `input`: text; required

- `pattern`: text; required

### Condition `region`

The viewer stands in one of the WorldGuard regions `names` (placeholders allowed), or in any region when there are none. Without WorldGuard it never holds.

- `names`: list of text; default `[]`

### Condition `text`

Compares `input` with `value` as `op` says. Both may contain placeholders.

- `input`: text; required

- `op`: one of `EQUALS`, `EQUALS_IGNORE_CASE`, `CONTAINS`; required

- `value`: text; required

### Condition `variable-exists`

A variable called `name` exists (and has not expired), a global one or, with `scope` `PLAYER`, the viewer's own.

- `name`: text; required

- `scope`: one of `GLOBAL`, `PLAYER`; default `"GLOBAL"`

### Condition `world`

The viewer is in one of the worlds `names` (placeholders allowed).

- `names`: list of text; required

## Activators

What opens a menu besides its commands, with `type` naming the activator.

[`addon`](#activator-addon), [`block`](#activator-block), [`chat`](#activator-chat), [`entity`](#activator-entity), [`item`](#activator-item), [`join`](#activator-join), [`npc`](#activator-npc), [`region-enter`](#activator-region-enter), [`region-leave`](#activator-region-leave), [`swap-hands`](#activator-swap-hands)

### Activator `addon`

An activator another plugin added with `net.xalitoria.menu.XaliMenuApi.registerActivator`, written in a menu file as `{ type = "myplugin:name", ... }`. The menu is read with `name` as the type and the keys other than the ones every activator has as one JSON text in `values`, which the plugin's type turns into what decides whether it matches. This is not written by hand: it is what the reading of the file makes of such an object.

- `name`: text; required

- `values`: text; default `"{}"`

- `require`: [Gate](#gate)

- `cancel`: `true` or `false`; default `true`

- `args`: list of text; default `[]`

### Activator `block`

The player clicks a block, or steps on it (`BlockClick.PHYSICAL`). `types` are block keys (`stone_button`) or tags (`#minecraft:buttons`); `at` limits it to one block. With neither, any block matches. `{block}`, `{world}`, `{x}`, `{y}` and `{z}` are the block's.

- `types`: list of text; default `[]`

- `at`: [BlockAt](#blockat)

- `click`: one of `LEFT`, `RIGHT`, `ANY`, `PHYSICAL`; default `"RIGHT"`

- `sneaking`: `true` or `false`

- `require`: [Gate](#gate)

- `cancel`: `true` or `false`; default `true`

- `args`: list of text; default `[]`

### Activator `chat`

The player says something in chat that `text` matches as `match` says. `{message}` is what they said.

- `text`: text; required

- `match`: one of `EQUALS`, `EQUALS_IGNORE_CASE`, `CONTAINS`, `STARTS_WITH`, `REGEX`; default `"EQUALS_IGNORE_CASE"`

- `require`: [Gate](#gate)

- `cancel`: `true` or `false`; default `true`

- `args`: list of text; default `[]`

### Activator `entity`

The player right-clicks an entity: one of `types` (`villager`) and/or called `name`. With neither, any entity matches. `{entity}` is its type and `{entity_name}` its name, empty if it has none.

- `types`: list of text; default `[]`

- `name`: text

- `sneaking`: `true` or `false`

- `require`: [Gate](#gate)

- `cancel`: `true` or `false`; default `true`

- `args`: list of text; default `[]`

### Activator `item`

The click of a player, with an item in the hand (any hand: the main one is used).

- `material`: text

  The item key of the item in hand, such as `compass`; any item when absent.

- `name`: text

  The name of the item in hand, written as in a menu (colors are ignored when comparing).

- `model`: whole number; at least 0

- `click`: one of `LEFT`, `RIGHT`, `ANY`; default `"ANY"`

- `sneaking`: `true` or `false`

  Whether the player must be sneaking (`true`) or not (`false`); either when absent.

- `require`: [Gate](#gate)

- `cancel`: `true` or `false`; default `true`

- `args`: list of text; default `[]`

### Activator `join`

The player joins the server, after `delay` ticks.

- `delay`: whole number; default `20`; at least 0

- `require`: [Gate](#gate)

- `cancel`: `true` or `false`; default `false`

- `args`: list of text; default `[]`

### Activator `npc`

The player clicks an NPC of FancyNpcs: one of `npcs`, by the NPC's name or id (any NPC when empty). `{npc}` is its name and `{npc_id}` its id. `cancel` stops the NPC's own actions from running.

- `npcs`: list of text; default `[]`

- `click`: one of `LEFT`, `RIGHT`, `ANY`; default `"ANY"`

- `sneaking`: `true` or `false`

- `require`: [Gate](#gate)

- `cancel`: `true` or `false`; default `true`

- `args`: list of text; default `[]`

### Activator `region-enter`

The player enters a WorldGuard region: one of `regions` by its id (any region when empty). `{region}` is the id of the region and `{world}` the world. Joining the server inside a region is not entering it. `cancel` has no meaning.

- `regions`: list of text; default `[]`

- `require`: [Gate](#gate)

- `cancel`: `true` or `false`; default `false`

- `args`: list of text; default `[]`

### Activator `region-leave`

The player leaves a WorldGuard region, in the same way `RegionEnter` enters one; `{region}` is the region left.

- `regions`: list of text; default `[]`

- `require`: [Gate](#gate)

- `cancel`: `true` or `false`; default `false`

- `args`: list of text; default `[]`

### Activator `swap-hands`

The player presses the key that swaps the hands.

- `sneaking`: `true` or `false`

- `require`: [Gate](#gate)

- `cancel`: `true` or `false`; default `true`

- `args`: list of text; default `[]`

## Catalogs

A list of things a menu draws from, such as the players or the menus, with `type` naming the catalog.

[`entities`](#catalog-entities), [`inventory`](#catalog-inventory), [`menus`](#catalog-menus), [`players`](#catalog-players), [`range`](#catalog-range), [`values`](#catalog-values), [`worlds`](#catalog-worlds)

### Catalog `entities`

Entities near the viewer within `radius` blocks (0.5 to 128, bounded so it stays safe on Folia's region threads): `type` (entity key such as `villager`), `name` (custom name if set, otherwise the entity name), `uuid`, `distance` (in blocks, rounded to one decimal), `x`, `y`, `z`, and `world`. Ordered closest first.

- `radius`: number; default `32`; 0.5 to 128

  Search radius in blocks around the viewer (0.5 to 128).

- `types`: list of text; default `[]`

  Entity type keys to include (for example `villager`, `zombie`); any entity when empty.

- `named-only`: `true` or `false`; default `false`

  Only list entities that have a custom name.

- `include-players`: `true` or `false`; default `false`

  Include other players in the results.

### Catalog `inventory`

The items in the viewer's inventory, by slot: `slot` (0 to 35, the hotbar first, then `armor` and `offhand` if `armor` is on), `material` (`diamond_sword`), `amount` and `name` (the custom name as plain text, or nothing). An element is drawn like any other, so `material = "{material}"` and `amount = "{amount}"` show the item; what else it holds (enchantments, lore) stays on the item.

- `armor`: `true` or `false`; default `false`

- `empty`: `true` or `false`; default `false`

### Catalog `menus`

The menus that are loaded, by name: `name` (what `/xmenu open` and the `open` action take) and `title` (as written in the file, placeholders and all). The menu that lists them is left out unless `includeSelf`. A menu of menus: `open { menu = "{name}" }`.

- `include-self`: `true` or `false`; default `false`

### Catalog `players`

The players who are online, by name: `name` and `uuid`.

- `include-self`: `true` or `false`; default `true`

### Catalog `range`

The whole numbers from `from` to `to`, both included and given as numbers or placeholders, `step` apart: `value`.

- `from`: text; required

- `to`: text; required

- `step`: whole number; default `1`; at least 1

- `descending`: `true` or `false`; default `false`

### Catalog `values`

Fixed texts, each one element with the value `value`: the entries of `list`, then the pieces of `text` (placeholders are filled in first) cut at `separator`, which is read as written and not as a pattern. Empty pieces are left out.

- `list`: list of text; default `[]`

- `text`: text

- `separator`: text; default `","`

- `trim`: `true` or `false`; default `true`

### Catalog `worlds`

The worlds of the server: `name` and `environment` (`normal`, `nether` or `the_end`).

It has no keys of its own.

## Bedrock form fields

An interactive control or label of a Bedrock `CUSTOM` form, with `type` naming the field.

[`dropdown`](#bedrockfield-dropdown), [`input`](#bedrockfield-input), [`label`](#bedrockfield-label), [`slider`](#bedrockfield-slider), [`step-slider`](#bedrockfield-step-slider), [`toggle`](#bedrockfield-toggle)

### BedrockField `dropdown`

A dropdown list whose chosen option text is bound to `{id}` and 0-based index to `{id}_index`.

- `id`: text; required

  Variable name used in `{id}` and `{id}_index` within `on-submit` actions.

- `text`: text; required

  Label shown above the dropdown.

- `options`: list of text; required

  Choices listed in order.

- `default`: whole number; default `0`; at least 0

  0-based index of the initially selected option.

### BedrockField `input`

A text box whose entered text is bound to `{id}` on submit.

- `id`: text; required

  Variable name used in `{id}` within `on-submit` actions.

- `text`: text; required

  Label shown above the text box.

- `placeholder`: text; default `""`

  Hint text shown when the box is empty.

- `default`: text; default `""`

  Initial text placed in the box. Placeholders are allowed.

### BedrockField `label`

Static text displayed inside the form. Produces no submitted value.

- `text`: text; required

  Text shown in the form. Colors and placeholders are allowed.

### BedrockField `slider`

A numeric slider whose chosen value is bound to `{id}` on submit.

- `id`: text; required

  Variable name used in `{id}` within `on-submit` actions.

- `text`: text; required

  Label shown above the slider.

- `min`: number

  Lowest selectable value.

- `max`: number

  Highest selectable value.

- `step`: number

  Increment between values on the slider.

- `default`: number

  Starting value; defaults to `min`.

### BedrockField `step-slider`

A stepped slider over named `steps`, binding the chosen label to `{id}` and its 0-based index to `{id}_index`.

- `id`: text; required

  Variable name used in `{id}` and `{id}_index` within `on-submit` actions.

- `text`: text; required

  Label shown above the step slider.

- `steps`: list of text; required

  Ordered labels of each stop along the slider.

- `default`: whole number; default `0`; at least 0

  0-based index of the starting stop.

### BedrockField `toggle`

An on/off switch whose value (`true` or `false`) is bound to `{id}` on submit.

- `id`: text; required

  Variable name used in `{id}` within `on-submit` actions.

- `text`: text; required

  Label shown beside the switch.

- `default`: `true` or `false`; default `false`

  Initial state of the switch.
