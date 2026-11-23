package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class ShellWorkspaceFreeformPolicyTest {
    public static final class Raw {
        public final Object token;
        Raw(int id) { token = id; }
    }
    private static FrameworkTaskSnapshot task(int id, int display, int mode) {
        return new FrameworkTaskSnapshot(new Raw(id), id, id, display, 1, mode, 1,
                null, null, "", "", "", "", -1, "", null, true, false, null);
    }
    @Test public void twoWindowsKeepIndependentClaimsUntilDepartureNotOcclusion() {
        var writes = new ArrayList<String>();
        var claims = new ArrayList<Object>();
        var owner = new ShellDesktopTaskOwnership();
        owner.configure(6);
        owner.markDesktopHost(1);
        for (int id : List.of(2, 3, 4, 5)) owner.markDesktop(id);
        var policy = new ShellWorkspaceFreeformPolicy(new PlatformWindowingDriver.FreeformMultiplicity() {
            public boolean canClaim(Object raw) {
                Object token = ((Raw) raw).token;
                claims.add(token);
                return !token.equals(5);
            }
            public String describe(Object raw) { return "test"; }
            public void setExempt(Object tx, Object token, boolean exempt) {}
        }, (token, exempt) -> writes.add(token + ":" + exempt), message -> fail(message));
        var windows = List.of(task(1, 6, 5), task(2, 6, 5), task(3, 6, 5),
                task(4, 6, 1), task(5, 6, 5), task(6, 6, 5), task(8, 0, 5));
        policy.configure(6);
        policy.observe(0, windows, owner);
        policy.observe(6, windows, owner);
        policy.observe(6, List.of(), owner);
        policy.observe(6, windows, owner);
        assertEquals(List.of(2, 3, 5), claims);
        assertEquals(List.of("2:true", "3:true"), writes);
        policy.departed(3, 6);
        policy.release(5);
        policy.departed(2, 0);
        policy.configure(-1);
        policy.observe(6, windows, owner);
        assertEquals(List.of("2:true", "3:true", "2:false", "3:false"), writes);
    }
}
