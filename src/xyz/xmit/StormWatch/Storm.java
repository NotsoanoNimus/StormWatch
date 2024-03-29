package xyz.xmit.StormWatch;


import net.minecraft.util.Tuple;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.logging.Level;


/**
 * Acts as a base template for any type of custom or shipped weather event that has both a configuration
 * as well as a chance to target a player per-world and spawn. Relevant configuration nodes are read upon
 * class instantiation to ensure that the object and in-game conditions are correct to spawn the storm on
 * the target, all before the {@link #startStorm(Player)} method is utilized to schedule all entity spawns
 * as appropriate. Generally, the flow of any Storm instance is to initialize, read extended configuration
 * details, verify all data types are appropriate, schedule entity spawning, and fire a StormEndEvent at
 * the end of the object's life cycle.
 *
 * @see StormConfig
 * @see StormManager
 * @see StormManager.StormCallback
 */
public abstract class Storm implements StormManager.StormCallback {
    // Predefined/External references.
    //// Get REQUIRED configuration key names for each registered storm type (See: StormManager).
    /**
     * Contains all <em><strong>required</strong></em> configuration value node names, for use any
     * custom Storm extension's configuration tree. This is defined in a single place for ease of
     * access to changing the name of a required configuration key.
     */
    public enum RequiredConfigurationKeyNames implements StormConfig.ConfigKeySet {
        ENABLED("storm.enabled"),
        ENVIRONMENTS("environment.allowedTypes"),
        ENVIRONMENTS_ENFORCED("environment.enforced"),
        CHANCE("storm.chance"),
        COOLDOWN_RANGE("perWorldCooldown.rangeInSeconds"),
        COOLDOWN_ENABLED("perWorldCooldown.enabled"),
        DURATION_RANGE("storm.durationRangeInSeconds"),
        FOLLOW_PLAYER("storm.followsTargetPlayer"),
        SPEED_RANGE("storm.speedMultiplierRange"),
        TIME_RANGE("timeOfDay.permittedRange"),
        TIME_RANGE_ENFORCED("timeOfDay.enforced"),
        PITCH_RANGE("orientation.pitchRangeInDegrees"),
        YAW_RANGE("orientation.yawRangeInDegrees"),
        SPAWN_RATE_RANGE("entities.spawning.rateRangeInTicks"),
        SPAWN_AMOUNT_RANGE("entities.spawning.amountPerRate"),
        X_RANGE("entities.spawning.xRangeInBlocks"),
        Z_RANGE("entities.spawning.zRangeInBlocks"),
        HEIGHT_RANGE("entities.spawning.absoluteHeightRangeInBlocks"),
        WINDY("storm.windy.enabled"),
        WINDY_CHANCE("storm.windy.chance"),
        LOAD_CHUNKS("chunkLoading.enabled"),
        LOAD_CHUNKS_DIAMETER("chunkLoading.chunksRadius"),
        LOAD_CHUNKS_PERSISTENT("chunkLoading.persistent"),
        LOAD_CHUNKS_UNLOAD_DELAY("chunkLoading.unloadDelaySeconds"),
        EXEMPT_PLAYERS("exemptPlayers"),
        EXEMPT_WORLDS("exemptWorlds");
        public final String label;
        RequiredConfigurationKeyNames(String keyText) { this.label = keyText; }
        public final String getLabel() { return this.label; }
    }
    /**
     * Defines configuration key names required if any implementation of Storm would like to include
     * explosive entities or events. Technically, this is not a must-have if the parameters of a Storm
     * implementation's explosion event(s) do <em>not</em> come from the plugin's configuration.
     */
    public enum ExplosiveConfigurationKeyNames implements StormConfig.ConfigKeySet {
        EXPLODES("entities.explosions.enabled"),
        EXPLOSION_YIELD("entities.explosions.yield"),
        BREAKS_BLOCKS("entities.explosions.breaksBlocks"),
        INCENDIARY("entities.explosions.setsFires"),
        EXPLOSION_DAMAGE("entities.explosions.damage"),
        EXPLOSION_DAMAGE_RADIUS("entities.explosions.damageRadiusInBlocks");
        public final String label;
        ExplosiveConfigurationKeyNames(String keyText) { this.label = keyText; }
        public final String getLabel() { return this.label; }
    }

    // Logging methods. Useful for passing instance params to the static function very quickly.
    /**
     * @see #log(Exception, String)
     */
    protected final void log(Exception ex) { this.log(ex, ""); }
    /**
     * Passes exceptions with additional error text, up to the base class StormWatch
     *
     * @see StormWatch
     */
    protected final void log(Exception ex, String additionalMsg) {
        if(!additionalMsg.isEmpty()) { StormWatch.log(ex, this.getStormId()+": "+additionalMsg); } else { StormWatch.log(ex); }
    }
    /**
     * Log messages only when the <code>debug</code> configuration option is set to <em>true</em>.
     */
    protected final void debugLog(String logText) { this.log(true, Level.INFO, logText); }
    /**
     * Log an <em>INFO</em>-level message to the server console from the plugin instance.
     *
     * @see #log(Level, String)
     */
    protected final void log(String logText) { this.log(false, Level.INFO, logText); }
    /**
     * Log a non-debug message to the console from the plugin instance.
     *
     * @param lvl The log-level to set on the logged message.
     * @param logText The actual message to log.
     */
    protected final void log(Level lvl, String logText) { this.log(false, lvl, logText); }
    // `debugLog` should be used by heir classes.
    private void log(boolean isMsgDebugOnly, Level logLevel, String msg) {
        StormWatch.log(isMsgDebugOnly, logLevel, this.getStormId()+": "+msg); }


