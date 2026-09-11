package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DesktopDisplayTargetTest {
    @Test
    public void factoriesPreserveKindAndDisplay() {
        assertEquals(
                DesktopDisplayOutput.Kind.PHONE,
                DesktopDisplayTarget.phone().output.kind);
        assertEquals(
                DesktopDisplayOutput.Kind.WIRED,
                DesktopDisplayTarget.wired(7).output.kind);
        assertEquals(
                DesktopDisplayOutput.Kind.WIRELESS,
                DesktopDisplayTarget.wireless(8).output.kind);
        assertEquals(
                DesktopDisplayOutput.Kind.SIMULATED,
                DesktopDisplayTarget.simulated(9).output.kind);
        assertEquals(8, DesktopDisplayTarget.wireless(8).workspaceDisplayId);
        assertEquals(0, DesktopDisplayTarget.phone().workspaceDisplayId);
    }

    @Test
    public void profileMetadataIsExplicitAndImmutable() {
        final DesktopDisplayTarget target = DesktopDisplayTarget.wired(7)
                .withActivationSource(
                        DesktopDisplayOutput.ActivationSource
                                .MAGICDESK_REQUESTED)
                .withProfile( "display:wired:local:123");

        assertEquals(7, target.workspaceDisplayId);
        assertEquals(7, target.output.displayId);
        assertEquals("display:wired:local:123", target.output.profileKey);
        assertEquals(
                DesktopDisplayOutput.ActivationSource.MAGICDESK_REQUESTED,
                target.output.activationSource);
    }

    @Test
    public void restorePreservesActivationSource() {
        final DesktopDisplayTarget target = DesktopDisplayTarget.restore(
                DesktopDisplayOutput.Kind.WIRED,
                7,
                3,
                "display:wired:local:123",
                DesktopDisplayOutput.ActivationSource.MAGICDESK_REQUESTED);

        assertEquals(
                DesktopDisplayOutput.ActivationSource.MAGICDESK_REQUESTED,
                target.output.activationSource);
    }

    @Test(expected = IllegalArgumentException.class)
    public void secondaryTargetRejectsDefaultDisplay() {
        DesktopDisplayTarget.wireless(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void phoneOutputRejectsSecondaryDisplay() {
        DesktopDisplayTarget.restore(
                DesktopDisplayOutput.Kind.PHONE,
                7,
                7,
                "ignored",
                DesktopDisplayOutput.ActivationSource.MAGICDESK_REQUESTED);
    }
}
