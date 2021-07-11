package xyz.xmit.StormWatch;

import net.minecraft.util.Tuple;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;
import xyz.xmit.StormWatch.storms.*;

import java.util.*;
import java.util.logging.Level;


/**
 * Single-instance class that maintains lists of current storms, registered storm types, their chances,
 * and also manages chunk-loading for the plugin. This is a critical component for cross-instance communication
 * that enables other storms to actively monitor enabled cooldowns and which worlds are occupied by a storm type
 * already.
 *
 * @see Storm
 */
public final class StormManager implements Listener {
    /**
     * Used explicitly for callback functions when a StormEndEvent is called. All Storms implement
     * this interface via inheritance from Storm.
     *
     * @see StormEndEvent
     */
    protected interface StormCallback { void doCleanupAfterStorm(); }
    /**
     * Defines "shipped" storm types that come with the plugin. To disable these, one should use the configuration
     * node of [<em>typename</em>].storm.enabled (set to <strong>false</strong>) instead of trying to manually
     * unregister them, or recompiling.
     */
    @SuppressWarnings("unchecked")
    // TODO: Perhaps move away from this variable. The "shipped" types were made well before the StormManager's
    //   ability to register Storm types on-demand came to fruition. Removing the special "shipped" status will
    //   allow the users to treat "shipped" types the same as other custom ones, because they basically are...
    //   On the contrary, I don't want these names to be overridden.
    public final static Class<? extends Storm>[] REGISTERED_STORMTYPES =
        (Class<? extends Storm>[]) new Class[] {
            StormImpact.class, StormShower.class, StormStreak.class
        };


    // Base objects used for managing storm states.
    private final Hashtable<Class<?>, Double> stormChances = new Hashtable<>();   //holds a list of storm occurrence chances.
    // TODO: Have a big think: can these be cleaned up?
    // TODO: Test TYPE_NAME and class-path collisions for registrations. Would be exceedingly valuable.
    private final ArrayList<Class<? extends Storm>> registeredStormTypes = new ArrayList<>();   //holds classes that test positively on instantiation
    private final HashMap<String, String> registeredClassPathsToTypeNames = new HashMap<>();   //class paths to TYPE_NAME fields
    // The below variable is used heavily for ID tracking and cooldown enablement.
    private final HashMap<UUID, Tuple<World, Class<? extends Storm>>> currentStormsMap = new HashMap<>();   //holds current UUIDs mapped to world and storm-type


    // Generic functions for managing storms.
    /**
     * Gets all configuration-set Storm extension chances that are registered with the StormManager instance.
     */
    @SuppressWarnings("unused")
    public final Hashtable<Class<?>, Double> getStormChances() { return this.stormChances; }
    /**
     * Gets a list of all registered Storm extensions that are placed into the plugin's rotation, if config-enabled.
     */
    public final ArrayList<Class<? extends Storm>> getRegisteredStormTypes() { return this.registeredStormTypes; }
    /**
     * Gets a list of registered Storm class names that are currently active.
     */
    public final HashMap<String, String> getRegisteredClassPathsToTypeNames() { return this.registeredClassPathsToTypeNames; }
    /**
     * Gets the entire map of currently-occurring Storm events. This is a mapping of a unique identifier to a data
     * tuple representing (a) the world in which the Storm event is occurring on the server, and (b) the extension
     * class of the Storm super-class.
     */
    @SuppressWarnings("unused")
    public final HashMap<UUID, Tuple<World, Class<? extends Storm>>> getCurrentStormsMap() { return this.currentStormsMap; }
    /**
     * Gets a Tuple containing a current Storm event's World and Storm extension class.
     *
     * @param uniqueId The UUID of the Storm class to query in the Map.
     */
    public final Tuple<World,Class<? extends Storm>> queryCurrentStormEvent(UUID uniqueId) {
        return this.currentStormsMap.get(uniqueId); }
    /**
     * Returns the chance for a new Storm of the parameterized type to occur in any World on the server.
     *
     * @param c The Storm extension class to poll.
     */
    public final double getStormChance(Class<? extends Storm> c) { return this.stormChances.get(c); }
    /**
     * Gets whether a Storm sub-class currently has an event ongoing in a certain World. This method is highly
     * important for checking cooldowns that are configuration-enabled.
     *
     * @param c A sub-class type of Storm to query.
     * @param w The World to query for an ongoing Storm event of type c.
     */
    public final boolean checkStormTypeAlreadyInProgress(Class<? extends Storm> c, World w) {
        for(var worldClass : this.currentStormsMap.values()) {
            if(worldClass.b().equals(c) && worldClass.a().equals(w)) {
                // The class and world were found paired together under some UUID, meaning a storm's in progress
                //   in this world for the given type.
                return true;
            }
        }
        return false;
    }


