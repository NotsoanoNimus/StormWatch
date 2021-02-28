package xyz.xmit.StormWatch;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired once every interval as specified by the TickRate value in StormWatch.
 * This timing mechanism is what is used to determine based on a randomized value
 * if the chance is met to start storms in certain worlds. This event does <em>not</em>
 * need to be cancellable.
 *
 * @see StormWatch
 */
public class StormTickEvent extends Event  {
    private static final HandlerList handlers = new HandlerList();
    // Constructor.
    public StormTickEvent() { }
    @Override
    public final HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
    @Override
    public final String getEventName() {
        return "StormTickEvent";
    }
}