    // Abstract methods.
    /**
     * Sets the properties of the child-class instance for use in the entity scheduling routines as needed.
     * 
     * @return Whether the implementation's instance could successfully initialize the Storm type's properties.
     */
    protected abstract boolean initializeStormTypeProperties();   //sets the ranged properties of the instance of the stormtype (speed from speedRange, custom properties, etc).
    /**
     * Allows an extending sub-class to define type-specific properties and conditions that must be met for the Storm
     * scheduling to proceed. This function is run almost immediately when the {@link #startStorm(Player)} method is called.
     * However, if a Storm is invoked by command, the {@link #setStrictConditionChecks(boolean)} method is required to be
     * set as true in the extension class' constructor, or else this method will be skipped.
     * 
     * @return Whether the Storm sub-class is able to run given the checked conditions.
     * @see #startStorm(Player) 
     */
    protected abstract boolean stormSpecificConditionChecks();   //checks defined in a subclass that define whether a storm is able to start
    /**
     * Sets properties for inorganic (i.e. command-called) Storms. Useful for when different types of parameters
     * that are sent via the command line should be able to have a changing effect on the spawned Storm schedule
     * (or other properties thereof). Storms that should be CANCELLED based on provided parameters can always
     * use the {@link #setCancelled(boolean)} method inside of this call, since the CommandExecutor is designed
     * to check for a cancelled Storm before starting it.
     *
     * @see StormWatchCommandExecutor
     * @see #setCancelled(boolean) 
     */
    protected abstract void setPropertiesFromCommand();
    /**
     * Create an entity from the Storm, or do Storm-specific actions here. This function implies that it's only used
     * for spawning entities; however, any scheduled task that runs at the spawn amount rate and at the chosen
     * interval can be implemented here.
     * 
     * @return Either null or a spawned Entity object to add to the list of spawned objects in-game.
     * @see #startStorm(Player) 
     */
    protected abstract Entity getNextEntity();
    /**
     * As the name implies, any task or code that should be run just before the schedule for the Storm extension
     * is created, should be done here.
     */
    protected abstract void doJustBeforeScheduling();
    /**
     * As the name implies, any task or code that should be run just <em>after</em> the schedule for the Storm
     * extension is created, should be done here.
     */
    protected abstract void doJustAfterScheduling();
    /**
     * Task(s) to run after a <em>callback</em> has fired on StormEndEvent. This is primarily used for post-Storm
     * cleanup after all other tasks have been completed.
     *
     * @see StormEndEvent
     * @see StormManager.StormCallback
     */
    public abstract void doCleanupAfterStorm();   //tasks to run after the storm ending event fires



    // Inherited values.
    /**
     * A default configuration entered for <strong>all</strong> registered Storm types that instantiate without
     * one or more of these keys enabled.
     *
     * @see #Storm(String, Map)
     * @see RequiredConfigurationKeyNames
     */
    protected final HashMap<String, Object> baseDefaultConfiguration = new HashMap<>() {{
        put(RequiredConfigurationKeyNames.ENABLED.label, true);
        put(RequiredConfigurationKeyNames.ENVIRONMENTS.label, new String[]{"NORMAL","THE_END"});
        put(RequiredConfigurationKeyNames.ENVIRONMENTS_ENFORCED.label, true);
        put(RequiredConfigurationKeyNames.CHANCE.label, 0.0123);
        put(RequiredConfigurationKeyNames.COOLDOWN_RANGE.label, new int[]{300,1200});
        put(RequiredConfigurationKeyNames.COOLDOWN_ENABLED.label, true);
        put(RequiredConfigurationKeyNames.DURATION_RANGE.label, new int[]{60,600});
        put(RequiredConfigurationKeyNames.FOLLOW_PLAYER.label, true);
        put(RequiredConfigurationKeyNames.SPEED_RANGE.label, new double[]{0.7,4.6});
        put(RequiredConfigurationKeyNames.TIME_RANGE.label, new int[]{0,24000});
        put(RequiredConfigurationKeyNames.TIME_RANGE_ENFORCED.label, false);
        put(RequiredConfigurationKeyNames.PITCH_RANGE.label, new int[]{70,110});
        put(RequiredConfigurationKeyNames.YAW_RANGE.label, new int[]{0,360});
        put(RequiredConfigurationKeyNames.SPAWN_RATE_RANGE.label, new int[]{15,45});
        put(RequiredConfigurationKeyNames.SPAWN_AMOUNT_RANGE.label, new int[]{10,25});
        put(RequiredConfigurationKeyNames.X_RANGE.label, new int[]{-100,100});
        put(RequiredConfigurationKeyNames.Z_RANGE.label, new int[]{-100,100});
        put(RequiredConfigurationKeyNames.HEIGHT_RANGE.label, new int[]{280,380});
        put(RequiredConfigurationKeyNames.WINDY.label, false);
        put(RequiredConfigurationKeyNames.WINDY_CHANCE.label, 0.001);
        put(RequiredConfigurationKeyNames.LOAD_CHUNKS.label, true);
        put(RequiredConfigurationKeyNames.LOAD_CHUNKS_DIAMETER.label, 5);
        put(RequiredConfigurationKeyNames.LOAD_CHUNKS_PERSISTENT.label, false);
        put(RequiredConfigurationKeyNames.LOAD_CHUNKS_UNLOAD_DELAY.label, 0);
        put(RequiredConfigurationKeyNames.EXEMPT_PLAYERS.label, new ArrayList<String>());
        put(RequiredConfigurationKeyNames.EXEMPT_WORLDS.label, new ArrayList<String>());
    }};
    /**
     * Default explosive-entity configuration that's provided, but not required, should any extension
     * of the Storm class wish to use config-enabled explosions.
     *
     * @see ExplosiveConfigurationKeyNames
     */
    protected static final HashMap<String, Object> defaultExplosiveConfiguration = new HashMap<>() {{
        put(ExplosiveConfigurationKeyNames.EXPLODES.label, false);
        put(ExplosiveConfigurationKeyNames.EXPLOSION_YIELD.label, 0);
        put(ExplosiveConfigurationKeyNames.BREAKS_BLOCKS.label, false);
        put(ExplosiveConfigurationKeyNames.INCENDIARY.label, false);
        put(ExplosiveConfigurationKeyNames.EXPLOSION_DAMAGE.label, 0.0);
        put(ExplosiveConfigurationKeyNames.EXPLOSION_DAMAGE_RADIUS.label, 0);
    }};