    // Construct a new storm manager object.
    public StormManager() {
        // TODO: Examine this with the above TODO about REGISTERED_STORMTYPES. This section could be possibly turned
        //   into one line that says "for each class in StormManager.SHIPPED_TYPES, run registerStormType(theType)"...
        // Test each requested type for validity on instantiation.
        HashMap<Class<?>, Object> baseClasses = new HashMap<>();
        for(Class<? extends Storm> c : StormManager.REGISTERED_STORMTYPES) {
            try {
                // Creating each object on first run SHOULD also instantiate each valid one's configuration.
                var inst = c.cast( c.getDeclaredConstructor().newInstance() );
                baseClasses.put(  c,  inst  );
                this.registeredStormTypes.add(c);
                this.registeredClassPathsToTypeNames.put(c.getName(), inst.getTypeName());
            } catch (Exception e) {
                StormWatch.log(e, "~ Problem registering storm type: " + c.getName() + " --- DISABLING TYPE");
            }
        }

        // Attempt to get each storm's chance from the LEGITIMATE (registered) types.
        //   Chances are used in the Event handler for plugin ticks, to calculate if a given storm type should start.
        for(Object o : baseClasses.values()) {
            try {
                Storm stormObj = (Storm)o;
                if(stormObj.getEnabled() && !stormObj.isCancelled()) {
                    this.stormChances.put(o.getClass(), stormObj.getStormChance());
                    StormWatch.log(false,
                            "~ STORM TYPE [" + stormObj.getName() + "]: ENABLED; spawn chance of " + stormObj.getStormChance());
                } else {
                    this.stormChances.put(o.getClass(), 0.00);
                    StormWatch.log(false,
                            "~ STORM TYPE [" + stormObj.getName() + "]: NOT ENABLED.");
                    if(!stormObj.getEnabled()) {
                        StormWatch.log(false,
                                "~~~ Type is disabled in the configuration.");
                    } else if(stormObj.isCancelled()) {
                        StormWatch.log(false,
                                "~~~ The type was marked enabled, but was CANCELLED. This likely indicates a configuration problem.");
                    }
                }
            } catch (Exception e) {
                StormWatch.log(false, Level.WARNING,
                        "~ Could not get the chance field for class: " + o.getClass().getName());
                StormWatch.log(e);
            }
        }
    }


