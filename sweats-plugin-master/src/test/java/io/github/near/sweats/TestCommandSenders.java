package io.github.near.sweats;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class TestCommandSenders {

    private TestCommandSenders() {}

    public static CommandSender console(final boolean permission, final List<Component> messages) {
        return proxy(CommandSender.class, new Handler("Console", permission, 0, messages, null, false));
    }

    public static Player player(
            final String name, final int ping, final boolean permission, final List<Component> messages) {
        return proxy(Player.class, new Handler(name, permission, ping, messages, null, false));
    }

    /** A player that also reports a position, for commands that place or look at things. */
    public static Player player(
            final String name,
            final int ping,
            final boolean permission,
            final List<Component> messages,
            final Location location) {
        return proxy(Player.class, new Handler(name, permission, ping, messages, location, false));
    }

    /** An operator player, for staff-only paths such as vanish. */
    public static Player opPlayer(final String name, final List<Component> messages) {
        return proxy(Player.class, new Handler(name, true, 0, messages, null, true));
    }

    /** A player whose {@code canSee} always returns true, for teleport-request tests. */
    public static Player seer(final String name, final List<Component> messages) {
        return proxy(Player.class, new Handler(name, true, 1, messages, null, false, true));
    }

    /** A player who can see others but has no special permissions. */
    public static Player visiblePlayer(final String name, final List<Component> messages) {
        return proxy(Player.class, new Handler(name, false, 0, messages, null, false, true));
    }

    /** A player for whom {@code canSee} is always false, as if the target were vanished. */
    public static Player blindPlayer(final String name, final List<Component> messages) {
        return proxy(Player.class, new Handler(name, false, 0, messages, null, false, false));
    }

    /** A player with a specific UUID for rank-based authorization tests. */
    public static Player playerWithUuid(
            final String name, final UUID uuid, final List<Component> messages) {
        return proxy(Player.class, new HandlerWithUuid(name, uuid, false, 0, messages, null, false));
    }

    /** A player with a specific UUID and OP status. */
    public static Player opPlayerWithUuid(
            final String name, final UUID uuid, final List<Component> messages) {
        return proxy(Player.class, new HandlerWithUuid(name, uuid, true, 0, messages, null, true));
    }

    private static final class HandlerWithUuid implements InvocationHandler {
        private final String name;
        private final UUID uuid;
        private final boolean permission;
        private final int ping;
        private final Location location;
        private final List<Component> messages;
        private final boolean op;

        private HandlerWithUuid(
                final String name,
                final UUID uuid,
                final boolean permission,
                final int ping,
                final List<Component> messages,
                final Location location,
                final boolean op) {
            this.name = name;
            this.uuid = uuid;
            this.permission = permission;
            this.ping = ping;
            this.messages = messages;
            this.location = location;
            this.op = op;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            return switch (method.getName()) {
                case "getName" -> name;
                case "getUniqueId" -> uuid;
                case "hasPermission" -> permission;
                case "isOp" -> op;
                case "canSee" -> true;
                case "getPing" -> ping;
                case "getLocation" -> location;
                case "isOnline" -> true;
                case "sendMessage" -> {
                    if (args != null && args.length == 1 && args[0] instanceof final Component message) {
                        messages.add(message);
                    }
                    yield null;
                }
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> name;
                default -> defaultValue(method.getReturnType());
            };
        }

        private static Object defaultValue(final Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == char.class) {
                return '\0';
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0.0F;
            }
            if (type == double.class) {
                return 0.0D;
            }
            return null;
        }
    }

    public static String plain(final Component component) {
        final StringBuilder out = new StringBuilder();
        collect(component, out);
        return out.toString();
    }

    public static TextColor colorOf(final Component component, final String content) {
        if (component instanceof final TextComponent text && text.content().equals(content)) {
            return text.color();
        }
        for (final Component child : component.children()) {
            final TextColor color = colorOf(child, content);
            if (color != null) {
                return color;
            }
        }
        return null;
    }

    public static boolean hasDecoration(final Component component, final String content, final net.kyori.adventure.text.format.TextDecoration decoration) {
        if (component instanceof final TextComponent text && text.content().equals(content)) {
            return text.decorations().get(decoration) == net.kyori.adventure.text.format.TextDecoration.State.TRUE;
        }
        for (final Component child : component.children()) {
            if (hasDecoration(child, content, decoration)) {
                return true;
            }
        }
        return false;
    }

    private static void collect(final Component component, final StringBuilder out) {
        if (component instanceof final TextComponent text) {
            out.append(text.content());
        }
        for (final Component child : component.children()) {
            collect(child, out);
        }
    }

    private static <T> T proxy(final Class<T> type, final InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(TestCommandSenders.class.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static final class Handler implements InvocationHandler {

        private final String name;
        private final boolean permission;
        private final int ping;
        private final Location location;
        private final List<Component> messages;
        private final boolean op;
        private final boolean sees;

        private Handler(
                final String name,
                final boolean permission,
                final int ping,
                final List<Component> messages,
                final Location location,
                final boolean op) {
            this(name, permission, ping, messages, location, op, false);
        }

        private Handler(
                final String name,
                final boolean permission,
                final int ping,
                final List<Component> messages,
                final Location location,
                final boolean op,
                final boolean sees) {
            this.name = name;
            this.permission = permission;
            this.ping = ping;
            this.messages = messages;
            this.location = location;
            this.op = op;
            this.sees = sees;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) {
            return switch (method.getName()) {
                case "getName" -> name;
                case "getUniqueId" -> UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
                case "hasPermission" -> permission;
                case "isOp" -> op;
                case "canSee" -> sees;
                case "getPing" -> ping;
                case "getLocation" -> location;
                case "isOnline" -> true;
                case "sendMessage" -> {
                    if (args != null && args.length == 1 && args[0] instanceof final Component message) {
                        messages.add(message);
                    }
                    yield null;
                }
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> name;
                default -> defaultValue(method.getReturnType());
            };
        }

        private static Object defaultValue(final Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == char.class) {
                return '\0';
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0.0F;
            }
            if (type == double.class) {
                return 0.0D;
            }
            return null;
        }
    }
}