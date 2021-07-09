package xyz.xmit.StormWatch.storms;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.scheduler.BukkitRunnable;
import xyz.xmit.StormWatch.StormWatch;
import xyz.xmit.StormWatch.Storm;
import xyz.xmit.StormWatch.StormConfig;

import java.util.HashMap;
import java.util.logging.Level;


/**
 * Extends the Storm super-class to create a generic wave of streaking, glowing objects in the sky above
 * a player's head. This Storm event is intentionally <em>calm and harmless to the player</em> and is more
 * of a "plugin-internal" demonstration of the Storm API being used lightly.
 *
 * @see Storm
 */
public class StormStreak extends Storm {
    /**
     * The storm type's registered name.
     */
    public static final String TYPE_NAME = "streak";
    /**
     * Required configuration subkeys specific to this storm type, under the primary node: <strong>streak</strong>.
     * For a reference of the effects of each subkey, please consult the
     * <a href="https://xmit.xyz/spigot/StormWatch/manual.html" target="_blank">plugin configuration manual</a>.
     */
    public enum StormStreakConfigurationKeyNames implements StormConfig.ConfigKeySet {
        HEIGHT_ABOVE_PLAYER_RANGE("heightAbovePlayerRange"),
        STREAK_ENTITY_TYPE("streakEntityType");
        public final String label;
        StormStreakConfigurationKeyNames(String keyText) { this.label = keyText; }
        public final String getLabel() { return this.label; }
    }
    private static final HashMap<String, Object> defaultConfig = new HashMap<>() {{
            put(RequiredConfigurationKeyNames.FOLLOW_PLAYER.label, false); //this storm loads chunks so it can just stay stationary, really.
            put(RequiredConfigurationKeyNames.CHANCE.label, 0.050);
            put(RequiredConfigurationKeyNames.COOLDOWN_RANGE.label, new int[]{0, 1});
            put(RequiredConfigurationKeyNames.SPEED_RANGE.label, new double[]{0.7, 4.3});
            put(RequiredConfigurationKeyNames.WINDY_CHANCE.label, 0.010);
            put(RequiredConfigurationKeyNames.WINDY.label, true);
            put(RequiredConfigurationKeyNames.X_RANGE.label, new int[]{-120, 120});
            put(RequiredConfigurationKeyNames.Z_RANGE.label, new int[]{-120, 120});
            put(RequiredConfigurationKeyNames.HEIGHT_RANGE.label, new int[]{50, 70}); // These always spawn within a certain distance of the player height.
            put(RequiredConfigurationKeyNames.PITCH_RANGE.label, new int[]{-20, 5}); // Near-horizontal angle of incidence on all meteors.

            // STREAK-specific configuration.
            put(StormStreakConfigurationKeyNames.HEIGHT_ABOVE_PLAYER_RANGE.label, new int[]{70, 100}); //relative height above the player to spawn
            put(StormStreakConfigurationKeyNames.STREAK_ENTITY_TYPE.label, "SMALL_FIREBALL"); //entitytype to use
    }};


    private EntityType streakItemType; //type of entity spawned for the streak schedule


    public StormStreak() { super(StormStreak.TYPE_NAME, StormStreak.defaultConfig); }


    // GET methods.
    /**
     * Gets the configuration-set EntityType of the streak Entity spawns.
     */
    public final EntityType getStreakItemType() { return this.streakItemType; }


    @Override
    protected final boolean initializeStormTypeProperties() {
        Fireball testCast;
        try {
            this.streakItemType = EntityType.valueOf(
                    StormConfig.getConfigValue(this.typeName, StormStreakConfigurationKeyNames.STREAK_ENTITY_TYPE)
            );
            var randomSpawnLocation = this.getNewRelativeLocation(true, true, true);
            testCast = (Fireball)randomSpawnLocation.getWorld().spawnEntity(randomSpawnLocation, this.streakItemType);
        } catch (Exception ex) {
            this.log(Level.WARNING, "--- Skipping spawn of STREAK storm: can't get proper configuration values.");
            this.log(ex);
            return false;
        }
        // Hopefully remove the entity if it was spawned.
        try { testCast.remove(); } catch(Exception ex) { this.log(ex); }
        return true;
    }

    @Override
    protected final boolean stormSpecificConditionChecks() {
        return true;
    }

    @Override
    protected void setPropertiesFromCommand(String[] cmdArgs) { }

    @Override
    protected final Entity getNextEntity() {
        var spawnBase = this.getNewRelativeLocation(true, true, true);
        spawnBase.setYaw(this.getStormYaw());
        spawnBase.setPitch(this.getStormPitch());
        // Spawn the fireball and add it to the entities list.
        var x = (Fireball)spawnBase.getWorld().spawnEntity(spawnBase, this.streakItemType);
        x.getLocation().setYaw(this.getStormYaw());
        x.getLocation().setPitch(this.getStormPitch());
        //// These properties are immutable.
        x.setYield(0); x.setIsIncendiary(false); x.setGravity(false);
        x.setGlowing(true); x.setBounce(false); x.setSilent(true);
        x.setPersistent(false);
        // Set the trajectory of the entity and return it (since these should be tracked).
        x.setVelocity(x.getDirection().multiply(this.getNewSpeed()));
        // Schedule an event to despawn the meteor after a short duration (
        //   Ideally, this will attempt to prevent the HUGE LAG and pile-up of streak entities that
        //   have been seen in chunks that get unloaded while this storm type is running.
        this.addScheduledSpawnTask(
            new BukkitRunnable() {
                public void run() {
                    try { x.remove(); } catch (Exception ex) { if(StormWatch.getInstance().getDebug()) { log(ex); } }
                }
            }.runTaskLater(StormWatch.getInstance(), (long)(this.getRandomInt(1,4) * 20L)) //1 to 4 seconds (20 - 80 server ticks)
        );
        return x;
    }

    @Override
    protected final void doJustBeforeScheduling() {}

    @Override
    protected final void doJustAfterScheduling() {}

    @Override
    public final void doCleanupAfterStorm() {
        // EASTER EGG IDEA: SPAWN SOMETHING HIDDEN 600-1200 BLOCKS AWAY IN THE DIRECTION THE METEORS WERE GOING.


        this.destroySpawnedEntities();   //clean up the spawned entities on storm end
    }
}
