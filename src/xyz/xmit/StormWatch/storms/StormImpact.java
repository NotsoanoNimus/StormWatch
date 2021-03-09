package xyz.xmit.StormWatch.storms;

import net.minecraft.server.v1_16_R3.Tuple;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Biome;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import xyz.xmit.StormWatch.*;

import java.util.*;
import java.util.logging.Level;

/**
 * Extends the Storm super-class to create a highly-modular, single-impact meteor, which creates
 * (optionally) large explosions and block "splashes" upon contacting any block. Registers itself
 * as a Bukkit event listener while "alive" to handle its own contact/impact event.
 *
 * @see Storm
 * @see Listener
 */
public final class StormImpact extends Storm implements Listener {
    /**
     * The storm type's registered name.
     */
    public static final String TYPE_NAME = "impact";
    /**
     * Required configuration subkeys specific to this storm type, under the primary node: <strong>impact</strong>.
     * For a reference of the effects of each subkey, please consult the
     * <a href="https://xmit.xyz/spigot/StormWatch/manual.html" target="_blank">plugin configuration manual</a>.
     */
    public enum StormImpactConfigurationKeyNames implements StormConfig.ConfigKeySet {
        MYSTERY_ENABLED("splash.enabled"),
        MYSTERY_TYPES("splash.materialTypes"),
        MYSTERY_USE_NEARBY_TYPES("splash.intelligentMaterialTypes"),
        MYSTERY_AMOUNT_RANGE("splash.amountRangeInBlocks"),
        MYSTERY_VELOCITY_RANGE("splash.velocity.xzFactorRange"),
        MYSTERY_VERTICAL_IMPULSE_RANGE("splash.velocity.verticalImpulseMultiplierRange"),
        SPLASH_IMPULSE_PROPORTIONAL("splash.velocity.proportionalToMeteorSize"),
        WARNING_SOUND("storm.playsWarningSoundToTarget"),
        METEOR_DIAMETER_RANGE("meteor.diameterRangeInBlocks"),
        METEOR_HOLLOW("meteor.hollow"),
        METEOR_LEAVE_DIAMOND_BLOCK("meteor.leaveDiamondSurprise"),
        METEOR_MATERIALS("meteor.materialTypes"),
        METEOR_MATERIALS_MIXED("meteor.compositionIsMixed"),
        METEOR_EXPLOSION_YIELD_PROPORTIONAL("meteor.explosion.proportionalYield"),
        METEOR_EXPLOSION_DAMAGE_PROPORTIONAL("meteor.explosion.proportionalDamage");
        public final String label;
        StormImpactConfigurationKeyNames(String keyText) { this.label = keyText; }
        public final String getLabel() { return this.label; }
    }
    // Impacts: Large single meteors that create impact craters and optionally leave behind extra stuff.
    //   Create default configuration overrides and custom keys.
    private static final HashMap<String, Object> defaultConfig = new HashMap<>() {{
            //////////put(RequiredConfigurationKeyNames.DURATION_RANGE.label, new int[]{20, 25}); //20-25 seconds for the meteor to land
            // ^^ Making the duration too short will cause the Listener to unregister before the trackerBlock hits the ground.
            put(RequiredConfigurationKeyNames.FOLLOW_PLAYER.label, false);
            put(RequiredConfigurationKeyNames.CHANCE.label, 0.0011);
            put(RequiredConfigurationKeyNames.SPAWN_AMOUNT_RANGE.label, new int[]{0, 1});
            put(RequiredConfigurationKeyNames.SPAWN_RATE_RANGE.label, new int[]{0, 1});
            put(RequiredConfigurationKeyNames.COOLDOWN_RANGE.label, new int[]{600, 1800});
            // I've found it's pretty fun to make these spawn from the western sky hemisphere
            //   to make the "toward-the-player" movement more reliable.
            put(RequiredConfigurationKeyNames.X_RANGE.label, new int[]{-100, -50});
            put(RequiredConfigurationKeyNames.Z_RANGE.label, new int[]{-100, 100});

            // EXPLOSIVE conf keys
            putAll(Storm.defaultExplosiveConfiguration); //include all defaults
            // -- overrides of default
            put(ExplosiveConfigurationKeyNames.EXPLOSION_DAMAGE.label, 40.0);
            put(ExplosiveConfigurationKeyNames.EXPLOSION_YIELD.label, 20);
            put(ExplosiveConfigurationKeyNames.EXPLOSION_DAMAGE_RADIUS.label, 30);

            // IMPACT-specific conf keys
            put(StormImpactConfigurationKeyNames.MYSTERY_USE_NEARBY_TYPES.label, true); // use nearby ground-contact blocks in the "mystery" splash?
            // ^^ invalidates the MYSTERY_TYPES key value
            put(StormImpactConfigurationKeyNames.MYSTERY_ENABLED.label, true); //custom subclass config keys
            put(StormImpactConfigurationKeyNames.MYSTERY_TYPES.label,
                    new String[]{"OBSIDIAN", "NETHERRACK", "BLACKSTONE", "COAL_BLOCK", "BONE_BLOCK"});
            put(StormImpactConfigurationKeyNames.MYSTERY_AMOUNT_RANGE.label, new int[]{15, 45});
            put(StormImpactConfigurationKeyNames.MYSTERY_VELOCITY_RANGE.label, new double[]{-0.2, 1.75});
            put(StormImpactConfigurationKeyNames.MYSTERY_VERTICAL_IMPULSE_RANGE.label, new double[]{0.75, 2.75});
            put(StormImpactConfigurationKeyNames.SPLASH_IMPULSE_PROPORTIONAL.label, true);
            put(StormImpactConfigurationKeyNames.WARNING_SOUND.label, true);
            put(StormImpactConfigurationKeyNames.METEOR_DIAMETER_RANGE.label, new int[]{3, 8});
            put(StormImpactConfigurationKeyNames.METEOR_HOLLOW.label, true);
            put(StormImpactConfigurationKeyNames.METEOR_LEAVE_DIAMOND_BLOCK.label, true); // leaves a diamond block at the impact site if true
            put(StormImpactConfigurationKeyNames.METEOR_MATERIALS.label, new String[]{"GLOWSTONE", "OBSIDIAN"});
            put(StormImpactConfigurationKeyNames.METEOR_MATERIALS_MIXED.label, false);
            put(StormImpactConfigurationKeyNames.METEOR_EXPLOSION_YIELD_PROPORTIONAL.label, true);
            put(StormImpactConfigurationKeyNames.METEOR_EXPLOSION_DAMAGE_PROPORTIONAL.label, true);
    }};
    private static final class BiomesToMaterials {
        private final ArrayList<Biome> biomes; private final ArrayList<Material> equivMats;
        public BiomesToMaterials(ArrayList<Biome> b, ArrayList<Material> m) { this.biomes = b; this.equivMats = m; }
        //public final boolean hasBiome(Biome b) { return this.biomes.contains(b); }
        public final boolean getHasBiome(Biome b) { return this.biomes.contains(b); }
        public final ArrayList<Material> getMaterials() { return this.equivMats; }
    }
    private static final ArrayList<BiomesToMaterials> splashMaterialsMapping = new ArrayList<>() {{
        add(new BiomesToMaterials(
                new ArrayList<>(Arrays.asList(Biome.FOREST, Biome.BIRCH_FOREST, Biome.TALL_BIRCH_HILLS, Biome.TALL_BIRCH_FOREST, Biome.BIRCH_FOREST_HILLS,
                        Biome.DARK_FOREST_HILLS, Biome.DARK_FOREST, Biome.CRIMSON_FOREST, Biome.FLOWER_FOREST, Biome.WARPED_FOREST, Biome.WOODED_HILLS,
                        Biome.PLAINS, Biome.SUNFLOWER_PLAINS, Biome.SAVANNA, Biome.SAVANNA_PLATEAU, Biome.SHATTERED_SAVANNA_PLATEAU, Biome.SHATTERED_SAVANNA,
                        Biome.JUNGLE, Biome.JUNGLE_EDGE, Biome.JUNGLE_HILLS, Biome.BAMBOO_JUNGLE_HILLS, Biome.BAMBOO_JUNGLE, Biome.MODIFIED_JUNGLE,
                        Biome.MODIFIED_JUNGLE_EDGE, Biome.RIVER, Biome.MOUNTAIN_EDGE, Biome.MOUNTAIN_EDGE, Biome.MOUNTAINS, Biome.TAIGA_MOUNTAINS,
                        Biome.GRAVELLY_MOUNTAINS, Biome.WOODED_MOUNTAINS, Biome.MODIFIED_GRAVELLY_MOUNTAINS, Biome.TAIGA, Biome.TAIGA_HILLS,
                        Biome.GIANT_SPRUCE_TAIGA, Biome.GIANT_TREE_TAIGA, Biome.GIANT_TREE_TAIGA_HILLS, Biome.GIANT_SPRUCE_TAIGA_HILLS)),
                new ArrayList<>(Arrays.asList(Material.DIRT, Material.STONE, Material.COAL_ORE,
                        Material.CLAY, Material.GRAVEL, Material.SAND, Material.OBSIDIAN, Material.GLOWSTONE))
        ));
        add(new BiomesToMaterials(
                new ArrayList<>(Arrays.asList(Biome.SWAMP, Biome.SWAMP_HILLS)),
                new ArrayList<>(Arrays.asList(Material.DIRT, Material.STONE, Material.CLAY, Material.GRAVEL, Material.SAND, Material.SLIME_BLOCK))
        ));
        add(new BiomesToMaterials(
                new ArrayList<>(Arrays.asList(Biome.MUSHROOM_FIELD_SHORE, Biome.MUSHROOM_FIELDS)),
                new ArrayList<>(Arrays.asList(Material.DIRT, Material.STONE, Material.MYCELIUM, Material.BROWN_MUSHROOM_BLOCK, Material.RED_MUSHROOM_BLOCK))
        ));
        add(new BiomesToMaterials(
                new ArrayList<>(Arrays.asList(Biome.END_BARRENS, Biome.END_HIGHLANDS, Biome.END_MIDLANDS, Biome.THE_END, Biome.SMALL_END_ISLANDS)),
                new ArrayList<>(Arrays.asList(Material.END_STONE))
        ));
        add(new BiomesToMaterials(
                new ArrayList<>(Arrays.asList(Biome.DESERT, Biome.DESERT_HILLS, Biome.DESERT_LAKES, Biome.BEACH)),
                new ArrayList<>(Arrays.asList(Material.SAND, Material.SANDSTONE, Material.STONE))
        ));
        add(new BiomesToMaterials(
                new ArrayList<>(Arrays.asList(Biome.BADLANDS, Biome.BADLANDS_PLATEAU, Biome.MODIFIED_BADLANDS_PLATEAU,
                        Biome.MODIFIED_WOODED_BADLANDS_PLATEAU, Biome.ERODED_BADLANDS, Biome.WOODED_BADLANDS_PLATEAU)),
                new ArrayList<>(Arrays.asList(Material.RED_SAND, Material.RED_SANDSTONE, Material.TERRACOTTA, Material.STONE))
        ));
        add(new BiomesToMaterials(
                new ArrayList<>(Arrays.asList(Biome.DEEP_FROZEN_OCEAN, Biome.ICE_SPIKES, Biome.FROZEN_OCEAN, Biome.FROZEN_OCEAN, Biome.SNOWY_TAIGA,
                        Biome.SNOWY_TUNDRA, Biome.SNOWY_BEACH, Biome.SNOWY_MOUNTAINS, Biome.SNOWY_TAIGA_MOUNTAINS, Biome.SNOWY_TAIGA_HILLS, Biome.FROZEN_RIVER)),
                new ArrayList<>(Arrays.asList(Material.STONE, Material.SAND, Material.ICE, Material.ICE, Material.ICE,
                        Material.SNOW_BLOCK, Material.SNOW_BLOCK, Material.SNOW_BLOCK))
        ));
        add(new BiomesToMaterials(
                new ArrayList<>(Arrays.asList(Biome.OCEAN, Biome.COLD_OCEAN, Biome.DEEP_COLD_OCEAN, Biome.DEEP_LUKEWARM_OCEAN, Biome.DEEP_OCEAN,
                        Biome.DEEP_WARM_OCEAN, Biome.LUKEWARM_OCEAN, Biome.WARM_OCEAN)),
                new ArrayList<>(Arrays.asList(Material.SAND, Material.COBBLESTONE, Material.CLAY,
                        Material.GRAVEL, Material.DIORITE, Material.ANDESITE, Material.GRANITE))
        ));
    }};
    /**
     * Gets the mappings used on a per-biome basis to determine the "intelligent" materials to use in the
     * event of a splash effect. If the <em>intelligentMaterialTypes</em> subkey is set in the config,
     * this list is used on every impact to add on the corresponding material types to the list of textures.
     */
    public static ArrayList<BiomesToMaterials> getSplashMaterialsMapping() { return StormImpact.splashMaterialsMapping; }

