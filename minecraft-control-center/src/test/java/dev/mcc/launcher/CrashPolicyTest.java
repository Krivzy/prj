package dev.mcc.launcher;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CrashPolicyTest {
    @Test void exactlyThreeRecoveryAttemptsWithCooldownThenTerminalFailure() {
        CrashPolicy policy = new CrashPolicy(3); long now = 100;
        assertEquals(2, policy.delaySeconds(now)); assertEquals(10, policy.delaySeconds(now + 1));
        assertEquals(30, policy.delaySeconds(now + 2)); assertNull(policy.delaySeconds(now + 3));
        assertEquals(2, policy.delaySeconds(now + TimeUnit.MINUTES.toNanos(10) + 3)); assertEquals(1, policy.attempts());
    }
    @Test void zeroRecoveryOptionDisablesUnexpectedExitRecovery() { assertNull(new CrashPolicy(0).delaySeconds(100)); }
    @Test void requestedStopAndCleanUnexpectedExitAreTerminal() {
        assertEquals(OwnedSupervisor.ExitAction.STOP, OwnedSupervisor.afterExit(0, "stop", false));
        assertEquals(OwnedSupervisor.ExitAction.STOP, OwnedSupervisor.afterExit(7, "stop", false));
        assertEquals(OwnedSupervisor.ExitAction.STOP, OwnedSupervisor.afterExit(0, null, false));
        assertEquals(OwnedSupervisor.ExitAction.STOP, OwnedSupervisor.afterExit(7, null, true));
    }
    @Test void requestedRestartRequiresCleanExitAndShutdownOverridesIt() {
        assertEquals(OwnedSupervisor.ExitAction.RESTART, OwnedSupervisor.afterExit(0, "restart", false));
        assertEquals(OwnedSupervisor.ExitAction.FAIL, OwnedSupervisor.afterExit(7, "restart", false));
        assertEquals(OwnedSupervisor.ExitAction.STOP, OwnedSupervisor.afterExit(0, "restart", true));
        assertEquals(OwnedSupervisor.ExitAction.RECOVER, OwnedSupervisor.afterExit(7, null, false));
    }
}
