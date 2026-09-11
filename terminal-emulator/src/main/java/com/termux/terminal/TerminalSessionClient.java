package com.termux.terminal;

/**
 * Emulator cursor preferences and logging callbacks.
 * MagicDesk adaptation: session lifecycle callbacks are excluded because PTYs and their
 * lifecycle are owned by MagicDesk, not by the emulator module.
 */
public interface TerminalSessionClient {

    void onTerminalCursorStateChange(boolean state);



    Integer getTerminalCursorStyle();



    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