    // Config-based variables.
    private boolean mysteryEnabled, meteorHollow, leaveDiamondBlock,
            warningSound, mysterySplashNearbyTypes, meteorCompositionMixed,
            splashImpulseProportional, yieldProportional, damageProportional;
    private int mysteryBlocksAmount;
    private Tuple<Double,Double> mysteryBlocksVelocityFactorRange ,mysteryVerticalImpulseRange;
    private final ArrayList<Material> mysteryTypes = new ArrayList<>();
    private final ArrayList<Material> meteorCompositionMaterials = new ArrayList<>();
    private int meteorDiameter;
    private double meteorSpeed;
    // Tracker variables.
    //// Use a single block to get the location of in the final callback for the after-effects.
    private FallingBlock trackerBlock;
    private Material trackerBlockMaterial; //get its material too




    // GET methods, in case they're wanted by another plugin upon seeing the StormStartEvent's instance.
    //   These should include javadoc information for those who desire to create implementations.
    /**
     * Gets whether the meteor's "splash" effect is enabled upon impact.
     */
    public final boolean isMysteryEnabled() { return this.mysteryEnabled; }
    /**
     * Gets whether a diamond block will be left over at the site of the meteor impact. Note that
     * this diamond will never place itself in AIR or BEDROCK materials, and as such will replace
     * the first block encountered underneath the single "tracker" block.
     * (see: {@link #getTrackerBlock()})
     */
    public final boolean isLeaveDiamondBlock() { return this.leaveDiamondBlock; }
    /**
     * Gets whether the spawned impact meteor is a hollow sphere or a filled sphere.
     */
    public final boolean isMeteorHollow() { return this.meteorHollow; }
    /**
     * Gets whether the predetermined warning sound will play to the target player of the impact.
     */
    public final boolean isPlaysWarningSound() { return this.warningSound; }
    /**
     * Gets whether the meteor's splash effect is composed of
     */
    public final boolean isMysterySplashNearbyTypes() { return this.mysterySplashNearbyTypes; }
    /**
     * Gets whether the meteor's composition is composed of a random assortment of predefined block Material
     * types (see: {@link #getMeteorCompositionMaterials()}).
     */
    public final boolean isMeteorCompositionMixed() { return this.meteorCompositionMixed; }
    /**
     * Gets whether the meteor's splash effect velocity impulse is proportional in speed to the diameter of the
     * generated meteor sphere (see: {@link #getMeteorDiameter()}).
     */
    public final boolean isSplashImpulseProportional() { return this.splashImpulseProportional; }
    /**
     * Gets whether the meteor's explosion yield, if explosion is enabled, is proportional to the diameter of the
     * generated meteor sphere (see: {@link #getMeteorDiameter()}).
     */
    public final boolean isYieldProportional() { return this.yieldProportional; }
    /**
     * Gets whether the damage of the meteor's impact, if explosion is enabled, is proportional to the diameter
     * of the generated meteor sphere (see: {@link #getMeteorDiameter()}).
     */
    public final boolean isDamageProportional() { return this.damageProportional; }
    /**
     * Gets the inbound velocity multiplier of the falling meteor.
     */
    public final double getMeteorSpeed() { return this.meteorSpeed; }
    /**
     * Gets the predetermined amount of splash blocks that will erupt from the top of the meteor on impact.
     */
    public final int getMysteryBlocksAmount () { return this.mysteryBlocksAmount; }
    /**
     * Gets the diameter, not radius, of the generated meteor sphere as created at the chosen spawn location.
     */
    public final int getMeteorDiameter() { return this.meteorDiameter; }
    /**
     * Gets the entity representing a block on the surface of the sphere, as a tracker. When this block is
     * caught in the {@link #onBlockPhysics(BlockPhysicsEvent)} event handler, the splash effect of the
     * impact storm is generated, if enabled. This block is an important part of firing that half of the
     * impact storm.
     */
    public final FallingBlock getTrackerBlock() { return this.trackerBlock; }
    /**
     * Gets the material from the Tracker Block. This is necessary to store separately based on the changing
     * nature of a FallingBlock entity; getting the value at a later time would be inefficient and impractical.
     */
    public final Material getTrackerBlockMaterial() { return this.trackerBlockMaterial; }
    /**
     * Get the types of blocks that are able to erupt from the meteor's impact site, if enabled. These types
     * will <strong>not matter</strong> if {@link #isMysterySplashNearbyTypes()} returns <em>true</em>.
     */
    public final ArrayList<Material> getMysteryTypes() { return this.mysteryTypes; }
    /**
     * Gets the possible composition types of the impact meteor, whether or not those types are mixed
     * when actually spawning the meteor.
     */
    public final ArrayList<Material> getMeteorCompositionMaterials() { return this.meteorCompositionMaterials; }
    /**
     * Gets the range of possible values for the splash effect's horizontal (i.e. <em>X-Z</em>) speed multipliers.
     */
    public final Tuple<Double,Double> getMysteryBlocksVelocityFactorRange() { return this.mysteryBlocksVelocityFactorRange; }
    /**
     * Gets the range of possible values for the splash effect's vertical speed multiplier.
     */
    public final Tuple<Double,Double> getMysteryVerticalImpulseRange() { return this.mysteryVerticalImpulseRange; }




