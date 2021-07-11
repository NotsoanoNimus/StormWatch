package xyz.xmit.StormWatch;

import net.minecraft.util.Tuple;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.logging.Level;


/**
 * The primary configuration manager for the StormWatch plugin. Uses a limited
 * set of classes and methods to wrap the work of accessing the spigot config
 * into an easy-to-use abstraction.
 *
 * @see #getConfigValue(String, String)
 */
public final class StormConfig {
    /**
     * Set on method construction to hold the initial StormWatch plugin configuration.
     */
    private FileConfiguration config;

    public StormConfig() { this.config = StormWatch.getInstance().getConfig(); }

    /**
     * Used for all configuration key-name indexing on a per-Storm basis, and also for
     * the Storm super-class itself.
     *
     * @see Storm
     */
    public interface ConfigKeySet { String getLabel(); }


    /**
     * Gets the active FileConfiguration used for the StormWatch instance, assigned to a local variable at
     * the time of plugin enablement.
     *
     * @return The StormConfig instance's active configuration file.
     */
    public final FileConfiguration getConfigFile() { return this.config; }


    /**
     * See: {@link #getConfigValueNoThrow(String, ConfigKeySet)}
     */
    public static <T> T getConfigValueNoThrow(ConfigKeySet subKeyNode) {
        return StormConfig.getConfigValueNoThrow("", subKeyNode);
    }
    /**
     * Gets a configuration node's value, and includes a try-catch inside the method to capture failing queries.
     * This is useful for returning a NULL and generating console logging for when a configuration node's access
     * fails, and absolves the need for the calling code to wrap the config query in a try-catch statement.
     * See: {@link #getConfigValue(String, String)}
     */
    public static <T> T getConfigValueNoThrow(String rootNode, ConfigKeySet subKeyNode) {
        if(rootNode.isEmpty()) {
            try {
                return StormConfig.getConfigValue(subKeyNode);
            } catch (Exception ex) {
                StormWatch.log(false, "Failed to get configuration node: " + subKeyNode.getLabel());
                StormWatch.log(ex);
                return null;
            }
        } else {
            try {
                return StormConfig.getConfigValue(rootNode, subKeyNode);
            } catch (Exception ex) {
                StormWatch.log(false,
                        "Failed to get configuration node: " + rootNode + "." + subKeyNode.getLabel());
                StormWatch.log(ex);
                return null;
            }
        }
    }


    /**
     * See: {@link #getConfigValue(String, String)}
     */
    public static <T> T getConfigValue(String rootKey, ConfigKeySet subNodeKey) throws Exception {
        return StormConfig.getConfigValue(rootKey, subNodeKey.getLabel());
    }
    /**
     * See: {@link #getConfigValue(String, String)}
     */
    public static <T> T getConfigValue(ConfigKeySet baseKey) throws Exception {
        return StormConfig.getConfigValue("", baseKey.getLabel());
    }
    /**
     * Attempts to get a configuration value of type &lt;T&gt; from the plugin's CONFIG.YML file.
     *
     * @param rootKey The primary root node of the config tree, typically the TYPE_NAME of a registered Storm type.
     * @param subNodeKey The sub-node under the root/base key to access.
     * @return A value of type T from the configuration file, if such a value exists for the node.
     * @throws Exception If the referenced configuration value is not set, the caller must be notified.
     */
    public static <T> T getConfigValue(String rootKey, String subNodeKey) throws Exception {
        String targetNode = rootKey.isEmpty() ? subNodeKey : (rootKey + "." + subNodeKey);
        try {
            @SuppressWarnings("unchecked")
            //T val = (T)StormWatch.getStormConfig().config.get(targetNode);
            T val = (T)StormWatch.getInstance().getConfig().get(targetNode);
            return val;
        } catch (Exception ex) {
            String message = "There was an issue getting configuration node: " + targetNode;
            StormWatch.log(false, Level.WARNING, message);
            throw new Exception(message);
        }
    }


    /**
     * Reloads the <code>config</code> variable for the instance. Should be called after a
     * save-configuration operation usually.
     */
    protected final void reloadConfig() {
        StormWatch.instance.reloadConfig();
        this.config = StormWatch.instance.getConfig();
    }
    /**
     * Used to set any default key/value pairs that aren't already set in the configuration.
     *
     * @param defaultPairs A set of default configuration key-value pairs to be set if they don't
     *                     already exist within the configuration.
     */
    protected final void setDefaults(Map<String, Object> defaultPairs) {
        for(String nodeName : defaultPairs.keySet()) {
            if(!this.config.contains(nodeName)) {
                this.config.set(nodeName, defaultPairs.get(nodeName));
            }
        }
        StormWatch.instance.saveConfig();
        this.reloadConfig();
    }


