package xyz.xmit.StormWatch;

import org.bukkit.*;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.logging.Level;


/**
 * A Spigot/Bukkit Minecraft server plugin designed to create and manage storm events
 * on a per-world, per-player basis. Storm events are defined with a certain set of
 * parameters to spawn which, if met, will be scheduled and executed appropriately
 * by the single worker thread given to the server instance. These events change form
 * depending on the server configuration and the administrator's management of the
 * modules themselves.
 */
public class StormWatch extends JavaPlugin {
    /**
     * Self-referential object representing the single instance of the plugin instantiated
     * when the server loads/enables the plugin.
     */
    protected static StormWatch instance;
    /**
     * Tracks registered Storm extensions that have had their default configurations populated
     * during the current lifetime of the plugin. This variable exists primarily to prevent
     * looping that would cause the configuration to be constantly accessed and/or overwritten.
     */
    protected static final ArrayList<Class> defaultConfigsSetForTypes = new ArrayList<>();
    /**
     * The plugin runs on its own timer by firing custom plugin events once at every interval in server
     * ticks, using this parameter to define that interval (as <strong>every 5 seconds</strong>). These
     * custom tick events are crucial as they represent how long between "chance" measurements that have
     * a chance to spawn a new storm type in-game.
     */
    public static final long TickRate = 100L;
    private final ArrayList<Listener> registeredListeners = new ArrayList<>();
    private StormConfig stormConfig;
    private StormManager stormManager;
    private StormChunkManager stormChunkManager;
    // Tick timer task.
    private BukkitTask tickTimerTask;
    // Debug flag. Config-specified.
    private boolean debug, logOnNewStormEvent;
    private ArrayList<String> globalExemptPlayers, globalExemptWorlds;
    // Top-level configuration variables for the plugin.
    private enum BaseConfigurationKeyNames implements StormConfig.ConfigKeySet {
        DEBUG("debug"),
        LOG_ON_STORM_EVENT_START("logOnNewStormStart"),
        GLOBAL_EXEMPT_WORLD_NAMES("globalExemptWorldNames"),
        GLOBAL_EXEMPT_PLAYER_NAMES("globalExemptPlayerNames");
        private final String label;
        BaseConfigurationKeyNames(String keyText) { this.label = keyText; }
        public final String getLabel() { return this.label; }
    }
    // Defines the default configuration for the top-level YML scope.
    private static final HashMap<String, Object> defaultConfig = new HashMap<>() {{
        put(BaseConfigurationKeyNames.DEBUG.label, false);
        put(BaseConfigurationKeyNames.LOG_ON_STORM_EVENT_START.label, true);
        put(BaseConfigurationKeyNames.GLOBAL_EXEMPT_WORLD_NAMES.label, new ArrayList<String>());
        put(BaseConfigurationKeyNames.GLOBAL_EXEMPT_PLAYER_NAMES.label, new ArrayList<String>());
    }};


    // MODIFIER SET TO PROTECTED, to prevent outside callers from logging and spoofing as this plugin.
    // Static logging functions.
    protected static void log(boolean isDebugOnlyMsg, Level lvl, String msg) {
        if(isDebugOnlyMsg && StormWatch.getInstance().getDebug()) {
            StormWatch.getInstance().getLogger().log(lvl, "[DEBUG] " + msg);
        } else if(!isDebugOnlyMsg) {
            StormWatch.getInstance().getLogger().log(lvl, msg);
        }
    }
    protected static void log(boolean isDebugOnlyMsg, String msg) { StormWatch.log(isDebugOnlyMsg, Level.INFO, msg); }
    protected static void log(Exception ex, String additionalInfo) {
        StormWatch.log(ex);
        StormWatch.log(false, Level.WARNING, "Additional information: " + additionalInfo);
    }
    protected static void log(Exception ex) {
        if(StormWatch.instance.debug) { ex.printStackTrace(); }
        StormWatch.log(false, Level.WARNING, "Caught exception: " + ex.getMessage());
    }


    // Generic constructor.
    public StormWatch() { StormWatch.instance = this; }


    /**
     * Retrieves the plugin-wide single instance of the plugin itself.
     */
    public static StormWatch getInstance() { return StormWatch.instance; }
    /**
     * Retrieves the plugin-wide single instance of the Storm Manager class.
     */
    public static StormManager getStormManager() { return StormWatch.getInstance().stormManager; }
    /**
     * Retrieves the plugin-wide single instance of the Storm Config class.
     */
    public static StormConfig getStormConfig() { return StormWatch.getInstance().stormConfig; }
    /**
     * Retrieves the plugin-wide single instance of the Storm Chunk Manager class.
     */
    public static StormChunkManager getStormChunkManager() { return StormWatch.getInstance().stormChunkManager; }