    // Class constructor.
    public StormImpact() { super(StormImpact.TYPE_NAME, StormImpact.defaultConfig); }


    // Populate IMPACT object-specific configuration and validate.
    protected final boolean initializeStormTypeProperties() {
        // Custom properties
        Tuple<Integer,Integer> diameterRange, mysteryBlocksRange;
        var materialTypes = new ArrayList<String>();
        var compositionTypes = new ArrayList<String>();
        try {
            diameterRange = StormConfig.getIntegerRange(this.typeName, StormImpactConfigurationKeyNames.METEOR_DIAMETER_RANGE);
            mysteryBlocksRange = StormConfig.getIntegerRange(this.typeName, StormImpactConfigurationKeyNames.MYSTERY_AMOUNT_RANGE);
            this.mysteryBlocksVelocityFactorRange = StormConfig.getDoubleRange(this.typeName, StormImpactConfigurationKeyNames.MYSTERY_VELOCITY_RANGE);
            this.mysteryVerticalImpulseRange = StormConfig.getDoubleRange(this.typeName, StormImpactConfigurationKeyNames.MYSTERY_VERTICAL_IMPULSE_RANGE);
            this.mysteryEnabled = StormConfig.getConfigValue(this.typeName, StormImpactConfigurationKeyNames.MYSTERY_ENABLED);
            this.meteorHollow = StormConfig.getConfigValue(this.typeName, StormImpactConfigurationKeyNames.METEOR_HOLLOW);
            this.warningSound = StormConfig.getConfigValue(this.typeName, StormImpactConfigurationKeyNames.WARNING_SOUND);
            this.meteorCompositionMixed = StormConfig.getConfigValue(this.typeName, StormImpactConfigurationKeyNames.METEOR_MATERIALS_MIXED);
            this.mysterySplashNearbyTypes = StormConfig.getConfigValue(this.typeName, StormImpactConfigurationKeyNames.MYSTERY_USE_NEARBY_TYPES);
            this.leaveDiamondBlock = StormConfig.getConfigValue(this.typeName, StormImpactConfigurationKeyNames.METEOR_LEAVE_DIAMOND_BLOCK);
            this.splashImpulseProportional = StormConfig.getConfigValue(this.typeName, StormImpactConfigurationKeyNames.SPLASH_IMPULSE_PROPORTIONAL);
            this.yieldProportional = StormConfig.getConfigValue(this.typeName, StormImpactConfigurationKeyNames.METEOR_EXPLOSION_YIELD_PROPORTIONAL);
            this.damageProportional = StormConfig.getConfigValue(this.typeName, StormImpactConfigurationKeyNames.METEOR_EXPLOSION_DAMAGE_PROPORTIONAL);
            //// get meteor composition types
            compositionTypes = StormConfig.getConfigValue(this.typeName, StormImpactConfigurationKeyNames.METEOR_MATERIALS);
            for(String s : compositionTypes) { this.meteorCompositionMaterials.add(Material.valueOf(s)); }
            //// get material types for splash
            materialTypes = StormConfig.getConfigValue(this.typeName, StormImpactConfigurationKeyNames.MYSTERY_TYPES);
            for(String s : materialTypes) { this.mysteryTypes.add(Material.valueOf(s)); }
        } catch (Exception e) {
            this.log(Level.WARNING, "~~~ Skipping spawn of IMPACT meteor: can't get proper configuration values.");
            this.log(e);
            return false;
        }
        // Set custom values (from whichever source: super or above)
        this.meteorSpeed = this.getNewSpeed();
        this.meteorDiameter = this.getRandomInt(diameterRange);
        this.mysteryBlocksAmount = this.getRandomInt(mysteryBlocksRange);
        this.trackerBlock = null;
        // Forcibly set the storm duration, because this is a one-off, non-scheduled event.
        //   Give the meteor long enough to touch the ground below.
        this.setStormDurationTicks(30 * 20); //30sec * 20ticks/sedc = 600 ticks

        return true;
    }


