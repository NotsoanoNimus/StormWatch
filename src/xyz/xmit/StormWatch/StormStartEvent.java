package xyz.xmit.StormWatch;

import org.bukkit.World;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.logging.Level;

/**
 * Used to signal that a storm is scheduled to be started and should have its conditions checked.
 *
 * @see Storm
 */
public class StormStartEvent extends Event implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private boolean isCancelled;

    private final World world;
    private final Storm instance;

    public StormStartEvent(World w, Storm instance) {
        this.world = w; this.instance = instance;
        if(w == null || instance == null) {
            StormWatch.log(false, Level.WARNING,
                    "A storm event tried to start but received invalid parameters (world, instance)"
                        + "; cancelling.\nIs the storm type set up properly and registered?");
            this.setCancelled(true);
            return;
        }
        if(StormWatch.getInstance().getLogOnNewStormEvent()) {
            StormWatch.log(false,
                "- Started a new storm of type '" + instance.getName() + "'.");
            StormWatch.log(false,
                    "--- Target Player: " + instance.getTargetPlayer().getDisplayName());
            StormWatch.log(false,
                    "--- World: " + instance.getTargetPlayer().getWorld().getName());
            StormWatch.log(false,
                instance.isCooldownEnabled() && !instance.isCalledByCommand()
                    ? "--- Cooldown: " + instance.getInstanceCooldown()
                    : "--- No cooldown enabled for this type.");
        }
        StormWatch.log(true,
                "- Started storm event: " + instance.getName() + " /// ID: " + instance.getStormId());
    }

    /**
     * Gets the world in which the storm is requesting to spawn.
     */
    public final World getWorld() { return this.world; }
    /**
     * Gets the instance of the storm requesting to spawn.
     */
    public final Storm getInstance() { return this.instance; }

    @Override
    public final boolean isCancelled() { return this.isCancelled; }
    @Override
    public final void setCancelled(boolean cancel) { this.isCancelled = cancel; }
    @Override
    public final String getEventName() { return "StormStartEvent"; }
    @Override
    public final HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}
