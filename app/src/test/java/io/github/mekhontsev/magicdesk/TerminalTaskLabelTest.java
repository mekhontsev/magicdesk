package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TerminalTaskLabelTest {
    @Test
    public void foregroundApplicationReplacesGenericTerminalName() {
        assertEquals(
                "nvim",
                TerminalTaskLabel.resolve(
                        "Termux",
                        new TerminalProcessInfo(123L, 123L, "/bin/nvim"),
                        ""));
    }

    @Test
    public void oscTitleAddsApplicationContext() {
        assertEquals(
                "nvim - notes.md",
                TerminalTaskLabel.resolve(
                        "Termux",
                        new TerminalProcessInfo(123L, 123L, "nvim"),
                        "notes.md"));
    }

    @Test
    public void interactiveShellRetainsBackendIdentity() {
        assertEquals(
                "Termux - home",
                TerminalTaskLabel.resolve(
                        "Termux",
                        new TerminalProcessInfo(123L, 123L, "bash"),
                        "home"));
    }

    @Test
    public void unsafeTitleCharactersAreCollapsed() {
        assertEquals(
                "mc - left right",
                TerminalTaskLabel.resolve(
                        "Termux",
                        new TerminalProcessInfo(123L, 123L, "mc"),
                        "left\u001b\nright"));
    }

    @Test
    public void applicationTitleDoesNotRepeatExecutable() {
        assertEquals(
                "mc [user@host]:~",
                TerminalTaskLabel.resolve(
                        "Termux Console",
                        new TerminalProcessInfo(123L, 123L, "mc"),
                        "mc [user@host]:~"));
    }
}
