package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

public final class AutomationJsonArgumentsTest {
    @Test
    public void keepsExactIntegerValuesAndRejectsFractionalIds() throws Exception {
        for (final Object value : new Object[] {42, 42L, 42.0, new BigDecimal("42.000")}) {
            assertEquals(42, AutomationJsonArguments.requiredInt(
                    new JSONObject().put("taskId", value), "taskId"));
        }
        for (final Object value : new Object[] {
                42.5, "42", true, JSONObject.NULL, new BigDecimal("42.00000000000001"),
                Long.MAX_VALUE, new BigInteger("4294967296")}) {
            final JSONObject args = new JSONObject().put("taskId", value);
            assertThrows(IllegalArgumentException.class,
                    () -> AutomationJsonArguments.requiredInt(args, "taskId"));
        }
    }

    @Test
    public void preservesSignedLongBoundariesWithoutSaturation() throws Exception {
        assertEquals(Long.MAX_VALUE, AutomationJsonArguments.requiredLong(
                new JSONObject().put("runId", Long.MAX_VALUE), "runId"));
        assertEquals(Long.MIN_VALUE, AutomationJsonArguments.requiredLong(
                new JSONObject().put("runId", Long.MIN_VALUE), "runId"));
        for (final Object value : new Object[] {
                new BigInteger("9223372036854775808"),
                new BigInteger("-9223372036854775809"), (double) Long.MAX_VALUE,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AutomationJsonArguments.longValue(value, "runId"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> AutomationJsonArguments.requiredLong(new JSONObject(), "runId"));
    }
}
