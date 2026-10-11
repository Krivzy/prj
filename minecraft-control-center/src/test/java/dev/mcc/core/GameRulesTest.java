package dev.mcc.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameRulesTest {
    public abstract static class ClassRule { public abstract Class<?> getType(); }
    public interface InterfaceRule { Class<?> getType(); }
    public static final class BooleanRule extends ClassRule { public Class<?> getType() { return Boolean.class; } }
    public static final class IntegerRule implements InterfaceRule { public Class<?> getType() { return Integer.class; } }
    public static final class ClassWorld {
        Object value = true;
        public Object getGameRuleValue(ClassRule rule) { return value; }
        public boolean setGameRule(ClassRule rule, Object value) { this.value = value; return true; }
    }
    public interface InterfaceWorld {
        Object getGameRuleValue(InterfaceRule rule);
        boolean setGameRule(InterfaceRule rule, Object value);
    }
    public static final class IntegerWorld implements InterfaceWorld {
        Object value = 3;
        public Object getGameRuleValue(InterfaceRule rule) { return value; }
        public boolean setGameRule(InterfaceRule rule, Object value) { this.value = value; return true; }
    }
    public static final class ExperimentalWorld {
        public Object getGameRuleValue(ClassRule rule) { throw new IllegalArgumentException("Feature disabled"); }
        public boolean setGameRule(ClassRule rule, Object value) { return false; }
    }
    @Test void actualPublicClassShapeReadsAndWritesTypedValues() {
        var api = new GameRules.RuntimeAccess(ClassRule.class, ClassWorld.class);
        var rule = new BooleanRule(); var world = new ClassWorld();
        assertSame(Boolean.class, api.type(rule)); assertEquals(true, api.value(world, rule));
        assertTrue(api.set(world, rule, false)); assertEquals(false, api.value(world, rule));
        assertThrows(IllegalArgumentException.class, () -> api.set(world, rule, "false"));
        assertThrows(IllegalArgumentException.class, () -> api.value(world, new IntegerRule()));
    }
    @Test void actualPublicInterfaceShapeReadsAndWritesWithoutConcreteClassDependency() {
        var api = new GameRules.RuntimeAccess(InterfaceRule.class, InterfaceWorld.class);
        var rule = new IntegerRule(); var world = new IntegerWorld();
        assertSame(Integer.class, api.type(rule)); assertEquals(3, api.value(world, rule));
        assertTrue(api.set(world, rule, 12)); assertEquals(12, api.value(world, rule));
        assertThrows(IllegalArgumentException.class, () -> api.set(world, rule, true));
    }
    @Test void featureUnavailableAndRejectedWriteRemainHonest() {
        var api = new GameRules.RuntimeAccess(ClassRule.class, ExperimentalWorld.class);
        var rule = new BooleanRule(); var world = new ExperimentalWorld();
        assertThrows(IllegalArgumentException.class, () -> api.value(world, rule));
        assertFalse(api.set(world, rule, false));
        assertThrows(UnsupportedOperationException.class, () -> new GameRules.RuntimeAccess(ClassRule.class, Object.class));
    }
}
