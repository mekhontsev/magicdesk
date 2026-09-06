package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class AndroidIntegrationRequestTest {
    @Test
    public void integerFieldsPreserveTheFullSignedRangeAndExactDecimalIntegers() throws Exception {
        for (final int value : new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
            assertEquals(value, integer(value));
            assertEquals(value, integer((long) value));
            assertEquals(value, integer((double) value));
            assertEquals(value, integer(new BigDecimal(value + ".000")));
        }
    }

    @Test
    public void integerFieldsRejectFractionsOverflowAndCoercibleStrings() throws Exception {
        for (final Object invalid : new Object[] {
                0.5, -0.5, 1.999, Integer.MAX_VALUE + 1L, Integer.MIN_VALUE - 1L,
                new BigDecimal("1.00000000000000000001"),
                new BigInteger("18446744073709551616"), "1", true, JSONObject.NULL}) {
            final JSONObject args = new JSONObject().put("value", invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> AutomationJsonArguments.requiredInt(args, "value"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> AutomationJsonArguments.requiredInt(null, "value"));
        assertThrows(IllegalArgumentException.class,
                () -> AutomationJsonArguments.requiredInt(new JSONObject(), "value"));
    }

    @Test
    public void fractionalTaskIdentityAndBoundsAreRejectedByPresentationParsing() throws Exception {
        final JSONObject args = new JSONObject().put("mode", "windowed")
                .put("preferredTaskId", 42.5);
        assertThrows(IllegalArgumentException.class,
                () -> AndroidIntegrationRequest.parsePresentation(args, null));
        args.remove("preferredTaskId");
        args.put("bounds", new JSONObject().put("x", 0.5).put("y", 0)
                .put("width", 5000).put("height", 5000));
        assertThrows(IllegalArgumentException.class,
                () -> AndroidIntegrationRequest.parsePresentation(args, null));
    }

    @Test
    public void arrayProjectionRetainsSupportedTypesAndStringWhitespace() throws Exception {
        assertArrayEquals(new String[0], (String[]) array(new JSONArray()));
        assertArrayEquals(new String[] {" a ", "\t\n"},
                (String[]) array(new JSONArray().put(" a ").put("\t\n")));
        assertArrayEquals(new boolean[] {true, false},
                (boolean[]) array(new JSONArray().put(true).put(false)));
        assertArrayEquals(new int[] {1, Integer.MAX_VALUE},
                (int[]) array(new JSONArray().put(1).put((long) Integer.MAX_VALUE)));
        assertArrayEquals(new long[] {Long.MIN_VALUE, Long.MAX_VALUE, 1},
                (long[]) array(new JSONArray().put(Long.MIN_VALUE).put(Long.MAX_VALUE).put(1)));
        assertArrayEquals(new double[] {0.5, 2, -3.75},
                (double[]) array(new JSONArray().put(0.5).put(2).put(-3.75)), 0);
    }

    @Test
    public void integerArraysCannotSilentlyNarrowOrWrapLaterItems() throws Exception {
        for (final Object invalid : new Object[] {0.5, Integer.MAX_VALUE + 1L,
                new BigInteger("18446744073709551616"), "2", JSONObject.NULL}) {
            final JSONArray values = new JSONArray().put(1).put(invalid);
            assertThrows(IllegalArgumentException.class, () -> array(values));
        }
        for (final Object invalid : new Object[] {0.5, new BigDecimal("9223372036854775808"), "2"}) {
            final JSONArray values = new JSONArray().put(Long.MAX_VALUE).put(invalid);
            assertThrows(IllegalArgumentException.class, () -> array(values));
        }
    }

    @Test
    public void unsupportedAndMixedArrayItemsAreRejectedRatherThanStringified() throws Exception {
        for (final JSONArray values : new JSONArray[] {
                new JSONArray().put(true).put("false"),
                new JSONArray().put("text").put(1),
                new JSONArray().put(0.5).put("2"),
                new JSONArray().put(0.5).put(new BigDecimal("1e1000")),
                new JSONArray().put(JSONObject.NULL),
                new JSONArray().put(new JSONObject()),
                new JSONArray().put(new JSONArray())}) {
            assertThrows(IllegalArgumentException.class, () -> array(values));
        }
    }

    @Test
    public void arrayLimitIsCheckedBeforeReadingAnyElements() throws Exception {
        final JSONArray exact = new JSONArray(Collections.nCopies(256, 1));
        assertEquals(256, ((int[]) array(exact)).length);
        final JSONArray oversized = new JSONArray(Collections.nCopies(257, 1)) {
            @Override public Object get(final int index) {
                throw new AssertionError("oversized array must not be traversed");
            }
        };
        assertThrows(IllegalArgumentException.class, () -> array(oversized));
    }

    private static int integer(final Object value) throws Exception {
        return AutomationJsonArguments.requiredInt(new JSONObject().put("value", value), "value");
    }

    private static Object array(final JSONArray values) throws Exception {
        return AndroidIntegrationRequest.arrayExtraValue("extra", values);
    }
}
