package xyz.xmit.StormWatch;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;


/**
 * Command executor for the primary StormWatch command <code>/stormgr</code>. This keeps all the
 * command and permissions processing out of the main plugin class.
 *
 * @see StormWatch
 */
// TODO: Rename to Parser instead of Executor, since it's doing multiple cmd-related tasks.
public class StormWatchCommandExecutor implements CommandExecutor, TabCompleter {
    private final HashMap<String, String> typesToClassPaths = new HashMap<>();
    private CommandSender whoSent = null;


    private void refreshStormTypes() {
        // Get a to-date (refreshed) list of all Storm type names.
        //   This is a map of the sub-type's TYPE_NAME --> Class-Name
        this.typesToClassPaths.clear();
        for(Class<? extends Storm> c : StormWatch.getStormManager().getRegisteredStormTypes()) {
            try {
                this.typesToClassPaths.put(
                    StormWatch.getStormManager().getRegisteredClassPathsToTypeNames().get(c.getName()),
                    c.getName()
                );
            } catch (Exception ex) { StormWatch.log(ex); }
        }
    }


    // TODO: Set up a TAB handler to help out with commands...
    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] strings) {
        return null;
    }


    /**
     * Primary parsing function used when the registered command (`/stormgr`) is used on the server.
     *
     * @see StormWatch
     * @see CommandExecutor
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Capture the command sender.
        this.whoSent = sender;
        // Check for empty/null params.
        if(args == null || args.length == 0) {
            // return some plugin info on an empty command
            sender.sendMessage(ChatColor.AQUA + """
            StormWatch, version\040""" + StormWatch.VERSION + ChatColor.GOLD + """
            \040\040by NotsoanoNimus <spigot-dev@xmit.xyz>""" + ChatColor.WHITE + """
            
            OPTIONS:
              CAST - Spawn and cast a Storm at a target player
              ex: /stormgr cast impact Billy
              TOGGLE - Attempt to toggle a type until the next reload
                (DOES NOT WORK CURRENTLY WITH DEFAULT TYPES)
                ex: /stormgr toggle customStorm disable
              CONFIGTOGGLE - Attempt to config-en/disable the type
                Works the same as the TOGGLE call
                This will also call the TOGGLE option automatically,
                  so reloads are not necessary
              QUERY - Queries information from the plugin.
                Run '/stormgr query' for more info.
              STATS - Gets plugin statistics.
                Run '/stormgr stats' for more info.
            """);
            return true;
        }

        // Most stormgr commands require at least three parameters (action storm value).
        // Here are some different permutations of that:
        //   /stormgr cast impact PlayerGuy [impact options]
        //   /stormgr toggle impact enabled

        // Some that may not, include "query" type commands for getting information from the plugin.
        var nonStandardCmds = new ArrayList<String>() {{
            add("query");
            add("stats");
        }};

        // Refresh the map of types -> class-paths.
        this.refreshStormTypes();
        // Get whether this is a "query" command or some other command that doesn't
        //   require the first 3 typical params: (action storm value)
        boolean followsPattern = !(nonStandardCmds.contains(args[0].toLowerCase(Locale.ROOT)));

        // Parse some of the parameters, if they follow the 3 param pattern.
        if(followsPattern) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Invalid parameters.");
                return false;
            } else if (!this.typesToClassPaths.containsKey(args[1])) {
                sender.sendMessage(ChatColor.RED + "Invalid Storm type: " + args[1]);
                return true;
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
                sender.sendMessage(ChatColor.RED + "That is not a currently-registered Storm type.");
                StormWatch.log(ex);
                return true;
            }

            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "cast":
                    var extraParams = args.length > 3 ? Arrays.copyOfRange(args, 3, args.length) : null;
                    return this.castCommand(newStorm, args[2], extraParams);
                case "toggle":
                    return this.toggleStorm(chosenStorm, args[1], args[2], true);
                case "configtoggle":
                    return this.configToggleStorm(chosenStorm, args[1], args[2]);
                default:
                    sender.sendMessage(ChatColor.RED + "Invalid parameter: " + args[0]);
                    return false;
            }
        } else {
            var passedParams = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : null;
            // Don't verify anything. Let the method calls do that.
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "query":
                    return this.queryCommand(passedParams);
                case "stats":
                    return this.statsCommand(passedParams);
                default:
                    sender.sendMessage(ChatColor.RED + "Invalid parameter: " + args[0]);
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
            this.whoSent.sendMessage(ChatColor.RED + "The target player '" + targetPlayerName + "' could not be found.");
            return true;
        }
        // After getting the valid target player: give the Storm the params array and start it.
        s.setCommandParameters(castParams);
        if(!s.isCancelled()) {
            s.startStorm(target);
            this.whoSent.sendMessage(ChatColor.GREEN + "Dispatched '" + s.getTypeName() +
                    "' Storm to player '" + targetPlayerName + "'.");
        } else {
            this.whoSent.sendMessage(ChatColor.RED + "The Storm event you tried to cast has been prematurely cancelled.");
        }
        return true;
    }


    // Toggles a storm's enabled flag; only persisting until the next reload.
    private boolean toggleStorm(Class<? extends Storm> storm, String stormName, String enOrDisable, boolean detail) {
        var mgr = StormWatch.getStormManager();
        boolean isEnable = true;
        switch (enOrDisable.toLowerCase(Locale.ROOT)) {
            case "enable" -> {}
            case "disable" -> isEnable = false;
            default -> {
                if(detail) {
                    this.whoSent.sendMessage(ChatColor.RED + "The parameter '" + enOrDisable
                            + "' is not valid. Must be 'enable' or 'disable'.");
                }
                return true;
            }
        }
        String action = isEnable ? "enable" : "disable";
        if(detail) {
            this.whoSent.sendMessage("Attempting to " + action + " Storm type: " + stormName);
        }
        // If the request is to enable and the Storm type doesn't exist, proceed. Opposite for disable requests.
        if((isEnable && !mgr.getRegisteredStormTypes().contains(storm)) ||
                (!isEnable && mgr.getRegisteredStormTypes().contains(storm))) {
            boolean successfulAction = isEnable ? mgr.registerNewStormType(storm) : mgr.unregisterStormType(storm);
            if(detail) {
                this.whoSent.sendMessage(
                        successfulAction
                                ? (ChatColor.GREEN + "Storm type " + action + "d successfully.")
                                : (ChatColor.RED   + "Failed to " + action + " the requested Storm type.")
                );
            }
        } else {
            // Issue an appropriate alternative response.
            String condition = isEnable ? "already" : "not";
            if(detail) {
                this.whoSent.sendMessage(ChatColor.RED + "Storm type '" + stormName +
                        "' is " + condition + " " + action + "d.");
            }
        }
        return true;
    }

    // Changes the 'enabled' value of a particular Storm in the configuration.
    private boolean configToggleStorm(Class<? extends Storm> storm, String stormName, String enOrDisable) {
        boolean setEnabled;
        switch(enOrDisable.toLowerCase(Locale.ROOT)) {
            case "enable" -> setEnabled = true;
            case "disable" -> setEnabled = false;
            default -> {
                this.whoSent.sendMessage(ChatColor.RED + "The parameter '" + enOrDisable
                        + "' is not valid. Must be 'enable' or 'disable'.");
                return true;
            }
        }
        boolean successfulChange = false;
        try {
            successfulChange = StormWatch.getStormConfig().setConfigValue(
                stormName, Storm.RequiredConfigurationKeyNames.ENABLED, setEnabled
            );
        } catch(Exception ex) { StormWatch.log(ex); }
        this.whoSent.sendMessage(
                (successfulChange ? ChatColor.GREEN : ChatColor.RED) +
                (successfulChange ? "Successfully" : "Failed to") + " config-" +
                (setEnabled ? "en" : "dis") + "able" + (successfulChange ? "d" : "") +
                " the '" + stormName + "' type."
        );
        // Also enable/disable it without needing a reload.
        try {
            this.toggleStorm(storm, stormName, enOrDisable, false);
        } catch(Exception ex) { StormWatch.log(ex); }
        return true;
    }


    // Runs query commands for getting data back from the plugin instance.
    private boolean queryCommand(String[] queryParams) {
        if(queryParams == null || queryParams.length == 0) {
            this.whoSent.sendMessage("""
                    QUERY - Gets plugin info. Subcommands:
                      CHANCES - Get the chance factors for all enabled types.
                      CLASSES - Returns a mapping of type names to class paths."""
            );
            return true;
        }
        switch(queryParams[0].toLowerCase(Locale.ROOT)) {
            case "chances" -> {
                // Returns the registered Storm type names with their chances.
                this.whoSent.sendMessage("Registered Storm chances:");
                var stormChances = StormWatch.getStormManager().getStormChances();
                var typeNameIndex = StormWatch.getStormManager().getRegisteredClassPathsToTypeNames();
                for(Class<?> s : stormChances.keySet()) {
                    this.whoSent.sendMessage("-- " + typeNameIndex.get(s.getName()) +
                            "   | Chance Factor: " + stormChances.get(s));
                }
            }
            case "classes" -> {
                // Returns all registered Storm type names to their class paths.
                this.whoSent.sendMessage("Registered Storm class paths:");
                var types = StormWatch.getStormManager().getRegisteredClassPathsToTypeNames();
                for(String s : types.keySet()) {
                    this.whoSent.sendMessage("-- " + types.get(s) + "   : " + s);
                }
            }
            default -> this.whoSent.sendMessage(ChatColor.RED + "Invalid query type: " + queryParams[0]);
        }
        return true;
    }


    // Statistics command.
    // TODO: Implement
    private boolean statsCommand(String[] params) {
        if(params == null || params.length == 0) {
            this.whoSent.sendMessage("STATS - Returns statistics. Subcommands:");
        }
        return true;
    }
}
