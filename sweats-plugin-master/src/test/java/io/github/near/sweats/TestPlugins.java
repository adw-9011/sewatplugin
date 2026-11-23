package io.github.near.sweats;

import java.lang.reflect.Proxy;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class TestPlugins {

    private TestPlugins() {}

    public static Plugin plugin() {
        return plugin(new YamlConfiguration());
    }

    /** A plugin proxy whose {@code getConfig()} returns {@code config}. */
    public static Plugin plugin(final FileConfiguration config) {
        return (Plugin) Proxy.newProxyInstance(
                TestPlugins.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConfig" -> config;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "TestPlugin";
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** A minimal JavaPlugin implementation for testing. */
    public static class TestJavaPlugin extends JavaPlugin {
        private final FileConfiguration config;

        public TestJavaPlugin() {
            this(new YamlConfiguration());
        }

        public TestJavaPlugin(final FileConfiguration config) {
            this.config = config;
        }

        @Override
        public FileConfiguration getConfig() {
            return config;
        }
    }

    /** A JavaPlugin instance for testing. */
    public static JavaPlugin javaPlugin() {
        return new TestJavaPlugin();
    }

    /** A JavaPlugin instance with the given config. */
    public static JavaPlugin javaPlugin(final FileConfiguration config) {
        return new TestJavaPlugin(config);
    }

    private static Object defaultValue(final Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
