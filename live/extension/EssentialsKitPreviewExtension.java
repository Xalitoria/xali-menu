import com.earth2me.essentials.Kit;
import com.earth2me.essentials.MetaItemStack;
import com.earth2me.essentials.User;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import net.ess3.api.IEssentials;
import net.ess3.provider.SerializationProvider;
import net.xalitoria.menu.Catalog;
import net.xalitoria.menu.Condition;
import net.xalitoria.menu.ExtensionContext;
import net.xalitoria.menu.ItemSource;
import net.xalitoria.menu.MenuContext;
import net.xalitoria.menu.MenuExtension;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A focused XaliMenu extension for previewing EssentialsX kits via `/kitpreview <kit>`:
 *
 * <ul>
 *   <li>Completers: `@kitpreview:kits` (all kits, requires `essentials.showkit`) and `@kitpreview:usable-kits` (kits the sender may preview and claim).</li>
 *   <li>Conditions: `kitpreview:authorized` (`node`, checked via EssentialsX's `User.isAuthorized`) and `kitpreview:exists` (`name`, optional `permitted = false`).</li>
 *   <li>Catalog: `kitpreview:items` (`kit`), listing each real item of the kit with `kit`, `slot`, `amount` and `material` (requires `essentials.showkit`).</li>
 *   <li>Item source: `kitpreview:<kit>/<slot>`, producing the exact parsed `ItemStack` EssentialsX gives for that kit entry.</li>
 * </ul>
 *
 * Pair with `kitpreview.conf` in `plugins/XaliMenu/menus/`. EssentialsX classes are confined to {@link Preview} so this class
 * loads cleanly before EssentialsX enables.
 */
public final class EssentialsKitPreviewExtension extends MenuExtension {
    @Override
    public String getIdentifier() {
        return "kitpreview";
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
        Preview.register(context);
    }

    /** Everything that touches EssentialsX. */
    static final class Preview {
        private Preview() {}

        static void register(ExtensionContext context) {
            context.registerCompleter("kits", sender -> kitNames(sender, false));
            context.registerCompleter("usable-kits", sender -> kitNames(sender, true));
            context.registerCondition("authorized", settings -> new Authorized(settings.requireString("node")));
            context.registerCondition("exists", settings ->
                new KitExists(settings.requireString("name"), settings.getBoolean("permitted", false)));
            context.registerCatalog(
                "items",
                Map.of("kit", "tools", "slot", "0", "amount", "1", "material", "stone_sword"),
                Map.of("kit", "{kit}"),
                settings -> new KitItems(settings.requireString("kit")));
            context.registerItemSource(new KitItemSource());
        }

        static IEssentials essentials() {
            return (IEssentials) Bukkit.getPluginManager().getPlugin("Essentials");
        }

        /** Sorted kit names for [sender]; players must have `essentials.showkit`, and when [usableOnly] also `essentials.kits.<name>`. */
        static List<String> kitNames(CommandSender sender, boolean usableOnly) {
            IEssentials ess = essentials();
            if (ess == null) return List.of();
            User user = sender instanceof Player player ? ess.getUser(player) : null;
            if (user != null && !user.isAuthorized("essentials.showkit")) return List.of();
            List<String> names = new ArrayList<>();
            for (String key : new TreeSet<>(ess.getKits().getKitKeys())) {
                if (usableOnly && user != null && !user.isAuthorized("essentials.kits." + key.toLowerCase(Locale.ROOT))) continue;
                names.add(key);
            }
            return names;
        }

        /** Parses the real item stacks of [kitName] the same way EssentialsX's `Kit.expandItems` does, skipping commands and currency lines. */
        static List<ItemStack> parseItems(IEssentials ess, String kitName) {
            String match = ess.getKits().matchKit(kitName);
            if (match == null) return List.of();
            List<String> lines;
            try {
                lines = new Kit(match, ess).getItems();
            } catch (Exception ignored) {
                return List.of();
            }
            if (lines == null || lines.isEmpty()) return List.of();

            String currency = ess.getSettings().getCurrencySymbol();
            if (currency == null || currency.isEmpty()) currency = "$";
            boolean allowUnsafe = ess.getSettings().allowUnsafeEnchantments();
            SerializationProvider serializer = ess.provider(SerializationProvider.class);

            List<ItemStack> result = new ArrayList<>();
            for (String raw : lines) {
                if (raw == null) continue;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("/") || line.startsWith("$") || line.startsWith(currency)) continue;
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
                    if (stack != null && stack.getType() != Material.AIR) result.add(stack);
                } catch (Exception ignored) {
                    // A malformed entry in a kit is skipped rather than breaking the whole preview.
                }
            }
            return result;
        }
    }

    private record Authorized(String node) implements Condition {
        @Override
        public boolean test(MenuContext ctx) {
            IEssentials ess = Preview.essentials();
            if (ess == null) return false;
            String perm = ctx.parse(node).trim();
            return !perm.isEmpty() && ess.getUser(ctx.getPlayer()).isAuthorized(perm);
        }
    }

    private record KitExists(String name, boolean permitted) implements Condition {
        @Override
        public boolean test(MenuContext ctx) {
            IEssentials ess = Preview.essentials();
            if (ess == null) return false;
            String resolved = ctx.parse(name).trim();
            if (resolved.isEmpty()) return false;
            String match = ess.getKits().matchKit(resolved);
            if (match == null) return false;
            if (!permitted) return true;
            return ess.getUser(ctx.getPlayer()).isAuthorized("essentials.kits." + match.toLowerCase(Locale.ROOT));
        }
    }

    private record KitItems(String kit) implements Catalog {
        @Override
        public List<Map<String, String>> elements(MenuContext ctx) {
            IEssentials ess = Preview.essentials();
            if (ess == null) return List.of();
            if (!ess.getUser(ctx.getPlayer()).isAuthorized("essentials.showkit")) return List.of();
            String resolved = ctx.parse(kit).trim();
            if (resolved.isEmpty()) return List.of();
            String match = ess.getKits().matchKit(resolved);
            if (match == null) return List.of();
            List<ItemStack> items = Preview.parseItems(ess, match);
            List<Map<String, String>> out = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                ItemStack stack = items.get(i);
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

    private static final class KitItemSource implements ItemSource {
        @Override
        public String getPrefix() {
            return "kitpreview";
        }

        @Override
        public ItemStack item(String id) {
            IEssentials ess = Preview.essentials();
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
            List<ItemStack> items = Preview.parseItems(ess, kitName);
            return index < items.size() ? items.get(index) : null;
        }
    }
}
