package xyz.xmit.StormWatch;

import net.minecraft.server.v1_16_R3.Tuple;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.logging.Level;


/**
 * The primary configuration manager for the StormWatch plugin. Uses a limited
 * set of classes and methods to wrap the work of accessing the spigot config
 * into an easy-to-use abstraction.
 *
 * @see ConfigValue
 */
public final class StormConfig {
    /**
     * Set on method construction to hold the initial StormWatch plugin configuration.
     */
    private FileConfiguration config;
    // Constructor.
    public StormConfig() { this.config = StormWatch.instance.getConfig(); }

    /**
     * Used for all configuration keyname indexing on a per-Storm basis, and also for
     * the Storm super-class itself.
     *
     * @see Storm
     */
    public interface ConfigKeySet { public abstract String getLabel(); }

    /**
     * Generic class used to abstract read-access to the plugin's CONFIG.YML file. General practice to use
     * this class is to declare an instance and immediately access the get method. For example,
     * <code>String t = new ConfigValue&lt;String&gt;().get(configNode);</code>
     *
     * @param <T> The type expected to be returned by an access to the Object's get method,
     *          for the target configuration node.
     */
    @SuppressWarnings("unchecked")
    public static final class ConfigValue<T> {
        /**
         * See: {@link #get(String, String)}
         */
        public T get(String stormKey, ConfigKeySet subKey) throws Exception {
            return this.get(stormKey,subKey.getLabel()); }
        /**
         * See: {@link #get(String, String)}
         */
        public T get(ConfigKeySet baseKey) throws Exception {
            return this.get("", baseKey); }
        /**
         * Attempts to get a configuration value of type &lt;T&gt; from the plugin's CONFIG.YML file.
         *
         * @param stormKey The primary root node of the config tree, typically the TYPE_NAME
         *                 of a registered Storm type.
         * @param subNodeName The sub-node under the stormKey to access.
         * @return A value of type T from the configuration file, if such a value exists for the node.
         * @throws Exception If the referenced configuration value is not set, the caller must be notified.
         */
        public T get(String stormKey, String subNodeName) throws Exception {
            String targetNode = stormKey.isEmpty() ? subNodeName : stormKey + "." + subNodeName;
            try {
                Object val = StormWatch.getStormConfig().config.get(targetNode);
                return (T)val;
            } catch (Exception ex) {
                String message = "There was an issue getting configuration node: " + targetNode;
                StormWatch.log(false, Level.WARNING, message);
                throw new Exception(message);
            }
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
    protected final void setDefaults(Map<String,Object> defaultPairs) {
        for(String nodeName : defaultPairs.keySet()) {
            if(!this.config.contains(nodeName)) {
                this.config.set(nodeName, defaultPairs.get(nodeName));
                //this.config.addDefault(nodeName, defaultPairs.get(nodeName));
            }
        }
        StormWatch.instance.saveConfig();
        this.reloadConfig();
    }



    /**
     * Return a configured range of integers as an interable tuple object.
     *
     * @param typeName The storm's TYPE_NAME field, representing the root node in the configuration file.
     * @param subKey The subkey of the typeName (primary configuration node).
     * @return A tuple of Integer objects representing an inclusive range between the two Integers as bounds.
     * @throws Exception
     */
    public static Tuple<Integer,Integer> getIntegerRange(String typeName, ConfigKeySet subKey) throws Exception {
        var x = new ConfigValue<ArrayList<Integer>>().get(typeName, subKey);
        return new Tuple<>(x.get(0), x.get(1));
    }
    /**
     * Return a configured range of doubles as an interable tuple object.
     *
     * @param typeName The storm's TYPE_NAME field, representing the root node in the configuration file.
     * @param subKey The subkey of the typeName (primary configuration node).
     * @return A tuple of Double objects representing an inclusive range between the two Doubles as bounds.
     * @throws Exception
     */
    public static Tuple<Double,Double> getDoubleRange(String typeName, ConfigKeySet subKey) throws Exception {
        var x = new ConfigValue<ArrayList<Double>>().get(typeName, subKey);
        return new Tuple<>(x.get(0), x.get(1));
    }
}
