package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class ShizukuCaptionPolicyControllerTest {
    @Test
    public void parsePrivacyValue_acceptsEnabledValues() {
        assertEquals(Integer.valueOf(1),
                ShizukuCaptionPolicyController.parsePrivacyValue("on"));
        assertEquals(Integer.valueOf(1),
                ShizukuCaptionPolicyController.parsePrivacyValue(" TRUE "));
        assertEquals(Integer.valueOf(1),
                ShizukuCaptionPolicyController.parsePrivacyValue("1"));
    }

    @Test
    public void parsePrivacyValue_acceptsDisabledValues() {
        assertEquals(Integer.valueOf(0),
                ShizukuCaptionPolicyController.parsePrivacyValue("off"));
        assertEquals(Integer.valueOf(0),
                ShizukuCaptionPolicyController.parsePrivacyValue(" false "));
        assertEquals(Integer.valueOf(0),
                ShizukuCaptionPolicyController.parsePrivacyValue("0"));
    }

    @Test
    public void parsePrivacyValue_rejectsUnknownValues() {
        assertNull(ShizukuCaptionPolicyController.parsePrivacyValue(null));
        assertNull(ShizukuCaptionPolicyController.parsePrivacyValue(""));
        assertNull(ShizukuCaptionPolicyController.parsePrivacyValue("maybe"));
    }
}