    /**
     * Very important managerial function that can be used by external plugins and extensions of the Storm class
     * to register their own Storm types and implementations. This is demonstrated in the sample
     * <a href="https://github.com/NotsoanoNimus/Darude-Sandstorm-Test" target="_blank">Darude (Sandstorm) Plugin</a>,
     * which is an entirely external Spigot/Bukkit plugin that sources this function.
     *
     * @param c External storm-type (sub-class of Storm) to register with the plugin.
     * @return Whether or not the storm type was successfully registered into the manager's index.
     */
    @SuppressWarnings("unused")
    public final boolean registerNewStormType(Class<? extends Storm> c) {
        // Prevent duplicate Storm type registrations. This is very important since TYPE_NAME is what
        //   the config.yml write operations use as the root config key. So with the following code,
        //   bad actors are IDEALLY not allowed to overwrite other configurations.
        // NOTE: This is NOT uniqueness on TYPE_NAME, but on a class path. This was an important distinction
        //       I ended up fixing below after instantiation.
        for(Object o : this.registeredStormTypes) {
            if(c.getName().toLowerCase(Locale.ROOT).equals(o.getClass().getName().toLowerCase(Locale.ROOT))) {
                StormWatch.log(false, Level.WARNING,
                    "~ A storm type with the class path '" + c.getName() + "' is already registered.");
                return false;
            }
        }
        try {
            // Create a new Storm instance for the class.
            Storm z = c.getDeclaredConstructor().newInstance();
            // Make sure the TYPE_NAME property is a unique value -- very important.
            if(this.registeredClassPathsToTypeNames.containsValue(z.getTypeName().toLowerCase(Locale.ROOT))) {
                StormWatch.log(false, Level.WARNING, "~ A storm with type_name '"
                        + z.getTypeName().toLowerCase(Locale.ROOT) + "' already exists. CANCELLED registration.");
                return false;
            }
            // Provided it casts properly, it's a valid type. Register it.
            this.registeredStormTypes.add(c);
            this.registeredClassPathsToTypeNames.put(c.getName(), z.getTypeName().toLowerCase(Locale.ROOT));
            this.stormChances.put(c, z.getStormChance());
            StormWatch.log(false,
                    "~ STORM TYPE [" + z.getName() + "] ENABLED; spawn chance of: " + z.getStormChance());
        } catch(Exception e) {
            StormWatch.log(false, Level.WARNING,
                    "~ Problem registering storm class '" + c.getName() + "' --- DISABLED this type!");
            return false;
        }
        return true;
    }

    /**
     * Used to request de-registration to completely remove a certain storm type from the StormManager.
     * This function <em>cannot</em> be used to remove shipped storm types, so if a user does not like a
     * certain built-in, they should instead <strong>disable it from the configuration file</strong>.
     *
     * @param stormType The Storm sub-class to attempt to unregister.
     * @return Whether or not the storm type was successfully unregistered from the manager.
     */
    @SuppressWarnings("unused")
    public final boolean unregisterStormType(Class<? extends Storm> stormType) {
        if(this.registeredStormTypes.contains(stormType)) {
            try {
                if (!Arrays.asList(StormManager.REGISTERED_STORMTYPES).contains(stormType)) {
                    this.registeredStormTypes.remove(stormType);
                    this.registeredClassPathsToTypeNames.remove(stormType.getName());
                    StormWatch.log(false,
                            "~ Storm extension temporary disabled by un-registration until the next reload: " + stormType.getName());
                    return true;
                } else {
                    // One cannot toggle/unregister a built-in Storm type
                    StormWatch.log(false,
                            "~ Built-in Storm types cannot be unregistered from the plugin. They should be config-disabled instead.");
                    return false;
                }
            } catch (Exception ex) {
                StormWatch.log(false, "~ Failed to unregister Storm type: " + stormType.getName());
                StormWatch.log(ex);
            }
        }
        return false;
    }



    /**
     * Registers a newly-spawned storm instance as a valid storm, which will remain until the
     * corresponding StormEndEvent is fired.
     *
     * @param startEvent The event thrown by the plugin to this handler.
     * @see StormStartEvent
     * @see StormEndEvent
     */
    @SuppressWarnings("unused")
    @EventHandler
    // Registers a new storm's UUID.
    public final void newStorm(StormStartEvent startEvent) {
        var newWorld = startEvent.getWorld();
        Storm t = startEvent.getInstance();
        if(!t.isCalledByCommand()) {
            if (this.queryCurrentStormEvent(t.getStormId()) == null) {
                this.currentStormsMap.put(t.getStormId(), new Tuple<>(newWorld, t.getClass()));
            } else {
                StormWatch.log(false, Level.WARNING,
                        "~ Duplicate event registration attempt detected. ID: " + t.getStormId() + " /// World: "
                                + newWorld.getName() + " /// Storm subclass: " + t.getClass().getName());
            }
        }
    }

