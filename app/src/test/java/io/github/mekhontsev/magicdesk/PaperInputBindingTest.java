package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PaperInputBindingTest {
    @Test public void chordRequiresExactKeyAndModifiers() {
        var binding = new PaperInputBinding(140, PaperInputBinding.modifiers(true, false, true, false), PaperInputAction.NEXT);
        assertTrue(binding.matches(140, 5));
        assertFalse(binding.matches(140, 1));
        assertFalse(binding.matches(141, 5));
        assertFalse(PaperInputBinding.NONE.matches(0, 0));
    }

    @Test public void serializationRetainsActionAndRejectsInvalidValues() throws Exception {
        var binding = new PaperInputBinding(140, 8, PaperInputAction.PREVIOUS);
        assertEquals(binding, PaperInputBinding.fromJson(binding.toJson()));
        assertEquals(PaperInputBinding.NONE, PaperInputBinding.fromJson(null));
        assertEquals(PaperInputBinding.NONE, PaperInputBinding.fromJson(new JSONObject()
                .put("key", 140).put("modifiers", 32).put("action", "NEXT")));
        assertEquals(PaperInputBinding.NONE, PaperInputBinding.fromJson(new JSONObject()
                .put("key", 140).put("modifiers", 0).put("action", "UNKNOWN")));
    }
}