    /**
     * Allows a value to be set directly for a configuration node.
     * @param rootKey The Storm type-name (or other root node) to access.
     * @param subNodeKey The configuration sub-node of the root node.
     * @param value The value to try setting.
     * @return Whether or not the configuration node was set.
     */
    protected final boolean setConfigValue(String rootKey, ConfigKeySet subNodeKey, Object value) {
        String targetNode = rootKey.isEmpty() ? subNodeKey.getLabel() : (rootKey + "." + subNodeKey.getLabel());
        try {
            StormWatch.log(true,
                    "Attempting to set config node '" + targetNode + "' ==> '" + value.toString() + "'");
            this.config.set(targetNode, value);
        } catch(Exception ex) {
            StormWatch.log(false, Level.WARNING,
                    "Failed to set configuration node '" + targetNode + "' to value '" + value.toString() + "'");
            StormWatch.log(ex);
            return false;
        }
        StormWatch.instance.saveConfig();
        this.reloadConfig();
        return true;
    }


    /**
     * From notes, I believe this was my attempt at dissolving issues with Integer vs Double confusions,
     * as well as easily abstracting all operations for extracting details from an input range.
     * <em><u>Not implemented at this time.</u></em> Please ignore.
     *
     * @param <T> Type of Number extension to instantiate the RangedValue type with.
     * @deprecated
     */
    // TODO: Revisit this and get it working/tested.
    public static final class RangedValue<T extends Number> {
        private final Random rand = new Random();
        private final Tuple<T,T> valueRange;
        private T currentValue;
        public RangedValue(String typeName, ConfigKeySet targetNode) throws Exception {
            this.valueRange = StormConfig.getValueRange(typeName, targetNode);
            this.currentValue = (T)this.getSomeValue();
        }
        public final Double getSomeValue() {
            return (this.rand.nextDouble() * (this.valueRange.b().doubleValue() - this.valueRange.a().doubleValue()))
                    + this.valueRange.a().doubleValue();
        }
        public final Tuple<T,T> getValueRange() { return this.valueRange; }
        public final T getCurrentValue() { return this.currentValue; }
        public final void setNewCurrentValue() { this.currentValue = (T)this.getSomeValue(); }
    }


    /**
     * Return a configured range of values of type <em>T</em> as an iterable tuple object.
     * This seems to abstract the need to defined the Integer vs Double conflicts, but needs testing first.
     *
     * @param typeName The storm's TYPE_NAME field, representing the root node in the configuration file.
     * @param subKey The sub-key of the typeName (primary configuration node).
     * @return A tuple of Integer objects representing an inclusive range between the two Integers as bounds.
     * @throws Exception Raises any captured Exception objects higher.
     */
    public static <T> Tuple<T,T> getValueRange(String typeName, ConfigKeySet subKey) throws Exception {
        ArrayList<T> x = StormConfig.getConfigValue(typeName, subKey);
        return new Tuple<>(x.get(0), x.get(1));
    }

    /**
     * Return a configured range of integers as an iterable tuple object.
     *
     * @param typeName The storm's TYPE_NAME field, representing the root node in the configuration file.
     * @param subKey The sub-key of the typeName (primary configuration node).
     * @return A tuple of Integer objects representing an inclusive range between the two Integers as bounds.
     * @throws Exception Raises any captured Exception objects higher.
     */
    public static Tuple<Integer,Integer> getIntegerRange(String typeName, ConfigKeySet subKey) throws Exception {
        ArrayList<Integer> x = StormConfig.getConfigValue(typeName, subKey);
        return new Tuple<>(x.get(0), x.get(1));
    }
    /**
     * Return a configured range of doubles as an iterable tuple object.
     *
     * @param typeName The storm's TYPE_NAME field, representing the root node in the configuration file.
     * @param subKey The sub-key of the typeName (primary configuration node).
     * @return A tuple of Double objects representing an inclusive range between the two Doubles as bounds.
     * @throws Exception Raises any captured Exception objects higher.
     */
    public static Tuple<Double,Double> getDoubleRange(String typeName, ConfigKeySet subKey) throws Exception {
        ArrayList<Double> x = StormConfig.getConfigValue(typeName, subKey);
        return new Tuple<>(x.get(0), x.get(1));
    }
}
