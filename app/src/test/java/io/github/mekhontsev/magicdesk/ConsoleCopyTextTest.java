package io.github.mekhontsev.magicdesk;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ConsoleCopyTextTest {
    @Test public void joinsIndentedProseFromATmuxScreen() {
        assertEquals("Checked the console notification: the same PTY returned with its history.",
                ConsoleCopyText.asParagraph("Checked the console notification:\n"
                        + "  the same PTY returned\n  with its history."));
    }

    @Test public void preservesParagraphsAndListEntries() {
        assertEquals("First paragraph continues.\n\n- First item continues.\n- Second item.\n1. Numbered item.",
                ConsoleCopyText.asParagraph("First paragraph\n  continues.\n\n"
                        + "- First item\n  continues.\n- Second item.\n1. Numbered item."));
    }

    @Test public void doesNotReformatObviousCodeOrTables() {
        final String text = "# Heading\nText.\n```sh\nprintf hello\n  printf world\n```\n"
                + "    code();\n    more();\n| one | two |\n| three | four |";
        assertEquals(text, ConsoleCopyText.asParagraph(text));
    }

    @Test public void normalizesLineEndingsWithoutJoiningWordsOrRemovingHyphens() {
        assertEquals("A hyphen- at a wrap.\n\nNext paragraph.",
                ConsoleCopyText.asParagraph("  A hyphen-\r\n  at a wrap.\r\n\r\nNext paragraph.\r\n"));
        assertEquals("", ConsoleCopyText.asParagraph("\n  \n"));
    }
}
