import com.earth2me.essentials.Kit;
import com.earth2me.essentials.Trade;
import com.earth2me.essentials.User;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.ess3.api.IEssentials;
import net.ess3.api.TranslatableException;
import net.ess3.api.events.AfkStatusChangeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.xalitoria.menu.Action;
import net.xalitoria.menu.Catalog;
import net.xalitoria.menu.Condition;
import net.xalitoria.menu.ExtensionContext;
import net.xalitoria.menu.MenuContext;
import net.xalitoria.menu.MenuExtension;
import net.xalitoria.menu.Settings;
import net.xalitoria.menu.ValidatedItemProperty;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Part of the live tests of XaliMenu, see ../README.md: an extension for EssentialsX, which knows nothing of XaliMenu and has no hook for
 * it. It is built into `plugins/XaliMenu/extensions/essentials.jar` (`XM_ESSENTIALS=<EssentialsX jar>` at setup); it is not a plugin.
 *
 * <ul>
 *   <li>Catalogs: `essentials:warps`, `essentials:homes` and `essentials:kits`; every element has `name`, and warps and homes also
 *       `world`, `x`, `y`, `z`; kits `ready` and `wait` (seconds until the kit can be used again).</li>
 *   <li>Conditions: `essentials:state` (`is = afk|god|vanished|muted|jailed`), `essentials:home` (the viewer has a home, `name` for a
 *       given one) and `essentials:kit` (the viewer may use the kit now).</li>
 *   <li>Actions: `essentials:warp`, `essentials:home`, `essentials:sethome`, `essentials:delhome`, `essentials:kit`, `essentials:back`
 *       and `essentials:afk`. They go through EssentialsX's own rules (permissions, delays, fees, the home limit) and answer in its
 *       language, as its commands do.</li>
 *   <li>Item properties: `essentials:kit-preview` (`kit`, optional `prefix`), which appends the items of a kit to the stack's lore
 *       and validates static kit names.</li>
 * </ul>
 *
 * The class that is created by XaliMenu mentions nothing of EssentialsX: everything that does is in {@link Ess} and the classes
 * under it, which are only loaded once {@link #register} reaches them.
 */
public final class EssentialsExtension extends MenuExtension {
    @Override
    public String getIdentifier() {
        return "essentials";
    }

    @Override
    public String getAuthor() {
        return "live tests";
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
        Ess.register(context);
    }

    /** What touches EssentialsX. */
    static final class Ess {
        private Ess() {}

        static void register(ExtensionContext context) {
            context.registerCatalog("warps", Map.of("name", "spawn", "world", "world", "x", "0", "y", "64", "z", "0"),
                Map.of("usable", "true"), settings -> new Warps(settings.getBoolean("usable", true)));
            context.registerCatalog("homes", Map.of("name", "home", "world", "world", "x", "0", "y", "64", "z", "0"), settings -> new Homes());
            context.registerCatalog("kits", Map.of("name", "tools", "ready", "true", "wait", "0"),
                Map.of("usable", "true"), settings -> new Kits(settings.getBoolean("usable", true)));

            context.registerCondition("state", settings -> new State(settings.requireString("is")));
            context.registerCondition("home", settings -> new HasHome(settings.getString("name")));
            context.registerCondition("kit", settings -> new KitReady(settings.requireString("name")));

            context.registerAction("warp", settings -> new Warp(settings.requireString("name")));
            context.registerAction("home", settings -> new Home(settings.getString("name", "home")));
            context.registerAction("sethome", settings -> new SetHome(settings.getString("name", "home")));
            context.registerAction("delhome", settings -> new DelHome(settings.getString("name", "home")));
            context.registerAction("kit", settings -> new GiveKit(settings.requireString("name")));
            context.registerAction("back", settings -> new Back());
            context.registerAction("afk", settings -> new Afk());

            context.registerItemProperty("kit-preview", Map.of("kit", "tools", "prefix", "&7- "),
                settings -> new KitPreview(settings.requireString("kit"), settings.getString("prefix", "&7- ")));
        }

        static IEssentials essentials() {
            return (IEssentials) Bukkit.getPluginManager().getPlugin("Essentials");
        }

        static User user(MenuContext ctx) {
            return essentials().getUser(ctx.getPlayer());
        }

        /** Says what went wrong in EssentialsX's own words, the way its commands do. */
        static void fail(User user, Throwable error) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            if (cause instanceof TranslatableException translatable) {
                user.sendTl(translatable.getTlKey(), translatable.getArgs());
            } else {
                essentials().showError(user.getSource(), cause, "xalimenu");
            }
        }

        /** Runs [body] for an action that needs the permission; a denial and a failure are answered as EssentialsX would. */
        static void run(MenuContext ctx, String permission, Body body) {
            User user = user(ctx);
            if (!user.isAuthorized("essentials." + permission)) {
                user.sendTl("noAccessCommand");
                return;
            }
            try {
                body.run(user);
            } catch (Exception error) {
                fail(user, error);
            }
        }

        /** The result of an asynchronous teleport: it answers a failure, and a success is told by EssentialsX itself. */
        static CompletableFuture<Boolean> teleportResult(User user) {
            return teleportResult(user, null);
        }

        /** The same, and when it worked [key] (with [args]) is said, which a command of EssentialsX says itself and the teleport does not. */
        static CompletableFuture<Boolean> teleportResult(User user, String key, Object... args) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.whenComplete((done, error) -> {
                if (error != null) {
                    fail(user, error);
                } else if (Boolean.TRUE.equals(done) && key != null) {
                    user.sendTl(key, args);
                }
            });
            return future;
        }

        static boolean perWarp(User user, String warp, boolean usable) {
            return !usable || !essentials().getSettings().getPerWarpPermission() || user.isAuthorized("essentials.warps." + warp);
        }

        static Map<String, String> place(String name, Location location) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("name", name);
            values.put("world", location.getWorld() == null ? "" : location.getWorld().getName());
            values.put("x", String.valueOf(location.getBlockX()));
            values.put("y", String.valueOf(location.getBlockY()));
            values.put("z", String.valueOf(location.getBlockZ()));
            return values;
        }

        /**
         * Whether the viewer may use the kit. The checks of {@link Kit} itself (checkPerms, checkDelay) tell the player why they fail, which
         * a catalog or a condition asked on every refresh must not do, so these read the same facts without saying anything.
         */
        static boolean permitted(Kit kit, User user) {
            return user.isAuthorized("essentials.kits." + kit.getName().toLowerCase(Locale.ROOT));
        }

        /** Whether the kit can be used now: it has never been used, or its delay is over. A kit that is once only and used is not. */
        static boolean ready(Kit kit, User user) {
            try {
                return kit.getNextUse(user) == 0;
            } catch (Exception unknown) {
                return false;
            }
        }

        /** Seconds until the kit can be used again; 0 when it can, or never can. */
        static long waiting(Kit kit, User user) {
            try {
                long next = kit.getNextUse(user);
                return next <= 0 ? 0 : Math.max(0, (next - System.currentTimeMillis() + 999) / 1000);
            } catch (Exception unknown) {
                return 0;
            }
        }

        @FunctionalInterface
        interface Body {
            void run(User user) throws Exception;
        }
    }

    // Catalogs.

    private record Warps(boolean usable) implements Catalog {
        @Override
        public List<Map<String, String>> elements(MenuContext ctx) {
            User user = Ess.user(ctx);
            List<Map<String, String>> elements = new ArrayList<>();
            for (String name : new TreeSet<>(Ess.essentials().getWarps().getList())) {
                if (!Ess.perWarp(user, name, usable)) continue;
                try {
                    elements.add(Ess.place(name, Ess.essentials().getWarps().getWarp(name)));
                } catch (Exception ignored) {
                    // a warp in a world that is not loaded has no place to show
                }
            }
            return elements;
        }
    }

    private record Homes() implements Catalog {
        @Override
        public List<Map<String, String>> elements(MenuContext ctx) {
            User user = Ess.user(ctx);
            List<Map<String, String>> elements = new ArrayList<>();
            for (String name : new TreeSet<>(user.getHomes())) {
                try {
                    elements.add(Ess.place(name, user.getHome(name)));
                } catch (Exception ignored) {
                    // a home in a world that is not loaded
                }
            }
            return elements;
        }
    }

    private record Kits(boolean usable) implements Catalog {
        @Override
        public List<Map<String, String>> elements(MenuContext ctx) {
            IEssentials ess = Ess.essentials();
            User user = Ess.user(ctx);
            List<Map<String, String>> elements = new ArrayList<>();
            for (String name : new TreeSet<>(ess.getKits().getKitKeys())) {
                try {
                    Kit kit = new Kit(name, ess);
                    boolean allowed = Ess.permitted(kit, user);
                    if (usable && !allowed) continue;
                    Map<String, String> values = new LinkedHashMap<>();
                    values.put("name", name);
                    values.put("ready", String.valueOf(allowed && Ess.ready(kit, user)));
                    values.put("wait", String.valueOf(Ess.waiting(kit, user)));
                    elements.add(values);
                } catch (Exception ignored) {
                    // a kit that is not well defined is left out, as /kits does
                }
            }
            return elements;
        }
    }

    // Conditions.

    private record State(String what) implements Condition {
        State {
            if (!List.of("afk", "god", "vanished", "muted", "jailed").contains(what)) {
                throw new IllegalArgumentException("'is' must be afk, god, vanished, muted or jailed, not '" + what + "'");
            }
        }

        @Override
        public boolean test(MenuContext ctx) {
            User user = Ess.user(ctx);
            return switch (what) {
                case "afk" -> user.isAfk();
                case "god" -> user.isGodModeEnabled();
                case "vanished" -> user.isVanished();
                case "muted" -> user.isMuted();
                default -> user.isJailed();
            };
        }
    }

    private record HasHome(String name) implements Condition {
        @Override
        public boolean test(MenuContext ctx) {
            List<String> homes = Ess.user(ctx).getHomes();
            return name == null ? !homes.isEmpty() : homes.stream().anyMatch(home -> home.equalsIgnoreCase(name));
        }
    }

    private record KitReady(String name) implements Condition {
        @Override
        public boolean test(MenuContext ctx) {
            try {
                Kit kit = new Kit(name, Ess.essentials());
                User user = Ess.user(ctx);
                return Ess.permitted(kit, user) && Ess.ready(kit, user);
            } catch (Exception missing) {
                return false;
            }
        }
    }

    // Actions.

    private record Warp(String name) implements Action {
        @Override
        public void run(MenuContext ctx) {
            Ess.run(ctx, "warp", user -> {
                String name = ctx.parse(this.name);
                IEssentials ess = Ess.essentials();
                if (!Ess.perWarp(user, name, true)) {
                    user.sendTl("noPerm", "essentials.warps." + name);
                    return;
                }
                user.getAsyncTeleport().warp(user, name, new Trade("warp", ess), PlayerTeleportEvent.TeleportCause.COMMAND, Ess.teleportResult(user));
            });
        }
    }

    private record Home(String name) implements Action {
        @Override
        public void run(MenuContext ctx) {
            Ess.run(ctx, "home", user -> {
                Location location = user.getHome(ctx.parse(name));
                if (location == null) {
                    user.sendTl("invalidHome", ctx.parse(name));
                    return;
                }
                user.getAsyncTeleport().teleport(location, new Trade("home", Ess.essentials()), PlayerTeleportEvent.TeleportCause.COMMAND,
                    Ess.teleportResult(user, "teleportHome", ctx.parse(name)));
            });
        }
    }

    private record SetHome(String name) implements Action {
        @Override
        public void run(MenuContext ctx) {
            Ess.run(ctx, "sethome", user -> {
                String name = ctx.parse(this.name);
                if (!name.matches("[A-Za-z0-9_-]+")) {
                    user.sendTl("invalidHomeName");
                    return;
                }
                boolean known = user.getHomes().stream().anyMatch(home -> home.equalsIgnoreCase(name));
                int limit = Ess.essentials().getSettings().getHomeLimit(user);
                if (!known && user.getHomes().size() >= limit && !user.isAuthorized("essentials.sethome.multiple.unlimited")) {
                    user.sendTl("maxHomes", limit);
                    return;
                }
                user.setHome(name, user.getBase().getLocation());
                user.sendTl("homeSet");
            });
        }
    }

    private record DelHome(String name) implements Action {
        @Override
        public void run(MenuContext ctx) {
            Ess.run(ctx, "delhome", user -> {
                String name = ctx.parse(this.name);
                user.delHome(name);
                user.sendTl("deleteHome", name);
            });
        }
    }

    private record GiveKit(String name) implements Action {
        @Override
        public void run(MenuContext ctx) {
            User user = Ess.user(ctx);
            try {
                IEssentials ess = Ess.essentials();
                String match = ess.getKits().matchKit(ctx.parse(name));
                if (match == null) {
                    user.sendTl("kitNotFound");
                    return;
                }
                // The same steps, in the same order, as EssentialsX's /kit.
                Kit kit = new Kit(match, ess);
                kit.checkPerms(user);
                kit.checkDelay(user);
                kit.checkAffordable(user);
                kit.setTime(user);
                kit.chargeUser(user);
                boolean fits = kit.expandItems(user);
                user.sendTl("kitReceive", match);
                if (!fits) user.sendTl("kitInvFull");
            } catch (Exception error) {
                Ess.fail(user, error);
            }
        }
    }

    private record Back() implements Action {
        @Override
        public void run(MenuContext ctx) {
            Ess.run(ctx, "back", user -> user.getAsyncTeleport().back(new Trade("back", Ess.essentials()), Ess.teleportResult(user)));
        }
    }

    private record Afk() implements Action {
        @Override
        public void run(MenuContext ctx) {
            Ess.run(ctx, "afk", user -> user.setAfk(!user.isAfk(), AfkStatusChangeEvent.Cause.COMMAND));
        }
    }

    // Item properties.

    private record KitPreview(String kit, String prefix) implements ValidatedItemProperty {
        @Override
        public List<String> validate() {
            if (kit.contains("{") || kit.contains("%")) return List.of();
            IEssentials ess = Ess.essentials();
            if (ess != null && ess.getKits().matchKit(kit) == null) {
                return List.of("kit '" + kit + "' does not exist");
            }
            return List.of();
        }

        @Override
        public void apply(ItemStack item, MenuContext ctx) {
            IEssentials ess = Ess.essentials();
            if (ess == null) return;
            String resolved = ctx.parse(kit).trim();
            if (resolved.isEmpty()) return;
            String match = ess.getKits().matchKit(resolved);
            if (match == null) return;
            List<String> raw;
            try {
                raw = new Kit(match, ess).getItems();
            } catch (Exception ignored) {
                return;
            }
            if (raw == null || raw.isEmpty()) return;
            List<Component> extra = new ArrayList<>();
            for (String line : raw) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("/")) continue;
                String[] parts = trimmed.split("\\s+");
                String material = parts[0].split(":")[0];
                String amount = parts.length > 1 ? parts[1] : "1";
                extra.add(ctx.component(prefix + material + " x" + amount)
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            if (extra.isEmpty()) return;
            item.editMeta(meta -> {
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.addAll(extra);
                meta.lore(lore);
            });
        }
    }
}
