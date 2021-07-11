package xyz.xmit.StormWatch.storms;

import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.Listener;
import xyz.xmit.StormWatch.Storm;
import xyz.xmit.StormWatch.StormConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Extends the Storm super-class to create a generic pattern of falling fireballs from
 * the sky. This is by far the most simple example of a Storm extension that could be
 * possible which still has a noticeable effect to the player in-game.
 *
 * @see Storm
 */
public final class StormShower extends Storm implements Listener {
    /**
     * The storm type's registered name.
     */
    public static final String TYPE_NAME = "shower";

    public enum StormShowerConfigurationKeyNames implements StormConfig.ConfigKeySet {
        SHOWER_GLOWING_FIREBALLS("entities.glowing");
        public final String label;
        StormShowerConfigurationKeyNames(String keyText) { this.label = keyText; }
        public final String getLabel() { return this.label; }
    }
    private static final HashMap<String, Object> defaultConfig = new HashMap<>() {{
        // Inherits all "Storm" default required keys and leaves them be.
        // Uses explosive conf keys
        putAll(Storm.defaultExplosiveConfiguration);
        // Meteors do not glow by default.
        put(StormShowerConfigurationKeyNames.SHOWER_GLOWING_FIREBALLS.label, false);
    }};

    private boolean isGlowingFireballs;


    public StormShower() {
        super(StormShower.TYPE_NAME, StormShower.defaultConfig);
    }


    public final boolean isGlowingFireballs() { return this.isGlowingFireballs; }
    public final void setGlowingFireballs(boolean isGlowing) { this.isGlowingFireballs = isGlowing; }


    @Override
    protected final boolean initializeStormTypeProperties() {
        try {
            this.isGlowingFireballs = StormConfig.getConfigValue(this.typeName, StormShowerConfigurationKeyNames.SHOWER_GLOWING_FIREBALLS);
        } catch(Exception ex) {
            this.log(Level.WARNING, "~~~ Skipping spawn of Shower type: can't get proper configuration values.");
            this.log(ex);
            return false;
        }
        return true;
    }

    @Override
    protected final boolean stormSpecificConditionChecks() { return true; }

    @Override
    protected void setPropertiesFromCommand() {
        // TODO: Abstract this out into the parent class. This stuff is going to be passed around everywhere.
        //   But Storms can't just be dismissed when not given extras since some will use this method well even without extra params.
        if(this.getCommandParameters() == null || this.getCommandParameters().length == 0) {
            this.debugLog("+++++ Received no additional command parameters. Nothing extra to do.");
            return;
        }
        var cmdArgs = new ArrayList<String>();
        this.debugLog("+++ Setting properties from Impact cast command.");
        // Converts all given params to lower-case.
        for(String s : this.getCommandParameters()) { cmdArgs.add(s.toLowerCase(Locale.ROOT)); }
        if(cmdArgs.contains("glow")) { this.setGlowingFireballs(true); }
    }

    @Override
    protected final Entity getNextEntity() {
        var spawnBase = this.getNewRelativeLocation(true, false, true);
        // Spawn the entity.
        Fireball x;
        try {
            x = (Fireball)Objects.requireNonNull(spawnBase.getWorld()).spawnEntity(spawnBase, EntityType.FIREBALL);
        } catch(Exception ex) { return null; }
        x.getLocation().setPitch(this.getStormPitch()); x.getLocation().setYaw(this.getStormYaw());
        x.setVelocity(x.getDirection().multiply(this.getNewSpeed()));
        // A lot of the SHOWER type properties are immutable, similar to STREAK events.
        try {
            x.setYield(this.getBreaksBlocks() ? this.getExplosionYield() : 0);
            x.setIsIncendiary(this.getSetsFires());
            x.setGravity(true);
            x.setBounce(false);
            x.setSilent(!this.getExplosionEnabled());  // If true, then this should be FALSE (so exp are NOT silent)
            x.setGlowing(this.isGlowingFireballs());
        } catch (Exception ex) {
            this.log(ex, "Failed to keep newly-spawned fireball.");
            x.remove();
            return null;
        }
        // On each spawn, there's a chance to spawn in some falling obsidian.
        if(this.getRandomDouble(0, 1.0) < 0.015) {
            FallingBlock newObsidian = spawnBase.getWorld().spawnFallingBlock(spawnBase, Material.OBSIDIAN.createBlockData());
            newObsidian.setVelocity(spawnBase.getDirection().multiply(this.getNewSpeed()));
            newObsidian.setGravity(true); newObsidian.setPersistent(true);
        }
        // Return the object.
        return x;
    }

    @Override
    protected final void doJustBeforeScheduling() {}

    @Override
    protected final void doJustAfterScheduling() {}

    @Override
    public final void doCleanupAfterStorm() { this.destroySpawnedEntities(); }   //clean up
}
