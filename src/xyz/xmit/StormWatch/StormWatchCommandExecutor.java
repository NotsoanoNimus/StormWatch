package xyz.xmit.StormWatch;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;

/**
 * Command executor for the primary StormWatch command <code>/stormgr</code>. This keeps all the
 * command and permissions processing out of the main plugin class.
 *
 * @see StormWatch
 */
public class StormWatchCommandExecutor implements CommandExecutor {
    private final HashMap<String, String> typesToClassPaths = new HashMap<>();
    private CommandSender whoSent = null;


    // Generic constructor.
    public StormWatchCommandExecutor() { }


    private String[] shiftStringArray(String[] params) {
        String[] x = Arrays.copyOfRange(params, 1, params.length - 1);
        return x;
    }

    private void refreshStormTypes() {
        // Get a to-date (refreshed) list of all Storm type names.
        //   This is a map of the sub-type's TYPE_NAME --> Class-Name
        this.typesToClassPaths.clear();
        for(Class<? extends Storm> c : StormWatch.getStormManager().getRegisteredStormTypes()) {
            try {
                this.typesToClassPaths.put(
                        ((Storm)c.getDeclaredConstructor().newInstance()).getName(), c.getName()
                );
            } catch (Exception ex) { StormWatch.log(ex); }
        }
    }


    /**
     * Primary parsing function used when the registered command (`/stormgr`) is used on the server.
     *
     * @see StormWatch
     * @see CommandExecutor
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // All stormgr commands require at least four parameters. Here are some different permutations:
        //   /stormgr cast impact PlayerGuy [impact options]
        //   /stormgr toggle impact enabled
        String senderName = (sender instanceof Player) ? ((Player)sender).getDisplayName() : sender.getName();
        System.out.println("SENDER: " + senderName + "\nCOMMAND: " + command
                + "\nLABEL: " + label + "\nARGS: " + String.join(", ", args));
        this.whoSent = sender;
        // Firstly, refresh the map of types -> class-paths.
        this.refreshStormTypes();
        if(args.length < 3) {
            sender.sendMessage("Invalid parameters.");
            return false;
        } else if(!this.typesToClassPaths.containsKey(args[1])) {
            sender.sendMessage("Invalid Storm type: " + args[1]);
            return false;
        }

        Class<? extends Storm> chosenStorm;
        Storm newStorm;
        try {
            chosenStorm = (Class<? extends Storm>)Class.forName(  this.typesToClassPaths.get( args[1] )  );
            if(!StormWatch.getStormManager().getRegisteredStormTypes().contains(chosenStorm)) {
                throw new Exception("Not an enabled Storm type!");
            }
            newStorm = (Storm)chosenStorm.cast(chosenStorm.getDeclaredConstructor().newInstance());
            newStorm.setIsCalledByCommand();
        } catch (Exception ex) {
            ((Player)sender).sendMessage("That is not a currently-registered Storm type.");
            StormWatch.log(ex);
            return false;
        }

        switch(args[0].toLowerCase(Locale.ROOT)) {
            case "cast":
                return this.castCommand(chosenStorm, newStorm,
                        this.shiftStringArray(this.shiftStringArray(args)));
            case "toggle":
                return this.toggleStorm(chosenStorm, args[2]);
            default:
                sender.sendMessage("Invalid parameter: " + args[0]);
                return false;
        }
    }


    private boolean castCommand(Class<? extends Storm> storm, Storm s, String[] castParams) {
        String tgtPlayer = castParams[0];
        Player target;
        try {
            target = StormWatch.getInstance().getServer().getPlayer(tgtPlayer);
            if(target == null) { throw new Exception("Target player does not exist."); }
        } catch (Exception ex) {
            StormWatch.log(ex);
            this.whoSent.sendMessage("The target player '" + tgtPlayer + "' could not be found.");
            return false;
        }
        // After getting the valid target player: give the Storm the params array and start it.
        s.setPropertiesFromCommand(castParams);
        if(!s.isCancelled()) { s.startStorm(target); }
        else { this.whoSent.sendMessage("The Storm event you tried to raise has been prematurely cancelled."); }
        return true;
    }

    private boolean toggleStorm(Class<? extends Storm> storm, String enOrDisable) {
        var mgr = StormWatch.getStormManager();
        switch(enOrDisable) {
            case "enable":
                if(!mgr.getRegisteredStormTypes().contains(storm)) {
                    mgr.registerNewStormType(storm);
                    this.whoSent.sendMessage("Attempted to load and enabled Storm type: " + storm.getName());
                } else {
                    this.whoSent.sendMessage("That Storm type is already enabled!");
                }
                return true;
            case "disable":
                if(mgr.getRegisteredStormTypes().contains(storm)) {
                    mgr.unregisterStormType(storm);
                    this.whoSent.sendMessage("Un-registered and disabled Storm type: " + storm.getName());
                } else {
                    this.whoSent.sendMessage("That Storm type is already disabled!");
                }
                return true;
            default:
                break;
        }
        this.whoSent.sendMessage("The provided parameter '" + enOrDisable
            + "' is invalid. Must be 'enable' or 'disable'.");
        return false;
    }
}
