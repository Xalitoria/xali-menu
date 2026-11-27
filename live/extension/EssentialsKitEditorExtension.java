import com.earth2me.essentials.Kit;
import com.earth2me.essentials.MetaItemStack;
import com.earth2me.essentials.User;
import com.earth2me.essentials.craftbukkit.Inventories;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import net.ess3.api.IEssentials;
import net.ess3.api.TranslatableException;
import net.ess3.provider.SerializationProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.xalitoria.menu.Action;
import net.xalitoria.menu.Catalog;
import net.xalitoria.menu.Condition;
import net.xalitoria.menu.ExtensionContext;
import net.xalitoria.menu.ItemProperty;
import net.xalitoria.menu.ItemSource;
import net.xalitoria.menu.MenuContext;
import net.xalitoria.menu.MenuExtension;
import net.xalitoria.menu.ValidatedItemProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * XaliMenu extension for selecting and editing EssentialsX kits in game (`kits.conf`, `kiteditor.conf`).
 *
 * <ul>
 *   <li>Completers: `@kiteditor:kits` (all kits, requires `essentials.createkit`) and `@kiteditor:usable-kits` (kits the sender may claim, requires `essentials.kit` + `essentials.kits.<name>`).</li>
 *   <li>Conditions: `kiteditor:authorized` (`node`, via EssentialsX's `User.isAuthorized`), `kiteditor:exists` (`name`, optional `permitted = false`) and `kiteditor:ready` (`name`).</li>
 *   <li>Catalogs: `kiteditor:kits` (`usable = false`, requires `essentials.kit`) and `kiteditor:items` (`kit`, requires `essentials.createkit`).</li>
 *   <li>Item source: `kiteditor:<kit>/<slot>` producing the exact parsed `ItemStack` of that kit item.</li>
 *   <li>Item properties: `kiteditor:kit-info` (`kit`) and `kiteditor:append-lore` (`lines`).</li>
 *   <li>Actions: `kiteditor:claim` (`essentials.kit` + `essentials.kits.<name>`), `kiteditor:create` / `add-held` / `import-inventory` /
 *       `remove-item` / `give-item` / `set-delay` (`essentials.createkit`), and `kiteditor:delete` (`essentials.delkit` + `essentials.kits.<name>`).</li>
 * </ul>
 *
 * All EssentialsX class references live in {@link KitsSupport} so this class loads cleanly before EssentialsX enables.
 */
public final class EssentialsKitEditorExtension extends MenuExtension {
    @Override
    public String getIdentifier() {
        return "kiteditor";
    }

    @Override
    public String getAuthor() {
        return "xalitoria";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public List<String> getRequiredPlugins() {
        return List.of("Essentials");
    }

    @Override
    public void register(ExtensionContext context) {
        KitsSupport.register(context);
    }

    /** Everything that touches EssentialsX. */
    static final class KitsSupport {
        private KitsSupport() {}

        static void register(ExtensionContext context) {
            context.registerCompleter("kits", sender -> editorKitNames(sender));
            context.registerCompleter("usable-kits", KitsSupport::usableKitNames);

            context.registerCondition("authorized", settings -> new Authorized(settings.requireString("node")));
            context.registerCondition("exists", settings ->
                new KitExists(settings.requireString("name"), settings.getBoolean("permitted", false)));
            context.registerCondition("ready", settings -> new KitReady(settings.requireString("name")));

            context.registerCatalog(
                "kits",
                Map.of("name", "tools", "delay", "600", "delay_text", "10m", "items", "4", "ready", "true", "wait", "0", "permitted", "true", "icon", "kiteditor:tools/0"),
                Map.of("usable", "false"),
                settings -> new KitsCatalog(settings.getBoolean("usable", false)));
            context.registerCatalog(
                "items",
                Map.of("kit", "tools", "slot", "0", "amount", "1", "material", "stone_sword"),
                Map.of("kit", "{kit}"),
                settings -> new KitItemsCatalog(settings.requireString("kit")));

            context.registerItemSource(new KitItemSource());

            context.registerItemProperty("kit-info", Map.of("kit", "{kit}"),
                settings -> new KitInfoProperty(settings.requireString("kit")));
            context.registerItemProperty("append-lore", Map.of("lines", "[\"&7Line\"]"),
                settings -> new AppendLoreProperty(settings.getStrings("lines")));

            context.registerAction("claim", settings -> new ClaimKit(settings.requireString("kit")));
            context.registerAction("create", settings -> new CreateKit(settings.requireString("kit"), settings.getLong("delay", 0L)));
            context.registerAction("add-held", settings -> new AddHeldItem(settings.requireString("kit")));
            context.registerAction("import-inventory", settings -> new ImportInventory(settings.requireString("kit")));
            context.registerAction("remove-item", settings -> new RemoveItem(settings.requireString("kit"), settings.requireString("slot")));
            context.registerAction("give-item", settings -> new GiveItem(settings.requireString("kit"), settings.requireString("slot")));
            context.registerAction("set-delay", settings -> new SetDelay(settings.requireString("kit"), settings.requireString("delay")));
            context.registerAction("delete", settings -> new DeleteKit(settings.requireString("kit")));
        }

        static IEssentials essentials() {
            return (IEssentials) Bukkit.getPluginManager().getPlugin("Essentials");
        }

        static User user(MenuContext ctx) {
            return essentials().getUser(ctx.getPlayer());
        }

        static boolean permitted(String kitName, User user) {
            return user.isAuthorized("essentials.kits." + kitName.toLowerCase(Locale.ROOT));
        }

        static boolean ready(Kit kit, User user) {
            try {
                return kit.getNextUse(user) == 0;
            } catch (Exception ignored) {
                return false;
            }
        }

        static long waiting(Kit kit, User user) {
            try {
                long next = kit.getNextUse(user);
                return next <= 0 ? 0 : Math.max(0, (next - System.currentTimeMillis() + 999) / 1000);
            } catch (Exception ignored) {
                return 0;
            }
        }

        static long delayOf(IEssentials ess, String match) {
            Map<String, Object> kit = ess.getKits().getKit(match);
            if (kit == null) return 0L;
            Object value = kit.get("delay");
            if (value instanceof Number n) return n.longValue();
            if (value != null) {
                try {
                    return Long.parseLong(value.toString().trim());
                } catch (NumberFormatException ignored) {
                    return 0L;
                }
            }
            return 0L;
        }

        static String formatDelay(long seconds) {
            if (seconds < 0) return "One-time";
            if (seconds == 0) return "None";
            long d = seconds / 86400;
            long h = (seconds % 86400) / 3600;
            long m = (seconds % 3600) / 60;
            long s = seconds % 60;
            StringBuilder out = new StringBuilder();
            if (d > 0) out.append(d).append("d ");
            if (h > 0) out.append(h).append("h ");
            if (m > 0) out.append(m).append("m ");
            if (s > 0 || out.isEmpty()) out.append(s).append("s");
            return out.toString().trim();
        }

        /** Sorted kit names for editor tab completion; players without `essentials.createkit` receive none. */
        static List<String> editorKitNames(CommandSender sender) {
            IEssentials ess = essentials();
            if (ess == null) return List.of();
            if (sender instanceof Player player && !ess.getUser(player).isAuthorized("essentials.createkit")) {
                return List.of();
            }
            return new ArrayList<>(new TreeSet<>(ess.getKits().getKitKeys()));
        }

        /** Sorted kit names [sender] may claim (`essentials.kit` + `essentials.kits.<name>`). */
        static List<String> usableKitNames(CommandSender sender) {
            IEssentials ess = essentials();
            if (ess == null) return List.of();
            User user = sender instanceof Player player ? ess.getUser(player) : null;
            if (user != null && !user.isAuthorized("essentials.kit")) return List.of();
            List<String> names = new ArrayList<>();
            for (String key : new TreeSet<>(ess.getKits().getKitKeys())) {
                if (user != null && !permitted(key, user)) continue;
                names.add(key);
            }
            return names;
        }

        static List<String> rawLines(IEssentials ess, String match) {
            try {
                List<String> items = new Kit(match, ess).getItems();
                return items == null ? new ArrayList<>() : new ArrayList<>(items);
            } catch (Exception ignored) {
                return new ArrayList<>();
            }
        }

        record ParsedItem(int rawIndex, ItemStack stack) {}

        static boolean isNonItemLine(String line, String currency) {
            return line.isEmpty() || line.startsWith("/") || line.startsWith("$") || line.startsWith(currency);
        }

        static String currencyOf(IEssentials ess) {
            String symbol = ess.getSettings().getCurrencySymbol();
            return symbol == null || symbol.isEmpty() ? "$" : symbol;
        }

        /** Parses the real items of [kitName], keeping each item's original index in the kit's raw `items` list. */
        static List<ParsedItem> parseEntries(IEssentials ess, String kitName) {
            String match = ess.getKits().matchKit(kitName);
            if (match == null) return List.of();
            List<String> lines = rawLines(ess, match);
            if (lines.isEmpty()) return List.of();

            String currency = currencyOf(ess);
            boolean allowUnsafe = ess.getSettings().allowUnsafeEnchantments();
            SerializationProvider serializer = ess.provider(SerializationProvider.class);

            List<ParsedItem> result = new ArrayList<>();
            for (int rawIndex = 0; rawIndex < lines.size(); rawIndex++) {
                String raw = lines.get(rawIndex);
                if (raw == null) continue;
                String line = raw.trim();
                if (isNonItemLine(line, currency)) continue;
                if (line.startsWith("slot:")) {
                    int space = line.indexOf(' ');
                    if (space != -1) line = line.substring(space + 1).trim();
                }
                try {
                    ItemStack stack;
                    if (line.startsWith("@")) {
                        if (serializer == null) continue;
                        stack = serializer.deserializeItem(Base64.getMimeDecoder().decode(line.substring(1)));
                    } else {
                        String[] parts = line.split(" +");
                        if (parts.length == 0 || parts[0].isEmpty()) continue;
                        int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                        ItemStack base = ess.getItemDb().get(parts[0], amount);
                        if (base == null || base.getType() == Material.AIR) continue;
                        MetaItemStack meta = new MetaItemStack(base);
                        if (parts.length > 2) meta.parseStringMeta(null, allowUnsafe, parts, 2, ess);
                        stack = meta.getItemStack();
                    }
                    if (stack != null && stack.getType() != Material.AIR) {
                        result.add(new ParsedItem(rawIndex, stack));
                    }
                } catch (Exception ignored) {
                    // Skip broken lines rather than breaking the whole kit view.
                }
            }
            return result;
        }

        /** Serializes [stack] in EssentialsX's `@<base64>` format when available, falling back to `IItemDb.serialize`. */
        static String serializeItem(IEssentials ess, ItemStack stack, int slot) {
            SerializationProvider serializer = ess.provider(SerializationProvider.class);
            String encoded = serializer != null
                ? "@" + Base64.getEncoder().encodeToString(serializer.serializeItem(stack))
                : ess.getItemDb().serialize(stack);
            return slot >= 0 ? "slot:" + slot + " " + encoded : encoded;
        }

        static boolean requirePerm(MenuContext ctx, String permission) {
            User user = user(ctx);
            if (!user.isAuthorized(permission)) {
                user.sendTl("noAccessCommand");
                return false;
            }
            return true;
        }

        static void fail(User user, Throwable error) {
            if (error instanceof TranslatableException translatable) {
                user.sendTl(translatable.getTlKey(), translatable.getArgs());
            } else {
                essentials().showError(user.getSource(), error, "xalimenu");
            }
        }
    }

    // Conditions.

    private record Authorized(String node) implements Condition {
        @Override
        public boolean test(MenuContext ctx) {
            IEssentials ess = KitsSupport.essentials();
            if (ess == null) return false;
            String perm = ctx.parse(node).trim();
            return !perm.isEmpty() && ess.getUser(ctx.getPlayer()).isAuthorized(perm);
        }
    }

    private record KitExists(String name, boolean permitted) implements Condition {
        @Override
        public boolean test(MenuContext ctx) {
            IEssentials ess = KitsSupport.essentials();
            if (ess == null) return false;
            String resolved = ctx.parse(name).trim();
            if (resolved.isEmpty()) return false;
            String match = ess.getKits().matchKit(resolved);
            if (match == null) return false;
            return !permitted || KitsSupport.permitted(match, KitsSupport.user(ctx));
        }
    }

    private record KitReady(String name) implements Condition {
        @Override
        public boolean test(MenuContext ctx) {
            IEssentials ess = KitsSupport.essentials();
            if (ess == null) return false;
            String match = ess.getKits().matchKit(ctx.parse(name).trim());
            if (match == null) return false;
            try {
                Kit kit = new Kit(match, ess);
                User user = KitsSupport.user(ctx);
                return KitsSupport.permitted(match, user) && KitsSupport.ready(kit, user);
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    // Catalogs.

    private record KitsCatalog(boolean usable) implements Catalog {
        @Override
        public List<Map<String, String>> elements(MenuContext ctx) {
            IEssentials ess = KitsSupport.essentials();
            if (ess == null) return List.of();
            User user = KitsSupport.user(ctx);
            if (!user.isAuthorized("essentials.kit")) return List.of();
            List<Map<String, String>> rows = new ArrayList<>();
            for (String name : new TreeSet<>(ess.getKits().getKitKeys())) {
                try {
                    boolean allowed = KitsSupport.permitted(name, user);
                    if (usable && !allowed) continue;
                    Kit kit = new Kit(name, ess);
                    long delay = KitsSupport.delayOf(ess, name);
                    List<KitsSupport.ParsedItem> entries = KitsSupport.parseEntries(ess, name);
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("name", name);
                    row.put("delay", String.valueOf(delay));
                    row.put("delay_text", KitsSupport.formatDelay(delay));
                    row.put("items", String.valueOf(entries.size()));
                    row.put("permitted", String.valueOf(allowed));
                    row.put("ready", String.valueOf(allowed && KitsSupport.ready(kit, user)));
                    row.put("wait", String.valueOf(KitsSupport.waiting(kit, user)));
                    row.put("icon", entries.isEmpty() ? "chest" : "kiteditor:" + name + "/0");
                    rows.add(row);
                } catch (Exception ignored) {
                    // Skip broken kit definitions.
                }
            }
            return rows;
        }
    }

    private record KitItemsCatalog(String kit) implements Catalog {
        @Override
        public List<Map<String, String>> elements(MenuContext ctx) {
            IEssentials ess = KitsSupport.essentials();
            if (ess == null) return List.of();
            if (!KitsSupport.user(ctx).isAuthorized("essentials.createkit")) return List.of();
            String match = ess.getKits().matchKit(ctx.parse(kit).trim());
            if (match == null) return List.of();
            List<KitsSupport.ParsedItem> entries = KitsSupport.parseEntries(ess, match);
            List<Map<String, String>> out = new ArrayList<>(entries.size());
            for (int i = 0; i < entries.size(); i++) {
                ItemStack stack = entries.get(i).stack();
                Map<String, String> row = new LinkedHashMap<>();
                row.put("kit", match);
                row.put("slot", String.valueOf(i));
                row.put("amount", String.valueOf(Math.max(1, stack.getAmount())));
                row.put("material", stack.getType().getKey().getKey());
                out.add(row);
            }
            return out;
        }
    }

    // Item source.

    private static final class KitItemSource implements ItemSource {
        @Override
        public String getPrefix() {
            return "kiteditor";
        }

        @Override
        public ItemStack item(String id) {
            IEssentials ess = KitsSupport.essentials();
            if (ess == null || id == null) return null;
            int slash = id.lastIndexOf('/');
            if (slash <= 0 || slash == id.length() - 1) return null;
            String kitName = id.substring(0, slash);
            int index;
            try {
                index = Integer.parseInt(id.substring(slash + 1));
            } catch (NumberFormatException ignored) {
                return null;
            }
            if (index < 0) return null;
            List<KitsSupport.ParsedItem> entries = KitsSupport.parseEntries(ess, kitName);
            return index < entries.size() ? entries.get(index).stack() : null;
        }
    }

    // Item properties.

    private record KitInfoProperty(String kit) implements ValidatedItemProperty {
        @Override
        public List<String> validate() {
            if (kit.contains("{") || kit.contains("%")) return List.of();
            IEssentials ess = KitsSupport.essentials();
            if (ess != null && ess.getKits().matchKit(kit) == null) {
                return List.of("kit '" + kit + "' does not exist");
            }
            return List.of();
        }

        @Override
        public void apply(ItemStack item, MenuContext ctx) {
            IEssentials ess = KitsSupport.essentials();
            if (ess == null) return;
            String match = ess.getKits().matchKit(ctx.parse(kit).trim());
            if (match == null) return;
            long delay = KitsSupport.delayOf(ess, match);
            int count = KitsSupport.parseEntries(ess, match).size();
            List<Component> extra = List.of(
                ctx.component("&7Delay: &f" + KitsSupport.formatDelay(delay) + " &8(" + delay + "s)")
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE),
                ctx.component("&7Items: &f" + count)
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
            );
            item.editMeta(meta -> {
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.addAll(0, extra);
                meta.lore(lore);
            });
        }
    }

    private record AppendLoreProperty(List<String> lines) implements ItemProperty {
        @Override
        public void apply(ItemStack item, MenuContext ctx) {
            if (lines.isEmpty()) return;
            List<Component> extra = new ArrayList<>(lines.size());
            for (String line : lines) {
                extra.add(ctx.component(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            item.editMeta(meta -> {
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.addAll(extra);
                meta.lore(lore);
            });
        }
    }

    // Actions.

    private record ClaimKit(String kit) implements Action {
        @Override
        public void run(MenuContext ctx) {
            IEssentials ess = KitsSupport.essentials();
            if (ess == null) return;
            if (!KitsSupport.requirePerm(ctx, "essentials.kit")) return;
            User user = KitsSupport.user(ctx);
            try {
                String match = ess.getKits().matchKit(ctx.parse(kit).trim());
                if (match == null) {
                    user.sendTl("kitNotFound");
                    return;
                }
                Kit resolved = new Kit(match, ess);
                resolved.checkPerms(user);
                resolved.checkDelay(user);
                resolved.checkAffordable(user);
                resolved.setTime(user);
                resolved.chargeUser(user);
                boolean fits = resolved.expandItems(user);
                user.sendTl("kitReceive", match);
                if (!fits) user.sendTl("kitInvFull");
            } catch (Exception error) {
                KitsSupport.fail(user, error);
            }
        }
    }

    private record CreateKit(String kit, long delay) implements Action {
        @Override
        public void run(MenuContext ctx) {
            if (!KitsSupport.requirePerm(ctx, "essentials.createkit")) {
                ctx.abort();
                return;
            }
            IEssentials ess = KitsSupport.essentials();
            String name = ctx.parse(kit).trim().toLowerCase(Locale.ENGLISH);
            if (!name.matches("[a-z0-9_-]+")) {
                ctx.getPlayer().sendMessage(ctx.component("&cInvalid kit name. Use letters, digits, _ or -."));
                ctx.abort();
                return;
            }
            if (ess.getKits().matchKit(name) != null) {
                ctx.getPlayer().sendMessage(ctx.component("&cKit '" + name + "' already exists."));
                ctx.abort();
                return;
            }
            ess.getKits().addKit(name, new ArrayList<>(), delay);
            ctx.getPlayer().sendMessage(ctx.component("&aCreated kit &e" + name + "&a."));
        }
    }

    private record AddHeldItem(String kit) implements Action {
        @Override
        public void run(MenuContext ctx) {
            if (!KitsSupport.requirePerm(ctx, "essentials.createkit")) return;
            IEssentials ess = KitsSupport.essentials();
            String match = ess.getKits().matchKit(ctx.parse(kit).trim());
            if (match == null) {
                ctx.getPlayer().sendMessage(ctx.component("&cKit not found."));
                return;
            }
            ItemStack held = ctx.getPlayer().getInventory().getItemInMainHand();
            if (held == null || held.getType() == Material.AIR || held.getAmount() <= 0) {
                ctx.getPlayer().sendMessage(ctx.component("&cHold an item in your main hand to add it."));
                return;
            }
            List<String> lines = KitsSupport.rawLines(ess, match);
            lines.add(KitsSupport.serializeItem(ess, held.clone(), -1));
            ess.getKits().addKit(match, lines, KitsSupport.delayOf(ess, match));
            ctx.getPlayer().sendMessage(ctx.component("&aAdded &f" + held.getType().getKey().getKey() + " x" + held.getAmount() + " &ato kit &e" + match + "&a."));
            ctx.refresh();
        }
    }

    private record ImportInventory(String kit) implements Action {
        @Override
        public void run(MenuContext ctx) {
            if (!KitsSupport.requirePerm(ctx, "essentials.createkit")) return;
            IEssentials ess = KitsSupport.essentials();
            String match = ess.getKits().matchKit(ctx.parse(kit).trim());
            if (match == null) {
                ctx.getPlayer().sendMessage(ctx.component("&cKit not found."));
                return;
            }
            // Keep existing command and currency lines so editing items does not wipe kit commands or money rewards.
            String currency = KitsSupport.currencyOf(ess);
            List<String> kept = new ArrayList<>();
            for (String raw : KitsSupport.rawLines(ess, match)) {
                if (raw != null && KitsSupport.isNonItemLine(raw.trim(), currency) && !raw.trim().isEmpty()) {
                    kept.add(raw);
                }
            }
            ItemStack[] contents = Inventories.getInventory(ctx.getPlayer(), true);
            List<String> added = new ArrayList<>();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack stack = contents[slot];
                if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) continue;
                added.add(KitsSupport.serializeItem(ess, stack.clone(), slot));
            }
            if (added.isEmpty()) {
                ctx.getPlayer().sendMessage(ctx.component("&cYour inventory is empty."));
                return;
            }
            added.addAll(kept);
            ess.getKits().addKit(match, added, KitsSupport.delayOf(ess, match));
            ctx.getPlayer().sendMessage(ctx.component("&aImported &f" + (added.size() - kept.size()) + " &aitem(s) into kit &e" + match + "&a."));
            ctx.refresh();
        }
    }

    private record RemoveItem(String kit, String slotSpec) implements Action {
        @Override
        public void run(MenuContext ctx) {
            if (!KitsSupport.requirePerm(ctx, "essentials.createkit")) return;
            IEssentials ess = KitsSupport.essentials();
            String match = ess.getKits().matchKit(ctx.parse(kit).trim());
            if (match == null) return;
            int index;
            try {
                index = Integer.parseInt(ctx.parse(slotSpec).trim());
            } catch (NumberFormatException ignored) {
                return;
            }
            List<KitsSupport.ParsedItem> entries = KitsSupport.parseEntries(ess, match);
            if (index < 0 || index >= entries.size()) return;
            int rawIndex = entries.get(index).rawIndex();
            List<String> lines = KitsSupport.rawLines(ess, match);
            if (rawIndex < 0 || rawIndex >= lines.size()) return;
            lines.remove(rawIndex);
            ess.getKits().addKit(match, lines, KitsSupport.delayOf(ess, match));
            ctx.getPlayer().sendMessage(ctx.component("&eRemoved item from kit &f" + match + "&e."));
            ctx.refresh();
        }
    }

    private record GiveItem(String kit, String slotSpec) implements Action {
        @Override
        public void run(MenuContext ctx) {
            if (!KitsSupport.requirePerm(ctx, "essentials.createkit")) return;
            IEssentials ess = KitsSupport.essentials();
            String match = ess.getKits().matchKit(ctx.parse(kit).trim());
            if (match == null) return;
            int index;
            try {
                index = Integer.parseInt(ctx.parse(slotSpec).trim());
            } catch (NumberFormatException ignored) {
                return;
            }
            List<KitsSupport.ParsedItem> entries = KitsSupport.parseEntries(ess, match);
            if (index < 0 || index >= entries.size()) return;
            ItemStack copy = entries.get(index).stack().clone();
            Player player = ctx.getPlayer();
            for (ItemStack leftover : player.getInventory().addItem(copy).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    private record SetDelay(String kit, String delaySpec) implements Action {
        @Override
        public void run(MenuContext ctx) {
            if (!KitsSupport.requirePerm(ctx, "essentials.createkit")) {
                ctx.abort();
                return;
            }
            IEssentials ess = KitsSupport.essentials();
            String match = ess.getKits().matchKit(ctx.parse(kit).trim());
            if (match == null) {
                ctx.getPlayer().sendMessage(ctx.component("&cKit not found."));
                ctx.abort();
                return;
            }
            long seconds;
            try {
                seconds = Long.parseLong(ctx.parse(delaySpec).trim());
            } catch (NumberFormatException ignored) {
                ctx.getPlayer().sendMessage(ctx.component("&cDelay must be a whole number of seconds (-1 for one-time)."));
                ctx.abort();
                return;
            }
            if (seconds < -1) {
                ctx.getPlayer().sendMessage(ctx.component("&cDelay must be -1 (one-time) or 0 and above."));
                ctx.abort();
                return;
            }
            ess.getKits().addKit(match, KitsSupport.rawLines(ess, match), seconds);
            ctx.getPlayer().sendMessage(ctx.component("&aSet delay of kit &e" + match + " &ato &f" + KitsSupport.formatDelay(seconds) + "&a."));
        }
    }

    private record DeleteKit(String kit) implements Action {
        @Override
        public void run(MenuContext ctx) {
            if (!KitsSupport.requirePerm(ctx, "essentials.delkit")) {
                ctx.abort();
                return;
            }
            IEssentials ess = KitsSupport.essentials();
            User user = KitsSupport.user(ctx);
            String match = ess.getKits().matchKit(ctx.parse(kit).trim());
            if (match == null) {
                user.sendTl("kitNotFound");
                ctx.abort();
                return;
            }
            // Match EssentialsX's Commanddelkit: deleting a kit also requires essentials.kits.<name>.
            try {
                new Kit(match, ess).checkPerms(user);
            } catch (Exception error) {
                KitsSupport.fail(user, error);
                ctx.abort();
                return;
            }
            ess.getKits().removeKit(match);
            ctx.getPlayer().sendMessage(ctx.component("&cDeleted kit &e" + match + "&c."));
        }
    }
}