    /**
     * Single random-number-generator instance reused throughout the Storm class and sub-classes.
     */
    protected final Random rng = new Random(); //random number generator
    private final UUID stormId; //unique storm object ID
    // Information about the storm.
    //// Trackers
    private boolean isStarted = false; //has the startStorm method been invoked (and gotten past initial checks)?
    private boolean cancelled = false; //is the storm attempting to be cancelled?
    private boolean isSingleSpawnPerJob = false; //can be set by heirs to force the `spawnAmountRange` values to be ignored
    private boolean isCalledByCommand = false; //was the storm started by command
    private boolean strictConditionChecks = false; //whether to ALWAYS perform condition checks on startStorm
    // ^^^ See the "StormSandstorm" module for why this can certainly be a useful tool!
    private Location baseSpawnLocation; //can either follow the player or stay stationary (see below)
    private int stormPitch, stormYaw; //storm "direction", defined by a value between a configurable range
    private int stormDurationTicks; //storm instance event duration
    private int cooldown = 0; //current instance cooldown. can be changed, since cooldowns are read on the END event.
    private final ArrayList<Entity> spawnedEntities = new ArrayList<>(); //collection of spawned entities; not required to use
    private final ArrayList<BukkitTask> scheduledSpawns = new ArrayList<>(); //collection of scheduled tasks
    private BukkitTask endEventCall; //pointer to task for calling the terminating event

    //// Provided by parameter.
    /**
     * The root configuration key-name of the Storm.
     */
    protected final String typeName; //Storm type's name (also used for the config node) -- this should be UNIQUE!
    private Player targetPlayer; //player targeted by the current event
    //// Provided by configuration
    private boolean isLoadsChunks; //does the storm load chunks?
    private boolean isLoadedChunksPersistent; //do loaded Storm chunks persist after the storm finishes?
    private boolean isCooldownEnabled; //does the storm create locks (i.e. is there a cooldown enabled?)
    private double stormChance; //chance for the storm to spawn
    private final ArrayList<World.Environment> permittedWorldEnvironments = new ArrayList<>(); //permitted environments in which the storm can spawn
    private boolean permittedWorldEnvironmentsEnforced = false; //enforce environment checks?
    private Tuple<Integer,Integer> cooldownRange; //cooldown between storms in each world
    private Tuple<Integer,Integer> durationRange; //duration range (in SECONDS) of the storm event
    private Tuple<Integer,Integer> spawnRateRange; //range of server ticks for creating new spawn jobs in the scheduler
    private Tuple<Integer,Integer> spawnAmountRange; //range of how many can spawn per tick-rate above
    private Tuple<Integer,Integer> pitchRange; //pitch axis range
    private Tuple<Integer,Integer> yawRange; //yaw axis range
    private Tuple<Integer,Integer> xRange; //x-spawn range from player base location
    private Tuple<Integer,Integer> zRange; //same as above, but for z-axis
    private Tuple<Integer,Integer> heightRange; //y-axis absolute min-to-max height at which to spawn entities (this one is NOT relative to the player)
    private Tuple<Integer,Integer> timeRange; //minecraft time-range in which storms can occur
    private Tuple<Double,Double> speedRange; //range of speed entities can move at
    private boolean timeRangeEnforced; //does the storm only spawn in the configured time range?
    private boolean isWindy; //can the storm change direction mid-schedule?
    private double windyChance; //chance for the storm to change direction
    private boolean followPlayer; //does the storm follow the player as it spawns entities?
    private boolean isSchedulingDisabled = false; //should scheduling be skipped?
    private int stormDurationEndPaddingTicks = 0;
    private int chunkLoadingDiameter; //how many chunks the Storm loads from end-to-end of a square area
    private int chunkLoadingUnloadDelay; //delay in seconds to wait after the StormEndEvent finishes to unload the Storm's chunks
    private ArrayList<String> exemptPlayers, exemptWorlds; // exempt worlds and target players for the Storm
    private String[] commandParams;


