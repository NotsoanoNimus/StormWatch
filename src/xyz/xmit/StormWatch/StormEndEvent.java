package xyz.xmit.StormWatch;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Used to signal that a storm is supposed to end and clean up via a callback method.
 *
 * @see Storm
 * @see xyz.xmit.StormWatch.StormManager.StormCallback
 */
public final class StormEndEvent extends Event implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private boolean isCancelled = false;

    private final UUID uniqueId;
    private final Storm instance;

    public StormEndEvent(UUID stormId, StormManager.StormCallback callback, Storm inst) {
        this.uniqueId = stormId; this.instance = inst;
        if(stormId == null) {
            StormWatch.log(false, Level.WARNING,
            "A storm event tried to fire an 'End' event but had no unique ID; cancelling."
                + "\nIs the storm type configured properly?");
            this.setCancelled(true);
            return;
        }
        callback.doCleanupAfterStorm();
        StormWatch.log(true, "- Ending storm event: " + stormId.toString());
    }

    /**
     * Gets the ending storm's unique ID.
     */
    public final UUID getStormId() { return this.uniqueId; }
    /**
     * Gets the instance of the ending storm type.
     */
    public final Storm getInstance() { return this.instance; }

    @Override
    public final boolean isCancelled() { return this.isCancelled; }
    @Override
    public final void setCancelled(boolean cancel) { this.isCancelled = cancel; }
    @Override
    public final String getEventName() { return "StormEndEvent"; }
    @Override
    public final HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}
