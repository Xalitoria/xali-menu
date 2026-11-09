import java.util.Map;
import net.xalitoria.menu.ActivatorFilter;
import net.xalitoria.menu.ActivatorSource;
import net.xalitoria.menu.XaliMenuApi;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Part of the live tests of XaliMenu, see ../README.md: a plugin written in Java that adds an activator. `/xmwave <emote> [times]`
 * says that the player did an emote; the answer is whether the activator asks for the event to be canceled. `/xmsave` saves the
 * player's data (bot/inventory.js uses it to leave a crash behind).
 */
public class XmDemo extends JavaPlugin {
    private ActivatorSource emotes;

    @Override
    public void onEnable() {
        emotes = XaliMenuApi.get().registerActivator(this, "xmdemo:emote", settings -> {
            String wanted = settings.requireString("name");
            int least = settings.getInt("times", 1);
            return values -> wanted.equals(values.get("emote")) && Integer.parseInt(values.get("times")) >= least;
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // `/xmsave` writes the player's data now, as an autosave does: a server that goes down after it has what the player had then.
        if (command.getName().equals("xmsave") && sender instanceof Player saved) {
            saved.saveData();
            return true;
        }
        if (!(sender instanceof Player player) || args.length < 1) return false;
        boolean cancel = emotes.fire(player, Map.of("emote", args[0], "times", args.length > 1 ? args[1] : "1"));
        player.sendMessage("fired " + args[0] + ", cancel=" + cancel);
        return true;
    }
}