    /**
     * Handles a StormEndEvent to either (a) remove the Storm instance ID from the tracked storms
     * list, or (b) engage a cooldown on the storm type for that world (if enabled). If the event was
     * triggered by a Storm that was spawned by command, then most of this method does not apply except
     * chunk-loading and chunk persistence as a result of the ending Storm.
     *
     * @param endEvent The event thrown by the plugin to this handler.
     * @see StormEndEvent
     * @see StormStartEvent
     */
    @SuppressWarnings("unused")
    @EventHandler
    // Destroys a tracked storm and wraps it up.
    public final void completeStorm(StormEndEvent endEvent) {
        // When a storm's UUID completes, refresh the object in the stormsPerWorld
        //   table to generate new Storm parameters for that type.
        Storm x = endEvent.getInstance();
        UUID stormId = endEvent.getStormId();
        // Make sure this is a valid event that was being tracked. If the storm was spawned by command,
        //   then verifying the UUID is not necessary because manual storms aren't tracked.
        if(this.queryCurrentStormEvent(stormId) == null && !x.isCalledByCommand()) {
            StormWatch.log(false, Level.WARNING,
                "~ StormEndEvent captured without a valid ID: " + stormId.toString());
        }

        // Schedule a task with the configured delay to unload the Storm's chunks, only if they're not persistent.
        if(x.isLoadsChunks() && !x.isLoadedChunksPersistent()) {
            var t = new BukkitRunnable() {
                public void run() { StormWatch.getStormChunkManager().unloadStormChunks(stormId); }
            }.runTaskLater(StormWatch.instance, endEvent.getInstance().getChunkLoadingUnloadDelay() * 20L);
        }

        // If the storm type has a cooldown enabled and is "organic" (not command-spawned), get the range and create a cooldown task.
        if(x.isCooldownEnabled() && !x.isCalledByCommand()) {
            // Parse information for this ID from the registered events.
            Tuple<World, Class<? extends Storm>> worldClass = this.queryCurrentStormEvent(stormId);
            if(worldClass == null) {
                StormWatch.log(false, Level.WARNING,
                        "~ Storm was registered but didn't have any associations: " + stormId);
                return;
            }
            World stormWorld = worldClass.a();
            Class<? extends Storm> stormClass = worldClass.b();
            int cooldown = x.getInstanceCooldown();
            var t = new BukkitRunnable() {
                public void run() {
                    currentStormsMap.remove(stormId);
                    StormWatch.log(true, "~~~ Cooldown complete for storm with ID: " + stormId
                        + " -- Type,World: " + stormClass.getName() + "," + stormWorld);
                }
            }.runTaskLater(StormWatch.instance, cooldown * 20L);
            StormWatch.log(true,
                    "~ Scheduling removal of storm ID " + stormId + " after cooldown of " + cooldown + " seconds.");
        } else if(!x.isCooldownEnabled() && !x.isCalledByCommand()) {
            // Otherwise, if no cooldown, remove the entry from the Storms tracker right now.
            this.currentStormsMap.remove(stormId);
            StormWatch.log(true, "~ Removed storm with ID: " + stormId + "  (no cooldown locking)");
        }

    }

