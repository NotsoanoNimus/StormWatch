package xyz.xmit.StormWatch;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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
    // Tick timer task.
    private BukkitTask tickTimerTask;
    // Debug flag. Config-specified.
    private boolean debug;
    // Top-level configuration variables for the plugin.
    private enum BaseConfigurationKeyNames implements StormConfig.ConfigKeySet {
        DEBUG("debug");
        private final String label;
        BaseConfigurationKeyNames(String keyText) { this.label = keyText; }
        public final String getLabel() { return this.label; }
    }
    // Defines the default configuration for the top-level YML scope.
    private static final HashMap<String, Object> defaultConfig = new HashMap<>() {{
        put(BaseConfigurationKeyNames.DEBUG.label, false);
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


        // Generic constructor.
    public StormWatch() { StormWatch.instance = this; }


    /**
     * Gets whether the plugin is running in debug mode (i.e. <em>verbose mode</em>).
     */
    public final boolean getDebug() { return this.debug; }
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
        this.stormConfig.setDefaults(StormWatch.defaultConfig);
        try {
            this.debug = new StormConfig.ConfigValue<Boolean>().get(BaseConfigurationKeyNames.DEBUG);
            if(this.debug) {
                StormWatch.log(false,"You are running the plugin in 'debug' mode. "
                        + "If this wasn't intended, you can turn this off via the configuration and reload the plugin.");
            }
        } catch (Exception ex) {
            StormWatch.log(false, Level.WARNING, "Couldn't get DEBUG configuration key; defaulted to enabled debug.");
            this.debug = true;
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
                StormTickEvent c = new StormTickEvent();
                Bukkit.getPluginManager().callEvent(c);
            }
        }.runTaskTimer(this, (StormWatch.TickRate*2L), StormWatch.TickRate); //delay of TickRate*2 before starting
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
            for(Listener l : this.registeredListeners) { HandlerList.unregisterAll(l); }
            // Cancel the "tick" event task.
            this.tickTimerTask.cancel();
            // Unload any ticketed chunks.
            for(World w : this.getServer().getWorlds()) { StormWatch.unloadTicketedChunks(w); }
        } catch (Exception ex) {
            StormWatch.log(false, Level.WARNING, "Problem disabling the plugin.");
            StormWatch.log(ex);
        }
    }


    /**
     * Fired when a command registered to this plugin is entered.
     */
    @Override
    public final boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Make sure a player sent the command, and that they have permission to do so, before parsing.
        if(sender instanceof Player) {
            Player p = (Player)sender;
            return true;
        }
        return false;
    }



    // Chunk (un)loading functions.
    //// THESE WILL BE REPLACED SOON BY A CHUNK MANAGER
    public static void loadChunksNear(Player p, int chunksDiameter) throws Exception {
        StormWatch.loadChunksNear(p.getLocation(), chunksDiameter);
    }
    public static void loadChunksNear(Location loc, int chunksDiameter) throws Exception {
        int chunkX = loc.getChunk().getX();
        int chunkZ = loc.getChunk().getZ();
        for(int i = -(chunksDiameter/2); i < (chunksDiameter/2); i++) {
            for(int o = -(chunksDiameter/2); o < (chunksDiameter/2); o++) {
                Objects.requireNonNull(loc.getWorld())
                        .getChunkAt(chunkX+i, chunkZ+o).addPluginChunkTicket(StormWatch.instance);
            }
        }
        StormWatch.log(true, "Loaded chunks at ("+chunkX+","+chunkZ+"), diameter of "+chunksDiameter);
    }
    public static void unloadTicketedChunks(Location loc) throws Exception {
        StormWatch.unloadTicketedChunks(Objects.requireNonNull(loc.getWorld())); }
    public static void unloadTicketedChunks(World w) throws Exception {
        if(w == null) { return; }
        Collection<Chunk> impactZones = w.getPluginChunkTickets().getOrDefault(StormWatch.instance, null);
        if(impactZones == null) { return; }
        for(Chunk zone : impactZones) {
            zone.removePluginChunkTicket(StormWatch.instance);   //unload the chunk
        }
        StormWatch.log(true, "Unloaded ticketed chunks for world: " + w.getName());
    }
}
