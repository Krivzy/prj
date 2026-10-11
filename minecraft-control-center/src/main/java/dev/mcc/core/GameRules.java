package dev.mcc.core;

import org.bukkit.GameRule;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/** Spigot 26.3 declares GameRule as an interface; Paper 26.3 still declares a class. */
public final class GameRules {
    private GameRules() {}
    private static final class BukkitAccess {
        static final RuntimeAccess INSTANCE = new RuntimeAccess(GameRule.class, World.class);
    }
    public static NamespacedKey key(Object rule) {
        if (rule instanceof Keyed keyed) return keyed.getKey();
        throw new IllegalArgumentException("Game rule has no public registry key");
    }
    public static Class<?> type(Object rule) { return BukkitAccess.INSTANCE.type(rule); }
    public static Object value(World world, Object rule) { return BukkitAccess.INSTANCE.value(world, rule); }
    public static boolean set(World world, Object rule, Object value) { return BukkitAccess.INSTANCE.set(world, rule, value); }

    /** Uses the runtime public API types; no private reflection or class/interface invocation opcode. */
    static final class RuntimeAccess {
        private final Class<?> ruleType;
        private final Method type, get, set;
        RuntimeAccess(Class<?> ruleType, Class<?> worldType) {
            this.ruleType = Objects.requireNonNull(ruleType);
            try {
                type = ruleType.getMethod("getType");
                get = worldType.getMethod("getGameRuleValue", ruleType);
                set = worldType.getMethod("setGameRule", ruleType, Object.class);
            } catch (ReflectiveOperationException error) {
                throw new UnsupportedOperationException("Server public gamerule API is unavailable", error);
            }
        }
        Class<?> type(Object rule) {
            validate(rule);
            Object value = invoke(type, rule);
            if (value instanceof Class<?> result) return result;
            throw new IllegalStateException("Server gamerule type is invalid");
        }
        Object value(Object world, Object rule) { validate(rule); return invoke(get, world, rule); }
        boolean set(Object world, Object rule, Object value) {
            validate(rule);
            if (value == null || !type(rule).isInstance(value)) throw new IllegalArgumentException("Incorrect gamerule value type");
            Object result = invoke(set, world, rule, value);
            if (result instanceof Boolean accepted) return accepted;
            throw new IllegalStateException("Server gamerule update result is invalid");
        }
        private void validate(Object rule) { if (!ruleType.isInstance(rule)) throw new IllegalArgumentException("Unknown runtime gamerule"); }
        private static Object invoke(Method method, Object target, Object... arguments) {
            try { return method.invoke(target, arguments); }
            catch (InvocationTargetException error) {
                if (error.getCause() instanceof RuntimeException cause) throw cause;
                if (error.getCause() instanceof Error cause) throw cause;
                throw new IllegalStateException("Server gamerule operation failed", error.getCause());
            } catch (ReflectiveOperationException error) { throw new UnsupportedOperationException("Server public gamerule invocation failed", error); }
        }
    }
}