    /**
     * Handles StormWatch tick events that occur at a regular interval based on a scheduled Bukkit task object.
     * This function is <strong><em>CRITICAL</em></strong> to the management of Storm instance spawning, as without
     * it no storm events would occur at all. Targets a player per world, iterates all StormManager registered Storm
     * types, detects their chance-to-spawn factor, determines if said Storm is on-cooldown in the plugin StormManager
     * instance, and finally (if instantiation goes right) will attempt to start the Storm event.
     *
     * @param e The event thrown by the plugin's main instance, at the interval defined by its TickRate property.
     * @see StormTickEvent
     * @see StormWatch
     */
    @SuppressWarnings("unused")
    @EventHandler
    // Run every time a storm TICK event is raised. Checks for a storm start event.
    public final void onStormTick(StormTickEvent e) {
        // If no players are online, all Tick events are invalidated.
        if(StormWatch.instance.getServer().getOnlinePlayers().size() < 1) { return; }
        // Iterate through each world. A storm of ONE TYPE PER EACH WORLD is allowed to spawn on a single "tick" event.
        //   What this means is all three built-in Storm extension classes will NEVER all spawn at the same time in
        //   one world/dimension on a single tick event.
        for(World w : StormWatch.instance.getServer().getWorlds()) {
            // Skip the world if there are no players in it, or if the world is globally exempt from Storm events.
            if(w.getPlayers().size() < 1 || StormWatch.getInstance().isExemptWorld(w.getName())) { continue; }
            boolean stormStartedThisIterationInWorld = false;
            // Iterate in each world's dynamic Storm objects collection.
            for(Class<? extends Storm> c : this.getRegisteredStormTypes()) {
                // Done first so STORM objects aren't created constantly and wearing down the server.
                if((Math.random() + 0.0001) <= this.getStormChance(c)) {
                    Storm storm;
                    try {
                        storm = c.cast(c.getDeclaredConstructor().newInstance());
                    } catch(Exception ex) {
                        // Here just-in-case, but honestly shouldn't happen if there's no mischief about.
                        StormWatch.log(false, Level.WARNING,
                                "~ Tried to instantiate a registered Storm Type, but failed casting.\n"
                                + "Please make sure all registered Storm types extend Storm properly.");
                        StormWatch.log(ex);
                        continue;
                    }
                    // Make sure that a storm hasn't already started in the target world on this Tick event.
                    //   Ensure that the world name is not exempted from this particular Storm type.
                    //   Also, make sure the storm of the given type is not on cooldown, if it has world-locking (cooldowns-per-world) enabled.
                    // ----- IMPORTANT: All other checks are done WITHIN the Storm base class once a Player object is fed to it.
                    //                   This step ends up getting saved and run first-thing in startStorm to prevent the
                    //                   server instantiating a new Storm[Type] object if it's just going to fail or be on cooldown anyhow.
                    if (!stormStartedThisIterationInWorld && storm.getEnabled() && !storm.isWorldNameExempt(w.getName())
                            && !(storm.isCooldownEnabled() && this.checkStormTypeAlreadyInProgress(storm.getClass(), w))) {
                        // Pick a random player inside the current target world and start the storm.
                        //   Each storm is assigned a unique identifier.
                        var rng = new Random();
                        // Try 3 times to get a non-exempt target player for the Storm event. Failure to do so skips the event entirely.
                        int triesToGetNonExemptPlayer = 0;
                        Player selectedPlayer = null;
                        while(triesToGetNonExemptPlayer < 3) {
                            selectedPlayer = w.getPlayers().get(  rng.nextInt( w.getPlayers().size() ));
                            if(!StormWatch.getInstance().isExemptPlayer(selectedPlayer.getName())
                                    && !storm.isPlayerNameExempt(selectedPlayer.getName())) {
                                break;   // Break out and proceed so long as the player is not exempt in either scope
                            }
                            triesToGetNonExemptPlayer++;
                        }
                        if(triesToGetNonExemptPlayer >= 3) {
                            StormWatch.log(true,
                                    "~~~ Failed to find a non-globally-exempt target for Storm ID " + storm.getStormId());
                            continue;
                        }
                        // Start the Storm with the target Player.
                        storm.startStorm(selectedPlayer);
                        if(storm.isCancelled()) {
                            // Typically occurs if there was a failure on instantiation, but such a failure could be the intent of the
                            //   designer of the Storm extension class (such as bad environment, or other conditions).
                            StormWatch.log(true,
                                    "~~~ Storm ID " + storm.getStormId() + " was cancelled prematurely.");
                        }
                        stormStartedThisIterationInWorld = !storm.isCancelled();   //as long as the storm instantiated/started, this will be true
                    }
                }
            }
        }

    }

}