    // IMPACT doesn't really have any storm-specific conditions (yet). Could add something like a height check?
    @Override
    protected final boolean stormSpecificConditionChecks() {
        return true;
    }

    @Override
    protected void setPropertiesFromCommand(String[] cmdArgs) { }

    @Override
    protected final void doJustBeforeScheduling() {
        this.setSchedulingDisabled(true);   //disable entity spawn schedule
    }

    @Override
    protected final void doJustAfterScheduling() {
        // Read the config and populate object variables before spawning the meteor.
        //   Only spawns the impact if the IMPACT object-specific config is valid.
        if(!this.isCancelled()) { this.getNextEntity(); }
        // Register 'this' as an event Listener while the storm is active.
        this.registerAsListener(this);
    }

    // IMPACT types are expected to spawn a single sphere in the sky and throw it down near the player.
    //   Thus, this function should be designed to be called only one time.
    @Override
    protected final Entity getNextEntity() {
        // Get a location with relative X-Z coordinates and somewhere in an absolute height range.
        Location spawnBase = this.getNewRelativeLocation(true, false, true);
        // Forcibly set the meteor yaw to send the meteor toward the player. This changes the "storm yaw".
        spawnBase.setDirection(spawnBase.toVector().subtract(this.getTargetPlayer().getLocation().toVector()));
        this.setStormYaw((int)spawnBase.getYaw());

        // Start creating the meteor with the provided diameter.
        //   Check if the sphere is meant to be hollow as well...
        int meteorRadius = this.meteorDiameter / 2;
        var spawnLocations = new ArrayList<Location>();
        for(int t = -meteorRadius; t <= meteorRadius; t++) {
            for(int e = -meteorRadius; e <= meteorRadius; e++) {
                for(int h = -meteorRadius; h <= meteorRadius; h++) {
                    ////// Use the equation for a sphere's radius to find all valid points within and on the surface of a sphere.
                    // x^2 + y^2 + z^2 = r^2, therefore: r = sqrt(x^2 + y^2 + z^2).
                    // So to trim the edges of the sphere and create a radius (using example of radius=5):
                    //   t,e,h = (0,2,0) ---> r = sqrt(0^2 + 2^2 + 0^2) = 2, which is INSIDE of the radius of 5.
                    //   t,e,h = (4,4,4) ---> r = sqrt(4^2 + 4^2 + 4^2) = sqrt(48) = 6.92, which is OUTSIDE the radius of 5.
                    int spherePoint = (int) Math.floor(Math.sqrt(Math.pow(t, 2) + Math.pow(e, 2) + Math.pow(h, 2)));
                    if ((!this.meteorHollow && spherePoint > meteorRadius)
                            || (this.meteorHollow && spherePoint != meteorRadius)) {
                        continue;
                    } else {
                        Location meteorLocation = spawnBase.clone().add(t, e, h);
                        meteorLocation.setPitch(this.getStormPitch());
                        meteorLocation.setYaw(this.getStormYaw());
                        spawnLocations.add(meteorLocation);
                    }
                }
            }
        }

        // Map out the materials to be used in the meteor. If the composition isn't varied, it can be set outside the loop easily.
        Material spawnType = this.meteorCompositionMaterials.get( this.getRandomInt(0, this.meteorCompositionMaterials.size()) );
        // Spawn the meteor now.
        for(Location loc : spawnLocations) {
            if(this.meteorCompositionMixed) {
                // If the composition is mixed, get a new material type each iteration/spawn.
                spawnType = this.meteorCompositionMaterials.get( this.getRandomInt(0, this.meteorCompositionMaterials.size()) );
            }
            // Set the individual block parameters after spawning. If the server is slow, or if the meteor
            //   has a large radius, this operation could (1) take some time, and/or (2) cause the
            //   blocks to sort of rain down in a stream rather than as a complete unit.
            FallingBlock block = this.getTargetPlayer().getWorld().spawnFallingBlock( loc, spawnType.createBlockData() );
            block.setVelocity(loc.getDirection().multiply(this.meteorSpeed));
            // Keeping this for fun. :)
            ////this.setEntityVelocity( block, this.getRandomDouble(this.speedRange) );
            block.setGravity(true); block.setPersistent(true); block.setInvulnerable(true);
            // Set the tracker block to the first block created in the set.
            if(this.trackerBlock == null) {
                this.trackerBlock = block;
                this.trackerBlockMaterial = block.getBlockData().getMaterial();
            }
        }

        // Play the warning sound to the player, if enabled.
        if(this.warningSound) {
            this.getTargetPlayer().getWorld().playSound(this.getTargetPlayer().getLocation(),
                    Sound.ENTITY_BLAZE_AMBIENT, 0.8f, 2.5f);
        }

        // Nothing needs to be returned for this event type.
        return null;
    }

