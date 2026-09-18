package io.github.mekhontsev.magicdesk.x11;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public final class X11DataExchangeTest {
    @Test public void quotedMimeParametersDoNotDiscardTheWholeOffer() {
        List<String> formats = List.of(
                "application/x-openoffice-embed-source-xml;windows_formatname=\"Star Embed Source (XML)\"",
                "text/rtf", "text/html", "text/plain;charset=utf-16",
                "text/plain;charset=utf-8", "UTF8_STRING", "STRING");
        assertEquals(formats, X11DataExchange.validateTypes(formats));
    }

    @Test public void namesRemainOpaqueAndOrdered() {
        String format = "application/example; name=\"two words\"";
        assertEquals(List.of(format, "UTF8_STRING"),
                X11DataExchange.validateTypes(List.of(format, "UTF8_STRING", format)));
    }

    @Test public void framingAndControlBytesAreStillRejected() {
        for (String format : Arrays.asList(null, "", "text/plain\nUTF8_STRING", "text/plain\r",
                "text/plain\t", "text/plain\0", "text/plain\u007f", "text/plain\u0080")) {
            assertThrows(IllegalArgumentException.class,
                    () -> X11DataExchange.validateTypes(Arrays.asList("UTF8_STRING", format)));
        }
    }

    @Test public void offerAndNameBoundsRemainUnchanged() {
        assertEquals(List.of(), X11DataExchange.validateTypes(List.of()));
        assertEquals(List.of("a".repeat(127)), X11DataExchange.validateTypes(List.of("a".repeat(127))));
        assertEquals(List.of("UTF8_STRING"), X11DataExchange.validateTypes(Collections.nCopies(64, "UTF8_STRING")));
        assertThrows(IllegalArgumentException.class, () -> X11DataExchange.validateTypes(null));
        assertThrows(IllegalArgumentException.class,
                () -> X11DataExchange.validateTypes(Collections.nCopies(65, "UTF8_STRING")));
        assertThrows(IllegalArgumentException.class,
                () -> X11DataExchange.validateTypes(List.of("a".repeat(128))));
    }
}
