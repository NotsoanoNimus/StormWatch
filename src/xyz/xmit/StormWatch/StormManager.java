package xyz.xmit.StormWatch;

import net.minecraft.server.v1_16_R3.Tuple;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;
import xyz.xmit.StormWatch.storms.*;

import java.lang.reflect.Array;
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
    public final static Class<? extends Storm>[] REGISTERED_STORMTYPES =
        (Class<? extends Storm>[]) new Class[] {
            StormImpact.class, StormShower.class, StormStreak.class
        };


    // Base objects used for managing storm states.
    private final Hashtable<Class<?>, Double> stormChances = new Hashtable<>();   //holds a list of storm occurrence chances.
    private final ArrayList<Class<? extends Storm>> registeredStormTypes = new ArrayList<>();   //holds classes that test positively on instantiation
    // The below variable is used heavily for ID tracking and cooldown enablement.
    private final HashMap<UUID, Tuple<World, Class<? extends Storm>>> currentStormsMap = new HashMap<>();   //holds current UUIDs mapped to world and storm-type
    // This variable is used for chunk ticketing and management. See the chunk manager further down this class.
    ////private final HashMap<UUID, ArrayList<Chunk>> currentStormChunkTickets = new HashMap<>();


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
        // Test each requested type for validity on instantiation.
        HashMap<Class<?>, Object> baseClasses = new HashMap<>();
        for(Class<? extends Storm> c : StormManager.REGISTERED_STORMTYPES) {
            try {
                // Creating each object on first run SHOULD also instantiate each valid one's configuration.
                baseClasses.put(  c,  c.cast( c.getDeclaredConstructor().newInstance() )  );
                this.registeredStormTypes.add(c);
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
                    StormWatch.log(false, "~ STORM TYPE [" + stormObj.getName() + "]: ENABLED. Got chance of " + stormObj.getStormChance());
                } else {
                    this.stormChances.put(o.getClass(), 0.00);
                    StormWatch.log(false, "~ STORM TYPE [" + stormObj.getName() + "]: DISABLED.");
                    if(!stormObj.getEnabled()) {
                        StormWatch.log(false, "~~~ Type is disabled in the configuration.");
                    }
                    if(stormObj.isCancelled()) {
                        StormWatch.log(false, "~~~ The type was CANCELLED. This likely indicates a configuration problem.");
                    }
                }
            } catch (Exception e) {
                StormWatch.log(false, Level.WARNING, "~ Could not get the chance field for class: " + o.getClass().getName());
                StormWatch.log(e);
            }
        }

        // Make sure all typeName fields in each Storm type are UNIQUE!
        ArrayList<String> typeNames = new ArrayList<>();
        for(Object o : baseClasses.values()) {
            try {
                String objName = ((Storm)o).getName();
                if (typeNames.contains(objName)) {
                    throw new Exception("Storm typeName '" + objName + "' is already defined!");
                } else { typeNames.add(objName); }
            } catch (Exception ex) {
                StormWatch.log(ex);
            }
        }
    }


    /**
     * Very important managerial function that can be used by external plugins and extensions of the Storm class
     * to register their own Storm types and implementations. This is demonstrated in the sample
     * <a href="https://github.com/NotsoanoNimus/darude-plugin.git" target="_blank">Darude (Sandstorm) Plugin</a>,
     * which is an entirely external Spigot/Bukkit plugin that sources this function.
     *
     * @param c External storm-type (sub-class of Storm) to register with the plugin.
     * @return Whether or not the storm type was successfully registered into the manager's index.
     */
    @SuppressWarnings("unused")
    public final boolean registerNewStormType(Class<? extends Storm> c) {
        for(Object o : this.registeredStormTypes) {
            if(c.getName().toLowerCase(Locale.ROOT).equals(o.getClass().getName().toLowerCase(Locale.ROOT))) {
                StormWatch.log(false, Level.WARNING,
                    "~ A storm type with the name " + c.getName() + " is already registered.");
                return false;
            }
        }
        try {
            //Object x = c.cast( c.getDeclaredConstructor().newInstance() );
            Storm z = c.getDeclaredConstructor().newInstance();
            // Provided it casts properly, it's a valid type. Register it.
            this.registeredStormTypes.add(c);
            this.stormChances.put(c, z.getStormChance());
            StormWatch.log(false, "~ STORM TYPE [" + z.getName() + "] ENABLED. Got chance of: " + z.getStormChance());
        } catch(Exception e) {
            StormWatch.log(false, Level.WARNING, "~ Problem registering storm type " + c.getName() + " --- DISABLED this type!");
            return false;
        }
        return true;
    }

    /**
     * Used to request de-registration to completely remove a certain storm type from the StormManager.
     * This function <em>cannot</em> be used to remove shipped storm types, so if a user does not like a
     * certain plugin, the should instead <strong>disable it from the configuration file</strong>.
     *
     * @param stormTypeName The Storm sub-class to attempt to de-register.
     * @return Whether or not the storm type was successfully unregistered from the manager.
     */
    @SuppressWarnings("unused")
    public final boolean unregisterStormType(Class<? extends Storm> stormTypeName) {
        ArrayList<Class<? extends  Storm>> listOfStormTypes = new ArrayList<>(this.registeredStormTypes);
        // Use the temporary clone to avoid a ConcurrentModificationException
        for(Class<? extends Storm> c : listOfStormTypes) {
            try {
                if (c.equals(stormTypeName)) {
                    if(!Arrays.asList(REGISTERED_STORMTYPES).contains(stormTypeName)) {
                        this.registeredStormTypes.remove(c);
                        StormWatch.log(false,
                            "~ STORM EXTENSION DISABLED BY DE-REGISTRATION: " + c.getName());
                    }
                }
            } catch (Exception ex) {
                StormWatch.log(false, "~ Failed to unregister storm type: " + stormTypeName);
                StormWatch.log(ex);
            }
        }
        return true;
    }


    //// Chunk management section.
    /*  AWAITING IMPLEMENTATION  */


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
        if(this.queryCurrentStormEvent(t.getStormId()) == null) {
            this.currentStormsMap.put(t.getStormId(), new Tuple<>(newWorld, t.getClass()));
        } else {
            StormWatch.log(false, Level.WARNING,
            "~ A duplicate registration attempt was detected for ID: " + t.getStormId());
        }
    }

    /**
     * Handles a StormEndEvent to either (a) remove the Storm instance ID from the tracked storms
     * list, or (b) engage a cooldown on the storm type for that world (if enabled).
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
        UUID stormId = endEvent.getStormId();
        // Make sure this is a valid event that was being tracked.
        if(this.queryCurrentStormEvent(stormId) == null) {
            StormWatch.log(false, Level.WARNING,
                "~ StormEndEvent captured without a valid UUID: " + stormId.toString());
        }
        // Parse information for this ID from the registered events.
        Tuple<World, Class<? extends Storm>> worldClass = this.queryCurrentStormEvent(stormId);
        if(worldClass == null) {
            StormWatch.log(false, Level.WARNING,
                "~ Storm was registered but didn't have any associations: " + stormId);
            return;
        }
        World stormWorld = worldClass.a();
        Class<? extends Storm> stormClass = worldClass.b();

        // If the storm type has a cooldown enabled, get the range and create a cooldown task.
        Storm x = endEvent.getInstance();
        if(x.isCooldownEnabled()) {
            int cooldown = x.getInstanceCooldown(); //x.getSomeCooldown();
            new BukkitRunnable() {
                public void run() {
                    currentStormsMap.remove(stormId);
                    StormWatch.log(true, "~~~ Cooldown complete for storm with ID: " + stormId
                        + " -- Type,World: " + stormClass.getName() + "," + stormWorld);
                }
            }.runTaskLater(StormWatch.instance, cooldown * 20L);
            StormWatch.log(true, "~ Scheduling removal of storm ID " + stormId + " after cooldown of " + cooldown + " seconds.");
        } else {
            // Otherwise, remove the entry from the tracker right now.
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
        // If no players are online, all events are invalidated (even cooldown decrements).
        if(StormWatch.instance.getServer().getOnlinePlayers().size() < 1) { return; }
        // Iterate through each world. A storm of ONE TYPE is allowed per-world to spawn on a single "tick" event.
        for(World w : StormWatch.instance.getServer().getWorlds()) {
            if(w.getPlayers().size() < 1) { continue; }   //skip the world if no players are in it
            boolean stormStartedThisIterationInWorld = false;
            // Iterate in each world's dynamic Storm objects collection.
            for(Class<? extends Storm> c : this.getRegisteredStormTypes()) {
                // Done first so STORM objects aren't created constantly and wearing down the server.
                //StormWatch.log("Testing against chance: " + this.mgr.getStormChance(c));
                if((Math.random() + 0.0001) <= this.getStormChance(c)) {
                    //StormWatch.log("Passed chance, getting to storm instantiation");
                    Storm storm;
                    try {
                        storm = (Storm)c.cast(c.getDeclaredConstructor().newInstance());
                    } catch(Exception ex) {
                        StormWatch.log(false, Level.WARNING,
                                "~ Tried to instantiate a registered Storm Type, but failed casting.\n"
                                + "Please make sure all classes in StormTypes.REGISTERED_STORMTYPES extend Storm properly.");
                        StormWatch.log(ex);
                        continue;
                    }
                    // Make sure that a storm hasn't already started in the target world on this Tick event.
                    //   Also, make sure the storm of the given type is not on cooldown, if it has world-locking (cooldowns-per-world) enabled.
                    // ----- IMPORTANT: All other checks are done WITHIN the storm class once a Player object is fed to it.
                    //                   This step ends up getting saved and run first-thing in startStorm to prevent the
                    //                   server instantiating a new Storm[Type] object if it's just going to fail or be on cooldown anyhow.
                    if (!stormStartedThisIterationInWorld && storm.getEnabled()
                            && !(storm.isCooldownEnabled() && this.checkStormTypeAlreadyInProgress(storm.getClass(), w))) {
                        // Pick a random player inside the current target world and start the storm.
                        //   Each storm is assigned a unique identifier.
                        var rng = new Random();
                        Player p = w.getPlayers().get(rng.nextInt(w.getPlayers().size()));   //random player selection
                        storm.startStorm(p);   //give it to the storm with the player
                        if(storm.isCancelled()) {
                            StormWatch.log(true, "~~~ Storm ID " + storm.getStormId() + " was cancelled prematurely.");
                        }
                        stormStartedThisIterationInWorld = !storm.isCancelled();   //as long as the storm instantiated/started, this will be true
                    }
                }
            }
        }

    }

}
