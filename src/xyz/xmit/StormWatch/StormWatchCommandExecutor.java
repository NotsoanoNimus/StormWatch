package xyz.xmit.StormWatch;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;

import static org.bukkit.craftbukkit.libs.org.apache.http.client.methods.RequestBuilder.put;

/**
 * Command executor for the primary StormWatch command <code>/stormgr</code>. This keeps all the
 * command and permissions processing out of the main plugin class.
 *
 * @see StormWatch
 */
public class StormWatchCommandExecutor implements CommandExecutor {
    private final HashMap<String, String> typesToClassPaths = new HashMap<>();
    private CommandSender whoSent = null;


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
        // Most stormgr commands require at least three parameters (action storm value).
        // Here are some different permutations of that:
        //   /stormgr cast impact PlayerGuy [impact options]
        //   /stormgr toggle impact enabled
        // Some that may not include "query" type commands for getting information from the plugin.
        var nonStandardCmds = new ArrayList<String>() {{
            put("query");
        }};

        // Capture the command sender.
        this.whoSent = sender;
        // Firstly, refresh the map of types -> class-paths.
        this.refreshStormTypes();
        // Get whether this is a "query" command or some other command that doesn't
        //   require the first 3 typical params: (action storm value)
        boolean followsPattern = !(nonStandardCmds.contains(args[0].toLowerCase(Locale.ROOT)));

        // Parse some of the parameters, if they follow the 3 param pattern.
        if(followsPattern) {
            if (args.length < 3) {
                sender.sendMessage("Invalid parameters.");
                return false;
            } else if (!this.typesToClassPaths.containsKey(args[1])) {
                sender.sendMessage("Invalid Storm type: " + args[1]);
                return false;
            }

            // Validate that the selected storm type is valid, and instantiate a Storm object corresponding to it.
            Class<? extends Storm> chosenStorm;
            Storm newStorm;
            try {
                chosenStorm = (Class<? extends Storm>) Class.forName(this.typesToClassPaths.get(args[1]));
                if (!StormWatch.getStormManager().getRegisteredStormTypes().contains(chosenStorm)) {
                    throw new Exception(chosenStorm.toString() + ", is not a registered Storm type!");
                }
                newStorm = (Storm) chosenStorm.cast(chosenStorm.getDeclaredConstructor().newInstance());
                newStorm.setIsCalledByCommand();
            } catch (Exception ex) {
                sender.sendMessage("That is not a currently-registered Storm type.");
                StormWatch.log(ex);
                return false;
            }

            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "cast":
                    return this.castCommand(newStorm, args[2], Arrays.copyOfRange(args, 3, args.length - 1));
                case "toggle":
                    return this.toggleStorm(chosenStorm, args[1], args[2]);
                case "configtoggle":
                    return this.configToggleStorm(chosenStorm, args[1], args[2]);
                default:
                    sender.sendMessage("Invalid parameter: " + args[0]);
                    return false;
            }
        } else {
            // Don't verify anything. Let the method calls do that.
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "query":
                    return this.queryCommand(Arrays.copyOfRange(args, 1, args.length - 1));
                default:
                    sender.sendMessage("Invalid parameter: " + args[0]);
                    return false;
            }
        }
    }


    private boolean castCommand(Storm s, String targetPlayerName, String[] castParams) {
        Player target;
        try {
            target = StormWatch.getInstance().getServer().getPlayer(targetPlayerName);
            if(target == null) { throw new Exception("Target player does not exist."); }
        } catch (Exception ex) {
            StormWatch.log(ex);
            this.whoSent.sendMessage("The target player '" + targetPlayerName + "' could not be found.");
            return false;
        }
        // After getting the valid target player: give the Storm the params array and start it.
        s.setCommandParameters(castParams);
        if(!s.isCancelled()) {
            s.startStorm(target);
            this.whoSent.sendMessage(ChatColor.GREEN + "Dispatched Storm to player '" + targetPlayerName + "'.");
        } else {
            this.whoSent.sendMessage(ChatColor.RED + "The Storm event you tried to cast has been prematurely cancelled.");
        }
        return true;
    }


    // TODO: This mess can probably be condensed like its shinier cousin below.
    private boolean toggleStorm(Class<? extends Storm> storm, String stormName, String enOrDisable) {
        var mgr = StormWatch.getStormManager();
        switch (enOrDisable.toLowerCase(Locale.ROOT)) {
            case "enable" -> {
                this.whoSent.sendMessage("Attempting to register Storm type: " + storm.getName());
                if(!mgr.getRegisteredStormTypes().contains(storm)) {
                    if(!mgr.registerNewStormType(storm)) {
                        this.whoSent.sendMessage("Failed to register the requested Storm type.");
                        return false;
                    } else {
                        this.whoSent.sendMessage("Storm type registered successfully.");
                        return true;
                    }
                } else {
                    this.whoSent.sendMessage("Storm type '" + storm.getName() + "' is already registered.");
                    return false;
                }
            }
            case "disable" -> {
                this.whoSent.sendMessage("Attempting to unregister Storm type: " + storm.getName());
                if(mgr.getRegisteredStormTypes().contains(storm)) {
                    if(!mgr.unregisterStormType(storm)) {
                        this.whoSent.sendMessage("Failed to unregister that Storm type!");
                        return false;
                    } else {
                        this.whoSent.sendMessage("Storm type unregistered successfully.");
                        return true;
                    }
                } else {
                    // I don't _think_ this should ever really happen, but ehh.
                    this.whoSent.sendMessage("Storm type '" + storm.getName() + "' is not registered.");
                    return false;
                }
            }
            default -> {
                this.whoSent.sendMessage("The parameter '" + enOrDisable
                        + "' is not valid. Must be 'enable' or 'disable'.");
                return false;
            }
        }
    }

    private boolean configToggleStorm(Class<? extends Storm> storm, String stormName, String enOrDisable) {
        boolean setEnabled;
        switch(enOrDisable.toLowerCase(Locale.ROOT)) {
            case "enable" -> { setEnabled = true; }
            case "disable" -> { setEnabled = false; }
            default -> {
                this.whoSent.sendMessage("The parameter '" + enOrDisable
                        + "' is not valid. Must be 'enable' or 'disable'.");
                return false;
            }
        }
        boolean successfulChange = false;
        try {
            successfulChange = StormWatch.getStormConfig().setConfigValue(
                stormName, Storm.RequiredConfigurationKeyNames.ENABLED, setEnabled
            );
        } catch(Exception ex) { StormWatch.log(ex); }
        this.whoSent.sendMessage((successfulChange ? "Successfully" : "Failed to") + " config-" +
                (setEnabled ? "en" : "dis") + "able the '" + stormName + "' type.");
        return successfulChange;
    }


    // TODO: Implement
    private boolean queryCommand(String[] queryParams) {
        return true;
    }
}
