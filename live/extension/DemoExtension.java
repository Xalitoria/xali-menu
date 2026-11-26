import java.util.List;
import java.util.Map;
import me.clip.placeholderapi.PlaceholderAPI;
import net.xalitoria.menu.Action;
import net.xalitoria.menu.ActivatorFilter;
import net.xalitoria.menu.ActivatorSource;
import net.xalitoria.menu.Condition;
import net.xalitoria.menu.ExtensionContext;
import net.xalitoria.menu.MenuContext;
import net.xalitoria.menu.MenuExtension;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Part of the live tests of XaliMenu, see ../README.md: an extension in Java for PlaceholderAPI, which knows nothing of XaliMenu. It
 * adds the condition `demo:papi`, the action `demo:say` and the activator `demo:join`, which a listener of the extension fires. It
 * is built into `plugins/XaliMenu/extensions/demo.jar` (`XM_EXTENSION=1` at setup); it is not a plugin and has no `plugin.yml`.
 */
public final class DemoExtension extends MenuExtension {
    @Override
    public String getIdentifier() {
        return "demo";
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
        return List.of("PlaceholderAPI");
    }

    @Override
    public void register(ExtensionContext context) {
        context.registerCondition("papi", settings -> new Papi(settings.requireString("placeholder"), settings.requireString("equals")));
        context.registerAction("say", settings -> new Say(settings.requireString("text")));
        ActivatorSource join = context.registerActivator("join", settings -> (ActivatorFilter) values -> true);
        context.registerListener(new Joined(join));
        context.getLogger().info("registered; data folder " + context.getDataFolder());
    }

    /** Uses a class of PlaceholderAPI, which only the class loader of the extension's jar can find by looking in the plugins. */
    private record Papi(String placeholder, String expected) implements Condition {
        @Override
        public boolean test(MenuContext ctx) {
            return expected.equals(PlaceholderAPI.setPlaceholders(ctx.getPlayer(), placeholder));
        }
    }

    private record Say(String text) implements Action {
        @Override
        public void run(MenuContext ctx) {
            ctx.getPlayer().sendMessage("said: " + text);
        }
    }

    private record Joined(ActivatorSource source) implements Listener {
        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            source.fire(event.getPlayer(), Map.of());
        }
    }
}
