package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;

final class ConsoleCommandHistory {
    private static final int MAX_ENTRIES = 100;

    private final List<String> mEntries = new ArrayList<>();
    private int mCursor;
    private String mDraft = "";

    void record(final String command) {
        if (command == null || command.trim().isEmpty()) {
            return;
        }
        if (mEntries.isEmpty()
                || !command.equals(mEntries.get(mEntries.size() - 1))) {
            mEntries.add(command);
            if (mEntries.size() > MAX_ENTRIES) {
                mEntries.remove(0);
            }
        }
        mCursor = mEntries.size();
        mDraft = "";
    }

    String previous(final String currentInput) {
        if (mEntries.isEmpty()) {
            return null;
        }
        if (mCursor >= mEntries.size()) {
            mDraft = currentInput == null ? "" : currentInput;
            mCursor = mEntries.size();
        }
        if (mCursor > 0) {
            mCursor--;
        }
        return mEntries.get(mCursor);
    }

    String next() {
        if (mCursor >= mEntries.size()) {
            return null;
        }
        mCursor++;
        return mCursor == mEntries.size()
                ? mDraft : mEntries.get(mCursor);
    }
}
