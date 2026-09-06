package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;

import java.math.BigDecimal;

/** Exact numeric arguments shared by the automation adapters. */
final class AutomationJsonArguments {
    private AutomationJsonArguments() {
    }

    static int requiredInt(final JSONObject object, final String key) {
        final long value = requiredLong(object, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " is out of range");
        }
        return (int) value;
    }

    static long requiredLong(final JSONObject object, final String key) {
        if (object == null || !object.has(key)) {
            throw new IllegalArgumentException(key + " is required");
        }
        return longValue(object.opt(key), key);
    }

    static long longValue(final Object value, final String name) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException error) {
            throw new IllegalArgumentException(
                    name + " must be an integer in the signed 64-bit range", error);
        }
    }
}