    /**
     * Gets whether the plugin is running in debug mode (i.e. <em>verbose mode</em>).
     */
    public final boolean getDebug() { return this.debug; }
    /**
     * Gets whether a newly-spawned Storm sub-type that successfully fires a StormStartEvent will
     * log to the console.
     */
    public final boolean getLogOnNewStormEvent() { return this.logOnNewStormEvent; }
    /**
     * Returns a list of player names who should be exempt from ALL Storm types.
     */
    public final ArrayList<String> getGlobalExemptPlayers() { return this.globalExemptPlayers; }
    /**
     * Checks the exempt players list for the given player name, to see if they are exempt from Storm spawning.
     */
    public final boolean isExemptPlayer(String playerName) { return this.globalExemptPlayers.contains(playerName); }
    /**
     * Returns a list of world names that are exempt from Storm spawning.
     */
    public final ArrayList<String> getGlobalExemptWorlds() { return this.globalExemptWorlds; }
    /**
     * Returns whether the given world name is exempt from spawning any Storm events from the plugin.
     */
    public final boolean isExemptWorld(String worldName) { return this.globalExemptWorlds.contains(worldName); }
    /**
     * Gets all registered plugin Listener objects.
     * List of any and all Listener objects that have, or will be, registered through this plugin. This
     * is used at plugin-disable to force de-registration of Listener types that might be around still.
     */
    public final ArrayList<Listener> getRegisteredListeners() { return this.registeredListeners; }
    /**
     * Add a registered listener for this plugin onto the list to be unregistered in case the plugin disables.
     */
    protected final void addRegisteredListener(Listener l) { this.registeredListeners.add(l); }


    /**
     * Fired when the server enables the plugin.
     */
    @Override
    public final void onEnable() {
        // Set up the StormConfig instance and copy in the default configuration if needed.
        this.stormConfig = new StormConfig();
        this.stormChunkManager = new StormChunkManager();
        this.stormConfig.setDefaults(StormWatch.defaultConfig);
        try {
            this.debug = StormConfig.getConfigValue(BaseConfigurationKeyNames.DEBUG);
            if(this.debug) {
                StormWatch.log(false,"You are running the plugin in 'debug' mode. "
                        + "If this wasn't intended, you can turn this off in the configuration and reload the plugin.");
            }
        } catch (Exception ex) {
            StormWatch.log(false, Level.WARNING, "Couldn't get DEBUG configuration key; enabled debug by default.");
            this.debug = true;
        }
        if(StormConfig.getConfigValueNoThrow(BaseConfigurationKeyNames.LOG_ON_STORM_EVENT_START) == null) {
            StormWatch.log(false, "Enabling logging on new Storm events, by default.");
            this.logOnNewStormEvent = true;
        } else {
            this.logOnNewStormEvent = StormConfig.getConfigValueNoThrow(BaseConfigurationKeyNames.LOG_ON_STORM_EVENT_START);
        }
        if((this.globalExemptWorlds = StormConfig.getConfigValueNoThrow(BaseConfigurationKeyNames.GLOBAL_EXEMPT_WORLD_NAMES)) == null) {
            StormWatch.log(false,
                    "Did not find a value for globally exempt world names. Defaulting to NO GLOBALLY EXEMPT WORLDS.");
            this.globalExemptWorlds = new ArrayList<>();
        } else {
            StormWatch.log(false,
                    "Storm events globally exempted for World names:   {" + String.join("; ", this.globalExemptWorlds) + "}");
            if(this.debug) {
                StormWatch.log(true, "+ Storm events in the GLOBAL scope are ENABLED for worlds:");
                for(World w : this.getServer().getWorlds()) {
                    if(!this.isExemptWorld(w.getName())) { StormWatch.log(true, "+++ " + w.getName()); }
                }
            }
        }
        if((this.globalExemptPlayers = StormConfig.getConfigValueNoThrow(BaseConfigurationKeyNames.GLOBAL_EXEMPT_PLAYER_NAMES)) == null) {
            StormWatch.log(false,
                    "Did not find a value for globally exempt player names. Defaulting to NO GLOBAL PLAYER EXEMPTIONS.");
            this.globalExemptPlayers = new ArrayList<>();
        } else {
            StormWatch.log(false,
                    "Storm events globally exempted for Player names:   {" + String.join("; ", this.globalExemptPlayers) + "}");
        }

        // Set up the Storm Manager event handler and register it.
        this.stormManager = new StormManager();
        this.getServer().getPluginManager().registerEvents(this.stormManager, this);
        this.registeredListeners.add(this.stormManager);

        // Register a task to fire an event every TickRate ticks.
        this.tickTimerTask = new BukkitRunnable() {
            @Override
            public void run() {
                // Create and fire a custom MeteorEvent.
                var c = new StormTickEvent();
                Bukkit.getPluginManager().callEvent(c);
            }
        }.runTaskTimer(this, (StormWatch.TickRate*2L), StormWatch.TickRate); //delay of TickRate*2 before starting

        // Register the primary control command executor.
        try {
            Objects.requireNonNull(this.getCommand("stormgr"))
                    .setExecutor(new StormWatchCommandExecutor());
        } catch (Exception ex) {
            StormWatch.log(false, Level.WARNING,
            "Failed to register the 'stormgr' command executor. Commands will not be executable.");
            StormWatch.log(ex);
        }
    }


    /**
     * Fired on plugin disable (server stop or reload).
     */
    @Override
    public final void onDisable() {
        try {
            // Unload all loaded configurations.
            StormWatch.defaultConfigsSetForTypes.clear();
            // Unregister all event handlers.
            for(Listener l : this.getRegisteredListeners()) { HandlerList.unregisterAll(l); }
            // Cancel the "tick" event task.
            this.tickTimerTask.cancel();
            // Unload any ticketed chunks.
            this.stormChunkManager.unloadAllChunks();
        } catch (Exception ex) {
            StormWatch.log(false, Level.WARNING, "Problem disabling the plugin.");
            StormWatch.log(ex);
        }
    }

}
