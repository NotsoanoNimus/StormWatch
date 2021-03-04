package xyz.xmit.StormWatch;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;


/**
 * Class to manage all Storm instance chunk-loading, so that the end event of one Storm instance
 * will not unload the chunks being used simultaneously by other Storm instances that are still
 * in the ongoing state. The class merely consists of an internal list that's manipulated as
 * StormStartEvent events are captured in the StormManager Listener object.
 *
 * @see Storm
 * @see StormManager
 * @see StormEndEvent
 */
public class StormChunkManager {
    private final HashMap<UUID, ArrayList<Chunk>> stormsToChunkStrings = new HashMap<>();


    /**
     * Checks whether the given unique Storm ID has any chunks loaded.
     *
     * @param stormId The unique ID of the Storm instance to check.
     * @return Whether the given Storm ID has any chunks loaded.
     */
    public final boolean hasChunksLoaded(UUID stormId) {
        if(this.stormsToChunkStrings.containsKey(stormId)) {
            if(this.stormsToChunkStrings.get(stormId) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     *
     * @param targetChunk
     * @return
     */
    public final boolean isChunkLoadedElsewhere(Chunk targetChunk) {
        for(ArrayList<Chunk> list : this.stormsToChunkStrings.values()) {
            for(Chunk c : list) {
                if(c.equals(targetChunk)) {
                    StormWatch.log(true,
                    "Chunk at ("+targetChunk.getX()+","+targetChunk.getZ()+") is loaded elsewhere.");
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * Run when a Storm event is ending, if chunk-loading was set, to unload any ticketed Chunk objects that
     * aren't shared by other Storm instances.
     *
     * @param stormId The unique ID of the Storm instance whose Chunks should be unloaded.
     */
    public final void unloadStormChunks(UUID stormId) {
        if(this.hasChunksLoaded(stormId)) {
            ArrayList<Chunk> stormsChunks = this.stormsToChunkStrings.get(stormId);
            this.stormsToChunkStrings.remove(stormId); //immediately unregister all loaded chunks for this storm
            for(Chunk c : stormsChunks) {
                if(!this.isChunkLoadedElsewhere(c)) {
                    StormWatch.log(true, "Unloaded Chunk at: ("+c.getX()+","+c.getZ()+").");
                    c.removePluginChunkTicket(StormWatch.getInstance());
                }
            }
        }
    }

    /**
     * @see #loadChunksNear(Location, int, UUID)
     */
    public final void loadChunksNear(Player p, int chunksDiameter, UUID stormId) throws Exception {
        this.loadChunksNear(p.getLocation(), chunksDiameter, stormId);
    }
    /**
     * Load a set of chunks in a "diameter" given by parameter. The actual coverage of the loaded area stretches from the provided
     * Location value in the associated World, out 1/2 the <em>chunksDiameter</em> value in each direction. This draws a "square" shape
     * of loaded chunks, with the provided Location at the center.
     *
     * @param loc World and Location at the center of the set of Chunks to load.
     * @param chunksDiameter The "diameter" of a square to load; cut this value in half to get the amount of chunks, from the center Location out to the edge in one direction, that are loaded.
     * @param stormId The unique ID of the Storm instance whose chunks should be loaded.
     * @throws Exception This exception will only ever be raised if for some reason the Location object given doesn't return a valid World.
     */
    public final void loadChunksNear(Location loc, int chunksDiameter, UUID stormId) throws Exception {
        int chunkX = loc.getChunk().getX();
        int chunkZ = loc.getChunk().getZ();
        ArrayList<Chunk> taggedChunksList = new ArrayList<>();
        if(chunksDiameter < 2) {
            loc.getChunk().addPluginChunkTicket(StormWatch.getInstance());
            taggedChunksList.add(loc.getChunk());
        } else {
            for (int i = -(chunksDiameter / 2); i < (chunksDiameter / 2); i++) {
                for (int o = -(chunksDiameter / 2); o < (chunksDiameter / 2); o++) {
                    Chunk loadedChunk = Objects.requireNonNull(loc.getWorld()).getChunkAt(chunkX + i, chunkZ + o);
                    taggedChunksList.add(loadedChunk);
                    // Only attempt to add a Chunk ticket if the Chunk isn't already loaded elsewhere.
                    if(!this.isChunkLoadedElsewhere(loadedChunk)) {
                        loadedChunk.addPluginChunkTicket(StormWatch.getInstance());
                    }
                }
            }
        }
        this.stormsToChunkStrings.put(stormId, taggedChunksList);
        StormWatch.log(true,
            "Storm " + stormId + ", loaded chunks at ("+chunkX+","+chunkZ+"), " +
                    (chunksDiameter < 2 ? "single chunk" : "diameter of "+chunksDiameter));
    }


    /**
     * Unloads all loaded/tagged plugin chunks.
     */
    protected void unloadAllChunks() {
        for(ArrayList<Chunk> list : this.stormsToChunkStrings.values()) {
            for(Chunk c : list) {
                c.removePluginChunkTicket(StormWatch.getInstance());
            }
        }
    }
}