    // TEST CODE - New default config testing.
    private static HashMap<String, Object> keySetToMap(StormConfig.ConfKeySet[] kv) {
        var defaultConfMap = new HashMap<String, Object>();
        for(var keySet : kv) {
            defaultConfMap.put(keySet.getLabel(), keySet.getDefaultValue());
        }
        return defaultConfMap;
    }

    public Storm(String name, StormConfig.ConfKeySet[] defaultConfKeys) {
        this(name, Storm.keySetToMap(defaultConfKeys));
    }
    // =======================

    // Default constructor requires an immutable name field and a configuration object.
    public Storm(String name, Map<String,Object> defaultConfig) {
        if(name == null || name.isEmpty()) {
            this.log(Level.WARNING, "Did not get a valid storm type name. Skipping construction.");
            this.typeName = ""; this.stormId = null;
            this.setCancelled(true);
            return;
        } else if(defaultConfig == null || defaultConfig.isEmpty()) {
            this.log(Level.WARNING, "Did not receive any kind of default configuration for this type. Is this intentional?");
        }
        // Set the canonicalized typename.
        this.typeName = name.toLowerCase(Locale.ROOT);
        // Assign the storm a valid UUID.
        this.stormId = UUID.randomUUID();

        // Populate any default configuration variables that aren't already defined.
        //   This needs to be done BEFORE checking if the Storm type is enabled, otherwise first-run instantiations will
        //   always return a FALSE for the Storm type being ENABLED.
        this.baseDefaultConfiguration.putAll(defaultConfig);   //add the per-subclass object defaults to the super's default
        // ^^ This can overwrite keys that already exist as well as define custom ones for the calling object.
        if(!StormWatch.defaultConfigsSetForTypes.contains(this.getClass())) {
            StormWatch.defaultConfigsSetForTypes.add(this.getClass());
            try {
                ////StormWatch.instance.stormConfig.setSectionDefaults(name, this.baseDefaultConfiguration);
                // Add all subnodes onto the base name, e.g. "impact" + "[.]environment.enforced" to give a full path to its configuration.
                ArrayList<String> set = new ArrayList<>(this.baseDefaultConfiguration.keySet());
                for (String s : set) {
                    this.baseDefaultConfiguration.put(this.typeName + "." + s, this.baseDefaultConfiguration.get(s));
                    this.baseDefaultConfiguration.remove(s);
                }
                // Set these default values at the global scope as applicable using the newly-build full paths.
                StormWatch.getStormConfig().setDefaults(this.baseDefaultConfiguration);
            } catch (Exception e) {
                this.log(e, "Unable to check default types and config for storm type: " + this.typeName);
                this.setCancelled(true);
                return;
            }
        }

        // Verify whether the Storm type is config-enabled.
        if(!this.getEnabled()) {
            this.log(Level.WARNING, "Storm type [" + name + "] is config-disabled.");
            this.setCancelled(true); return;
        }

        // Set base config stuff (required to be set for ALL storm types).
        //   If this faults at all, then there is a problem with a REQUIRED configuration parameter.
        try {
            this.constructBaseStormOptions();
        } catch (Exception e) {
            this.log(e,"Possible bad configuration value! Could not instantiate Storm type "
                    + this.typeName + ":   " + e.getMessage());
            this.setCancelled(true); return;
        }

        // Explicitly mark this Storm instance OK to use.
        this.setCancelled(false);
    }