    @Override
    public final void doCleanupAfterStorm() {
        // Unregister self as an active plugin event listener
        try { HandlerList.unregisterAll(this); } catch (Exception e) { this.log(e); }
    }


    /**
     * Custom, impact-specific registered event handler that's fired on any block physics
     * to track the trackerBlock. This is important to know when the TrackerBlock makes
     * contact with the ground, or really any surface, even if only a 1x1 platform.
     *
     * @param e The BlockPhysicsEvent to handle.
     * @see Listener
     * @see BlockPhysicsEvent
     */
    @EventHandler
    public final void onBlockPhysics(BlockPhysicsEvent e) {
        if(this.trackerBlock != null && this.trackerBlock.isOnGround()
                && e.getSourceBlock().getBlockData().matches(this.trackerBlock.getBlockData())) {
            // Immediately clear the tracker after capturing a copy.
            FallingBlock block = this.trackerBlock;
            this.trackerBlock = null;
            try {
                float explosionYield = this.isYieldProportional() ? (float)(this.meteorDiameter * 2.8) : this.getExplosionYield();
                if(this.getExplosionEnabled()) {
                    // Create the explosion.
                    block.getWorld().createExplosion(
                            block.getLocation(),
                            explosionYield,
                            this.getSetsFires(),
                            this.getBreaksBlocks()
                    );
                    // Damage all nearby entities, as applicable.
                    for(Entity nearbyMob : block.getNearbyEntities(this.getExplosionDamageRadius(),
                            this.getExplosionDamageRadius(), this.getExplosionDamageRadius())) {
                        if(nearbyMob instanceof Damageable) {
                            ((Damageable)nearbyMob).damage(
                                this.isDamageProportional()
                                    ? (float)(this.getMeteorDiameter() * 3.6)
                                    : this.getExplosionDamage()
                            );
                        }
                    }
                }
            } catch (Exception ex) {
                this.log(Level.WARNING, "Unable to create IMPACT explosion or damage nearby entities.");
                this.log(ex);
            }

            // Do mystery splashing if enabled.
            if(this.mysteryEnabled) {
                try {
                    this.debugLog("Spawning " + this.getMysteryBlocksAmount() + " splash blocks on IMPACT event.");
                    // Get the fallen block location on the ground and set the splash spawn up to the
                    //   meteor diameter (+3 padding) above the ground location.
                    Location mysteryBaseSpawn = block.getLocation().clone();
                    mysteryBaseSpawn.setY(mysteryBaseSpawn.getY() + meteorDiameter + 3);
                    // Create a list of all randomized textures from the MYSTERY_TYPES array, or nearby the impact site if enabled.
                    var texturesList = new ArrayList<Material>();
                    if(!this.mysterySplashNearbyTypes) {
                        for(int i = 0; i < this.mysteryBlocksAmount; i++) {
                            texturesList.add(this.mysteryTypes.get(this.rng.nextInt(this.mysteryTypes.size())));
                        }
                    } else {
                        var nearbyMaterials = new ArrayList<Material>();
                        // Check for the biome across all registered types. If it doesn't exist, default to the DIRT, SAND, STONE materials list.
                        for(BiomesToMaterials bToM : StormImpact.splashMaterialsMapping) {
                            if(bToM.getHasBiome(e.getBlock().getBiome())) { nearbyMaterials = bToM.getMaterials(); break; }
                        }
                        if(nearbyMaterials == null || nearbyMaterials.isEmpty()) {
                            this.debugLog("--- Couldn't find impact biome. Meteor splash for IMPACT selected default materials!");
                            nearbyMaterials = new ArrayList<>(Arrays.asList(Material.STONE, Material.DIRT, Material.SAND));
                        }
                        //// Always add on the material the tracker block is made of, regardless of biome.
                        ////   If the meteor is a mixed composition, add all of its types to the list.
                        if(this.meteorCompositionMixed) {
                            nearbyMaterials.addAll(this.meteorCompositionMaterials);
                        } else {
                            try { nearbyMaterials.add(this.trackerBlockMaterial); } catch (Exception ex) { this.log(ex); }
                        }
                        // Finally randomize the textures list for the amount of splash blocks that will be spawning.
                        for(int i = 0; i < this.mysteryBlocksAmount; i++) {
                            texturesList.add(nearbyMaterials.get(this.rng.nextInt(nearbyMaterials.size())));
                        }
                    }
                    // For each texture type, spawn a new block from the base location and randomize its trajectory based on the impact's inbound yaw.
                    for (Material mysteryMaterial : texturesList) {
                        int minBaseYaw = this.getStormYaw() - 60;
                        var t = mysteryBaseSpawn.clone();
                        t.setYaw((this.getRandomInt(minBaseYaw, minBaseYaw+120) + 180) % 360);
                        t.setDirection(t.getDirection());
                        FallingBlock newMysteryBlock = block.getWorld().spawnFallingBlock(t, mysteryMaterial.createBlockData());
                        double velocityFactor = this.getRandomDouble(this.mysteryBlocksVelocityFactorRange);
                        double verticalImpulseFactor = this.getRandomDouble(this.mysteryVerticalImpulseRange);
                        newMysteryBlock.setVelocity(t.getDirection().
                                multiply(
                                    this.isSplashImpulseProportional()
                                        ? (velocityFactor * Math.max(this.getMeteorDiameter()/5, 1))
                                        : velocityFactor
                                )
                                .setY(
                                    this.isSplashImpulseProportional()
                                        ? (verticalImpulseFactor * Math.max(this.getMeteorDiameter()/5, 1))
                                        : verticalImpulseFactor
                                )
                        );
                    }
                } catch (Exception ex) {
                    this.log(Level.WARNING, "Unable to create mystery blocks splash effect from IMPACT event.");
                    this.log(ex);
                }
            }

            // If set, replace the impact tracker block with a block of diamond.
            if(this.leaveDiamondBlock) {
                Location l = e.getBlock().getLocation(); //get the impact location
                l.setY(l.getY() - this.getRandomInt(2, 10)); //start 2 to 10 blocks below the impact tracker block
                // While the next block in the loop is equal to BEDROCK or AIR types, continue searching downward until block Y-1.
                int start = 0; int max = 400; //prevent infinite loop scenarios
                while((l.getBlock().getBlockData().getMaterial() == Material.BEDROCK
                        || l.getBlock().getBlockData().getMaterial() == Material.AIR)
                        && l.getY() > 1 && start < max) { l.setY(l.getY()-1); start++; }
                if(l.getY() <= 1) {
                    this.debugLog("Failed to place a diamond block under the meteor impact zone for IMPACT.");
                } else {
                    try {
                        l.getBlock().setBlockData(StormWatch.getInstance().getServer().createBlockData(Material.DIAMOND_BLOCK));
                        this.debugLog("Buried diamond block at Location: " + l.getBlock().getLocation());
                    } catch (Exception ex) { this.debugLog("Was ready to spawn diamond block, but cancelled."); this.log(ex); }
                }
            }

            // Prematurely end the event.
            // Actually don't do this, because the blocks need time to fall down after the impact.
            ////this.cancelScheduledSpawns();

        }
    }
}
