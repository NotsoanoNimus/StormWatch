package xyz.xmit.StormWatch.storms;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.Listener;
import xyz.xmit.StormWatch.StormWatch;
import xyz.xmit.StormWatch.Storm;

import java.util.HashMap;

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
    private static final HashMap<String, Object> defaultConfig = new HashMap<>() {{
        // Explosive conf keys
        putAll(Storm.defaultExplosiveConfiguration);
    }};


    //private final UUID instanceId = UUID.randomUUID();
    public StormShower() {
        super(StormShower.TYPE_NAME, StormShower.defaultConfig);
    }


    @Override
    protected final boolean initializeStormTypeProperties() {
        return true;
    }

    @Override
    protected final boolean stormSpecificConditionChecks() {
        return true;
    }

    @Override
    protected final Entity getNextEntity() {
        Location spawnBase = this.getNewRelativeLocation(true, false, true);
        // Spawn the entity.
        Fireball x = (Fireball)spawnBase.getWorld().spawnEntity(spawnBase, EntityType.FIREBALL);
        x.getLocation().setPitch(this.getStormPitch()); x.getLocation().setYaw(this.getStormYaw());
        x.setVelocity(x.getDirection().multiply(this.getNewSpeed()));
        // A lot of the SHOWER type properties are immutable, similar to STREAK events.
        try {
            x.setYield(this.getBreaksBlocks() ? this.getExplosionYield() : 0);
            x.setIsIncendiary(this.getSetsFires());
            x.setGravity(true);
            x.setBounce(false);
            x.setSilent(false);
            x.setGlowing(false);
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
    protected final void doJustBeforeScheduling() {
        try { StormWatch.loadChunksNear(this.getTargetPlayer(), 12); }
        catch (Exception ex) { this.log(ex); }}

    @Override
    protected final void doJustAfterScheduling() {}

    @Override
    public final void doCleanupAfterStorm() {
        this.destroySpawnedEntities(); //clean up
    }
}