    // Instantiation fields (can also be used manually or overridden). These are always called on storm start (even if overridden)!
    private void constructBaseStormOptions() throws Exception {
        //// storm chance
        this.stormChance = StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.CHANCE);
        //// permitted environments
        ArrayList<String> envNames =
                StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.ENVIRONMENTS);
        for(String s : envNames) { this.permittedWorldEnvironments.add(World.Environment.valueOf(s)); }
        this.permittedWorldEnvironmentsEnforced =
                StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.ENVIRONMENTS_ENFORCED);
        //// getting ranges

        /*//TEST CODE
        var yawRangeTest = new StormConfig.RangedValue<Integer>(this.typeName, RequiredConfigurationKeyNames.YAW_RANGE);
        this.yawRange = yawRangeTest.getValueRange();*/

        this.cooldownRange = StormConfig.getIntegerRange(this.typeName, RequiredConfigurationKeyNames.COOLDOWN_RANGE);
        this.durationRange = StormConfig.getIntegerRange(this.typeName, RequiredConfigurationKeyNames.DURATION_RANGE);
        this.speedRange = StormConfig.getDoubleRange(this.typeName, RequiredConfigurationKeyNames.SPEED_RANGE);
        this.pitchRange = StormConfig.getIntegerRange(this.typeName, RequiredConfigurationKeyNames.PITCH_RANGE);
        this.yawRange = StormConfig.getIntegerRange(this.typeName, RequiredConfigurationKeyNames.YAW_RANGE);
        this.xRange = StormConfig.getIntegerRange(this.typeName, RequiredConfigurationKeyNames.X_RANGE);
        this.zRange = StormConfig.getIntegerRange(this.typeName, RequiredConfigurationKeyNames.Z_RANGE);
        this.heightRange = StormConfig.getIntegerRange(this.typeName, RequiredConfigurationKeyNames.HEIGHT_RANGE);
        this.spawnRateRange = StormConfig.getIntegerRange(this.typeName, RequiredConfigurationKeyNames.SPAWN_RATE_RANGE);
        this.spawnAmountRange = StormConfig.getIntegerRange(this.typeName, RequiredConfigurationKeyNames.SPAWN_AMOUNT_RANGE);
        //// windy settings
        this.isWindy = StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.WINDY);
        this.windyChance = StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.WINDY_CHANCE);
        //// storm follows player
        this.followPlayer = StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.FOLLOW_PLAYER);
        //// time-range check
        this.timeRange = StormConfig.getIntegerRange(this.typeName, RequiredConfigurationKeyNames.TIME_RANGE);
        this.timeRangeEnforced = StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.TIME_RANGE_ENFORCED);
        //// instance cooldown, if the storm type has a cooldown enabled
        this.cooldown = this.getRandomInt(this.cooldownRange);
        this.isCooldownEnabled = StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.COOLDOWN_ENABLED);
        //// chunk loading settings
        this.chunkLoadingDiameter = StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.LOAD_CHUNKS_DIAMETER);
        this.chunkLoadingUnloadDelay = StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.LOAD_CHUNKS_UNLOAD_DELAY);
        this.isLoadedChunksPersistent = StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.LOAD_CHUNKS_PERSISTENT);
        this.isLoadsChunks = StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.LOAD_CHUNKS);
        //// exemption settings
        this.exemptPlayers = StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.EXEMPT_PLAYERS);
        this.exemptWorlds = StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.EXEMPT_WORLDS);
        //// preset storm duration (changeable by sub-classes before scheduling)
        ////   NOTE: The storm duration is in SERVER TICKS
        this.stormDurationTicks = this.getNewDurationInTicks();
    }


    //////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////
    ////// GET methods.
    public final boolean getEnabled() {
        try {
            return StormConfig.getConfigValue(this.typeName, RequiredConfigurationKeyNames.ENABLED);
        } catch (Exception ex) {
            this.log(ex, "Unable to get ENABLED status for type " + this.typeName);
            return false;
        }
    }
    public final boolean isCancelled() { return this.cancelled; }
    public final boolean isCooldownEnabled() { return this.isCooldownEnabled; }
    public final boolean isStarted() { return this.isStarted; }
    public final boolean isWindy() { return this.isWindy; }
    public final boolean isSchedulingDisabled() { return this.isSchedulingDisabled; }
    public final boolean isTimeRangeEnforced() { return this.timeRangeEnforced; }
    public final boolean isPermittedWorldEnvironmentsEnforced() { return this.permittedWorldEnvironmentsEnforced; }
    public final boolean isCalledByCommand() { return this.isCalledByCommand; }
    public final boolean isFollowPlayer() { return this.followPlayer; }
    public final boolean isSingleSpawnPerJob() { return this.isSingleSpawnPerJob; }
    public final double getStormChance() { return this.stormChance; }
    public final int getStormYaw() { return this.stormYaw; }
    public final int getStormPitch() { return this.stormPitch; }
    public final Tuple<Integer, Integer> getPitchRange() { return this.pitchRange; }
    public final Tuple<Integer, Integer> getYawRange() { return this.yawRange; }
    public final int getStormDurationTicks() { return this.stormDurationTicks; }
    public final int getStormDurationEndPaddingTicks() { return this.stormDurationEndPaddingTicks; }
    public final int getInstanceCooldown() { return this.cooldown; }
    public final int getChunkLoadingDiameter() { return this.chunkLoadingDiameter; }
    public final int getChunkLoadingUnloadDelay() { return this.chunkLoadingUnloadDelay; }
    public final String[] getCommandParameters() { return this.commandParams; }
    public final boolean isLoadsChunks() { return this.isLoadsChunks; }
    public final boolean isLoadedChunksPersistent() { return this.isLoadedChunksPersistent; }
    public final ArrayList<String> getExemptPlayers() { return this.exemptPlayers; }
    public final boolean isPlayerNameExempt(String playerName) { return this.exemptPlayers.contains(playerName); }
    public final ArrayList<String> getExemptWorlds() { return this.exemptWorlds; }
    public final boolean isWorldNameExempt(String worldName) { return this.exemptPlayers.contains(worldName); }
    public final boolean isStrictConditionChecks() {return this.strictConditionChecks; }
    /**
     * Gets a new location from configuration-defined coordinate ranges. The returned location can either consider the
     * configuration ranges to be absolute (i.e. in-game coordinates) between which a storm event can spawn, or a
     * metric of coordinates that is relative to the player's base location when the storm spawned (this base can also
     * change through the duration of the spawn, see {@link #isFollowPlayer()}.
     *
     * @param isXRelative Is the X coordinate of the new location relative to the storm's base spawn location or absolute?
     * @param isYRelative Is the Y coordinate of the new location relative to the storm's base spawn location or absolute?
     * @param isZRelative Is the Z coordinate of the new location relative to the storm's base spawn location or absolute?
     * @return A new location that is either absolute or relative to the target player, based on the provided boolean values per axis.
     * @see #isFollowPlayer()
     * @see #getBaseSpawnLocation()
     */
    public final Location getNewRelativeLocation(boolean isXRelative, boolean isYRelative, boolean isZRelative) {
        Location newLoc = this.baseSpawnLocation.clone();
        newLoc.setX(isXRelative ? (newLoc.getX() + this.getNewXSpawn()) : this.getNewXSpawn());
        newLoc.setY(isYRelative ? (newLoc.getY() + this.getNewYSpawn()) : this.getNewYSpawn());
        newLoc.setZ(isZRelative ? (newLoc.getZ() + this.getNewZSpawn()) : this.getNewZSpawn());
        return newLoc;
    }
    public final String getTypeName() { return this.typeName; }
    public final String getName() { return this.typeName; }
    public final Location getBaseSpawnLocation() { return this.baseSpawnLocation; }
    public final Player getTargetPlayer() { return this.targetPlayer; }
    public final ArrayList<BukkitTask> getScheduledSpawns() { return this.scheduledSpawns; }
    public final ArrayList<World.Environment> getPermittedWorldEnvironments() { return this.permittedWorldEnvironments; }
    public final ArrayList<Entity> getSpawnedEntities() { return this.spawnedEntities; }
    public final UUID getStormId() { return this.stormId; }
    public final Tuple<Integer,Integer> getTimeRange() { return this.timeRange; }
    public final Tuple<Integer,Integer> getSpawnAmountRange() { return this.spawnAmountRange; }
    public final Tuple<Integer,Integer> getSpawnRateRange() { return this.spawnRateRange; }
    //// Getter methods that use a range defined at instantiation.
    protected final double getNewSpeed() { return this.getRandomDouble(this.speedRange); }
    protected final int getNewCooldown() { return this.getRandomInt(this.cooldownRange); }
    protected final int getNewPitch() { return this.getRandomInt(this.pitchRange); }
    protected final int getNewYaw() { return this.getRandomInt(this.yawRange); }
    protected final int getNewDurationInTicks() { return this.getRandomInt(this.durationRange) * 20; }
    protected final int getNewXSpawn() { return this.getRandomInt(this.xRange); }
    protected final Tuple<Integer,Integer> getXRange() { return this.xRange; }
    protected final int getNewYSpawn() { return this.getRandomInt(this.heightRange); }
    protected final Tuple<Integer,Integer> getHeightRange() { return this.heightRange; }
    protected final int getNewZSpawn() { return this.getRandomInt(this.zRange); }
    protected final Tuple<Integer,Integer> getZRange() { return this.zRange; }
    /////
    // Explosive options/getters for static fields (non-range).
    public final boolean getExplosionEnabled() throws Exception {
        return StormConfig.getConfigValue(this.typeName, ExplosiveConfigurationKeyNames.EXPLODES); }
    public final boolean getSetsFires() throws Exception {
        return StormConfig.getConfigValue(this.typeName, ExplosiveConfigurationKeyNames.INCENDIARY); }
    public final boolean getBreaksBlocks() throws Exception {
        return StormConfig.getConfigValue(this.typeName, ExplosiveConfigurationKeyNames.BREAKS_BLOCKS); }
    public final double getExplosionDamage() throws Exception {
        return StormConfig.getConfigValue(this.typeName, ExplosiveConfigurationKeyNames.EXPLOSION_DAMAGE); }
    public final int getExplosionYield() throws Exception {
        return StormConfig.getConfigValue(this.typeName, ExplosiveConfigurationKeyNames.EXPLOSION_YIELD); }
    public final int getExplosionDamageRadius() throws Exception {
        return StormConfig.getConfigValue(this.typeName, ExplosiveConfigurationKeyNames.EXPLOSION_DAMAGE_RADIUS); }


    //////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////
    ////// SET/Modify methods. Sub-classes and extensions should NEVER override these (they shouldn't be able to).
    // Cancel the Storm instance.
    protected final void setCancelled(boolean isCancelled) {
        if(isCancelled) { this.debugLog("Storm has been cancelled!"); }
        if(this.isStarted()) {
            // If the StormStartEvent was already fired, this storm should be cancelled thoroughly.
            this.cancelScheduledSpawns();
        }
        this.cancelled = isCancelled;
    }
    // Set the Storm as command generated (not "natural").
    protected final void setIsCalledByCommand() { this.isCalledByCommand = true; }
    // Directly change the cooldown, if desired.
    protected final void setInstanceCooldown(int cooldownInSeconds) { this.cooldown = cooldownInSeconds; }
    // Set the direction of the storm entities based on the yaw/pitch ranges.
    protected final void setNewRandomStormDirection() {
        this.stormPitch = this.getRandomInt(this.pitchRange);
        this.stormYaw = this.getRandomInt(this.yawRange);
    }
    protected final void setSingleSpawnPerJob(boolean singleSpawning) { this.isSingleSpawnPerJob = singleSpawning; }
    protected final void setStormPitch(int pitch) { this.stormPitch = pitch; }
    protected final void setStormYaw(int yaw) { this.stormYaw = yaw; }
    // Allows a storm extension to set the target player at will. MIGHT act a bit strangely.
    protected final void setTargetPlayer(Player target) { this.targetPlayer = target; }
    protected final void setFollowPlayer(boolean followPlayer) { this.followPlayer = followPlayer; }
    // Set the base spawn location for all entities (used to update the location as well on player movement).
    protected final void updateBaseLocation() {
        Location t = this.targetPlayer.getLocation().clone();
        t.setYaw(this.stormYaw); t.setPitch(this.stormPitch);
        this.baseSpawnLocation = t.clone();
    }
    protected final void setStormIsOngoing(boolean isOngoing) { this.isStarted = isOngoing; }
    protected final void setSchedulingDisabled(boolean isDisabled) { this.isSchedulingDisabled = isDisabled; }
    // Allows a storm to be ended prematurely.
    protected final void cancelScheduledSpawns() {
        for(BukkitTask task : this.scheduledSpawns) { task.cancel(); }   //cancel all scheduled items
        this.endEventCall.cancel();   //cancel the original end-storm call
        this.endStorm(20, this);   //call the cancel event in 20 ticks (1 second)
    }
    protected final void addScheduledSpawnTask(BukkitTask t) { this.scheduledSpawns.add(t); }
    // Manually add spawned entities to the list.
    protected final void addSpawnedEntity(Entity e) { this.spawnedEntities.add(e); }
    // Attempt a removal on any leftover entities, if desired.
    protected final void destroySpawnedEntities() {
        this.debugLog("Destroying all spawned entities and cleaning up.");
        for(Entity e : this.spawnedEntities) {
            try {
                if(e != null) { e.remove(); }
            } catch (Exception ex) { StormWatch.log(ex); }
        }
    }
    protected final void setStormDurationTicks(int ticks) { this.stormDurationTicks = ticks; }
    protected final void setStormDurationEndPaddingTicks(int padding) { this.stormDurationEndPaddingTicks = padding; }
    protected final void setStrictConditionChecks(boolean isStrict) { this.strictConditionChecks = isStrict; }
    protected final void setCommandParameters(String[] cmdArgs) { this.commandParams = cmdArgs; }
    // TODO: Add more "internal" property setter methods that Storm extensions can adjust freely.
    protected final void setXRange(Tuple<Integer, Integer> newRange) { this.xRange = newRange; }
    protected final void setZRange(Tuple<Integer, Integer> newRange) { this.zRange = newRange; }
    /////
    // Explosive SET/modify methods.


    // Default implementations.
    //// Start the storm; trigger the entity spawning for the specified duration.
    public final void startStorm(Player targetedPlayer) {
        // Initialize some base values from the config and player information.
        //   Particularly important is the base location instantiation before other checks that happen here.
        this.setTargetPlayer(targetedPlayer);
        this.setNewRandomStormDirection();   //initialize a pitch/yaw for the storm.
        this.updateBaseLocation();   //update the base location.
        // Run the condition checks, only if either (1) the storm is organic, or (2) the Storm is set to use
        //   condition checks even while being command-called.
        if(!this.isCalledByCommand() || this.isStrictConditionChecks()) {
            if(!this.baseConditionChecks() || !this.stormSpecificConditionChecks()) {
                this.debugLog("Storm did not pass base condition or storm-specific checks.");
                this.setCancelled(true);
                return;
            }
        }

        // Initialize the storm-specific properties.
        if(!this.initializeStormTypeProperties()) {
            this.log(Level.WARNING, "Could not initialize storm-specific properties! IS it configured properly?");
            this.setCancelled(true);
            return;
        }

        // If this is called by command, call the method now that has a chance to override the gotten properties.
        if(this.isCalledByCommand()) {
            try {
                this.setPropertiesFromCommand();
            } catch(Exception ex) {
                StormWatch.log(false, "Commanded Storm CANCELLED! Problem settings Storm properties from the command call.");
                StormWatch.log(false, ex.getMessage());
                StormWatch.log(ex);
                this.setCancelled(true);
                return;
            }
        }


        // Output console information.
        this.debugLog("- Starting storm of type [" + this.typeName +
                "] in world: " + this.baseSpawnLocation.getWorld().getName());
        this.debugLog("--- Target Player: " + this.getTargetPlayer().getDisplayName()
                + " /// Duration: " + this.getStormDurationTicks() +
                " (" + (this.getStormDurationTicks() / 20) + " seconds)"
                + " /// Cooldown: " + this.getInstanceCooldown());

        // Do per-storm-type tasks before starting the storm event.
        this.doJustBeforeScheduling();
        // Check cancelled status.
        if(this.isCancelled()) {
            this.debugLog("Storm was cancelled after the `doJustBeforeScheduling` method. Nothing scheduled or started.");
            return;
        }

        ////// THIS POINT IS CONSIDERED A "START" AND REGISTRATION.
        //////   WHEN A STORM "STARTS", ITS CANCELLATION WILL ALWAYS INCLUDE A
        //////   CLEANUP OF THE REGISTERED UUID IN THE STORM MANAGER VIA "CANCELSCHEDULEDSPAWNS".
        // Register the storm through the primary event listener (StormManager).
        var stormEvent = new StormStartEvent(this.getBaseSpawnLocation().getWorld(), this);
        Bukkit.getPluginManager().callEvent(stormEvent);
        // Set the storm type to started.
        this.setStormIsOngoing(true);
        // Load the chunks as applicable.
        if(this.isLoadsChunks()) {
            try {
                StormWatch.getStormChunkManager().loadChunksNear(this.getTargetPlayer(), this.getChunkLoadingDiameter(), this.getStormId());
            } catch (Exception ex) { this.log(ex); }
        }

        // Schedule the spawn tasks, if enabled.
        if(!this.isSchedulingDisabled()) {
            // Start scheduling. Count the scheduled "lapsed" ticks allllll the way up to the storm duration (in ticks).
            //   Hopefully, this will schedule a schedule of spawned meteors through the storm duration at semi-random intervals.
            this.debugLog("--- {Scheduled} Storm event commencing for: " + (this.getStormDurationTicks() / 20)
                    + " seconds [" + this.getStormDurationTicks() + " TICKS].");
            int ticksLapsed = 0;
            int minTicksBetweenSpawns = 4;  // there needs to be at least 4 ticks between spawn ops; prevents infinite looping
            int spawnedEntityCount = 0;
            do {
                //how many ticks to wait until the next scheduled event
                int ticksTilNextSpawn = this.getRandomInt(this.spawnRateRange);
                ticksTilNextSpawn = Math.max(minTicksBetweenSpawns, ticksTilNextSpawn);
                //how many entities to spawn in the scheduled event
                int howMany = this.isSingleSpawnPerJob() ? 1 : this.getRandomInt(this.spawnAmountRange);
                spawnedEntityCount += howMany;
                ticksLapsed += ticksTilNextSpawn;
                //schedule the entities
                this.scheduleNextEntities(ticksLapsed, howMany, this);
            } while (ticksLapsed < this.getStormDurationTicks());
            this.debugLog("----- Scheduling complete for new storm.");
            this.debugLog("------- Spawning total of " + spawnedEntityCount +
                    " entities over " + ticksLapsed + " server ticks.");
        } else {
            this.debugLog("----- Scheduling DISABLED for this type. Ran commands and left ASAP.");
        }

        // Do the "after-scheduling" tasks, even if nothing was scheduled.
        this.doJustAfterScheduling();
        if(this.isCancelled()) {
            this.debugLog("Storm cancelled after `doJustAfterScheduling`.");
            return;
        }

        // Register the task to call the end event with a delay in "tick" units (with a padding of 30 ticks by default).
        //   NOTE: That padding is there to allow final entities to exist in the world before being cleaned up, where applicable.
        this.endStorm(this.getStormDurationTicks() + this.getStormDurationEndPaddingTicks(), this); //padding of ~1.5s on the end event
    }

    //// Schedules the queued entities to create.
    protected final void scheduleNextEntities(int delay, int count, Storm instance) {
        this.scheduledSpawns.add(new BukkitRunnable() {
            public void run() {
                try {
                    if(followPlayer) { updateBaseLocation(); }
                    // Random (LOW) chance to change the storm's direction, if enabled.
                    if(isWindy() && getRandomDouble(0, 1.0) < windyChance) { setNewRandomStormDirection(); }
                    // Create the entities according to the method implementation..
                    for (int i = 0; i < count; i++) {
                        var x = instance.getNextEntity();
                        if(x != null) { spawnedEntities.add(x); }
                    }
                } catch (Exception ex) {
                    log(ex, "Problem spawning entity batch");
                }
            }
        }.runTaskLater(StormWatch.instance, delay));
    }

    //// Method to end a storm after the provided duration. Uses 'this' object to determine which storm is
    ////   being ended in the StormManager instance.
    private void endStorm(long stormDurationServerTicks, Storm instance) {
        this.endEventCall = new BukkitRunnable() {
            public void run() {
                instance.setStormIsOngoing(false);
                var endStorm =  new StormEndEvent(stormId, instance, instance);
                Bukkit.getPluginManager().callEvent(endStorm);
            }
        }.runTaskLater(StormWatch.getInstance(), stormDurationServerTicks);
    }


    // Use base-configuration (REQUIRED KEYS) to determine some super-class checks like timeRange and envType, etc.
    private boolean baseConditionChecks() {
        if(this.isTimeRangeEnforced()) {
            int worldTime = (int)(this.getTargetPlayer().getWorld().getTime() % 24000);
            if(worldTime < this.timeRange.a() || worldTime > this.timeRange.b()) {
                this.debugLog("Cannot start: explicit Time Range is enforced.");
                return false;
            }
        }
        if(this.isPermittedWorldEnvironmentsEnforced()) {
            if(!this.getPermittedWorldEnvironments().contains(this.getTargetPlayer().getWorld().getEnvironment())) {
                this.debugLog("Cannot start: player world environment is not permitted.");
                return false;
            }
        }
        if(this.isCancelled()) {
            this.debugLog("Cannot start: storm has already been cancelled.");
            return false;
        }
        if(this.isStarted()) {
            this.debugLog("Cannot start: storm has already been noted as started.");
            return false;
        }
        // If nothing else returned a false by now, check the last two items and all set.
        return true;
    }


    // Misc functions
    @Override
    public String toString() {
        if(!this.isCancelled()) {
            return "Storm sub-class of type [" + this.getName() + "] with ID: " + this.getStormId();
        } else { return "Cancelled Storm sub-class of type " + this.getName(); }
    }
    //// Register a subclass as a Listener type to catch events (and be unloaded with the plugin on disable).
    protected final void registerAsListener(Listener instance) {
        if(instance == null) { this.setCancelled(true); return; }
        StormWatch.getInstance().getServer().getPluginManager().registerEvents(instance, StormWatch.getInstance());
        StormWatch.getInstance().addRegisteredListener(instance);
    }
    //// WRAPPER FUNC: Get random value of value type between two values.
    protected final int getRandomInt(Tuple<Integer,Integer> range) {
        return this.getRandomInt(range.a(), range.b()); }
    protected final int getRandomInt(int min, int max) {
        try {
            if ((max - min) < 0) {
                return (min - this.rng.nextInt(Math.abs(max - min)));
            } else {
                return (this.rng.nextInt(max - min) + min);
            }
        } catch(Exception ex) { return 0; }
    }
    protected final double getRandomDouble(Tuple<Double,Double> range) {
        return this.getRandomDouble(range.a(), range.b()); }
    protected final double getRandomDouble(double min, double max) {
        try {
            return (this.rng.nextDouble() * (max - min)) + min;
        } catch(Exception ex) { return 0.0; }
    }
}
