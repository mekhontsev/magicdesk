package io.github.mekhontsev.magicdesk;

import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Provides consistent keyboard navigation for desktop popup menus. */
final class DesktopMenuNavigator {
    private final Runnable mDismissAction;
    private final List<View> mItems = new ArrayList<>();
    private final Set<View> mSubmenuItems = Collections.newSetFromMap(
            new IdentityHashMap<>());

    private Runnable mBackAction;
    private View mPreferredFirstItem;

    DesktopMenuNavigator(final Runnable dismissAction) {
        mDismissAction = dismissAction;
    }

    void prepare(final Runnable backAction) {
        mItems.clear();
        mSubmenuItems.clear();
        mBackAction = backAction;
        mPreferredFirstItem = null;
    }

    void prefer(final View item) {
        if (mPreferredFirstItem == null && isNavigable(item)) {
            mPreferredFirstItem = item;
        }
    }

    void markSubmenu(final View item) {
        if (item != null) {
            mSubmenuItems.add(item);
        }
    }

    void activate(final View root) {
        mItems.clear();
        collectItems(root);
        for (final View item : mItems) {
            item.setOnKeyListener(this::handleKey);
        }
        final View first = isNavigable(mPreferredFirstItem)
                ? mPreferredFirstItem : nextItem(-1, 1, false);
        if (first != null) {
            first.post(first::requestFocusFromTouch);
        }
    }

    private boolean handleKey(
            final View focused,
            final int keyCode,
            final KeyEvent event) {
        if (!isNavigationKey(keyCode)) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || (keyCode == KeyEvent.KEYCODE_TAB
                        && !event.isShiftPressed())) {
            focusRelative(focused, 1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP
                || (keyCode == KeyEvent.KEYCODE_TAB
                        && event.isShiftPressed())) {
            focusRelative(focused, -1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (mSubmenuItems.contains(focused)) {
                focused.performClick();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            runBackOrDismiss();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            focused.performClick();
            return true;
        }
        runBackOrDismiss();
        return true;
    }

    private void focusRelative(final View focused, final int direction) {
        final int current = mItems.indexOf(focused);
        final View next = nextItem(current, direction, true);
        if (next != null) {
            next.requestFocusFromTouch();
        }
    }

    private View nextItem(
            final int current,
            final int direction,
            final boolean wrap) {
        if (mItems.isEmpty()) {
            return null;
        }
        int index = current;
        for (int visited = 0; visited < mItems.size(); visited++) {
            index += direction;
            if (index < 0 || index >= mItems.size()) {
                if (!wrap) {
                    return null;
                }
                index = index < 0 ? mItems.size() - 1 : 0;
            }
            final View candidate = mItems.get(index);
            if (isNavigable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void collectItems(final View view) {
        if (view == null) {
            return;
        }
        if (!(view instanceof ViewGroup)) {
            if (view.isClickable() && view.isFocusable()) {
                mItems.add(view);
            }
            return;
        }
        final ViewGroup group = (ViewGroup) view;
        final int itemCountBeforeChildren = mItems.size();
        for (int index = 0; index < group.getChildCount(); index++) {
            collectItems(group.getChildAt(index));
        }
        if (mItems.size() == itemCountBeforeChildren
                && view.isClickable() && view.isFocusable()) {
            mItems.add(view);
        }
    }

    private void runBackOrDismiss() {
        if (mBackAction != null) {
            mBackAction.run();
        } else {
            mDismissAction.run();
        }
    }

    private static boolean isNavigable(final View view) {
        return view != null
                && view.isEnabled()
                && view.getVisibility() == View.VISIBLE;
    }

    private static boolean isNavigationKey(final int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_TAB
                || keyCode == KeyEvent.KEYCODE_ESCAPE
                || keyCode == KeyEvent.KEYCODE_BACK;
    }
}
