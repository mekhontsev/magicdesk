package io.github.mekhontsev.magicdesk;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DesktopNotificationMapper {
    private DesktopNotificationMapper() {
    }

    static DesktopNotificationListenerService.Entry fromSystemNotification(
            final Context context,
            final StatusBarNotification statusBarNotification,
            final RankingMap rankingMap) {
        if (statusBarNotification == null
                || context.getPackageName().equals(
                        statusBarNotification.getPackageName())) {
            return null;
        }
        final Notification notification =
                statusBarNotification.getNotification();
        if (notification == null) {
            return null;
        }

        final Ranking ranking = new Ranking();
        final boolean hasRanking = rankingMap != null
                && rankingMap.getRanking(
                        statusBarNotification.getKey(), ranking);
        final int importance = hasRanking
                ? ranking.getImportance()
                : NotificationManager.IMPORTANCE_DEFAULT;
        final boolean matchesInterruptionFilter =
                !hasRanking || ranking.matchesInterruptionFilter();
        final Bundle extras = notification.extras == null
                ? Bundle.EMPTY : notification.extras;
        final int progressMax =
                extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        final boolean progress = progressMax > 0
                || extras.getBoolean(
                        Notification.EXTRA_PROGRESS_INDETERMINATE, false);

        final List<DesktopNotificationListenerService.ActionEntry> actions =
                new ArrayList<>();
        if (notification.actions != null) {
            for (int index = 0;
                    index < notification.actions.length;
                    index++) {
                final Notification.Action action =
                        notification.actions[index];
                if (action == null || action.actionIntent == null
                        || action.getRemoteInputs() != null
                        || TextUtils.isEmpty(action.title)) {
                    continue;
                }
                actions.add(
                        new DesktopNotificationListenerService.ActionEntry(
                                index, trimText(action.title, 60)));
            }
        }

        return new DesktopNotificationListenerService.Entry(
                statusBarNotification.getKey(),
                statusBarNotification.getPackageName(),
                loadApplicationLabel(
                        context, statusBarNotification.getPackageName()),
                trimText(
                        extras.getCharSequence(Notification.EXTRA_TITLE), 160),
                getNotificationText(extras),
                statusBarNotification.getPostTime(),
                importance,
                notification.flags,
                notification.contentIntent != null,
                statusBarNotification.isClearable(),
                statusBarNotification.isOngoing(),
                (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0,
                progress,
                matchesInterruptionFilter,
                notification.getLargeIcon() != null
                        ? notification.getLargeIcon()
                        : notification.getSmallIcon(),
                Collections.unmodifiableList(actions));
    }

    private static String loadApplicationLabel(
            final Context context, final String packageName) {
        try {
            return context.getPackageManager()
                    .getApplicationInfo(packageName, 0)
                    .loadLabel(context.getPackageManager())
                    .toString();
        } catch (Exception ignored) {
            return packageName;
        }
    }

    private static String getNotificationText(final Bundle extras) {
        final CharSequence bigText =
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (!TextUtils.isEmpty(bigText)) {
            return trimText(bigText, 600);
        }
        final CharSequence text =
                extras.getCharSequence(Notification.EXTRA_TEXT);
        if (!TextUtils.isEmpty(text)) {
            return trimText(text, 600);
        }
        final CharSequence[] lines =
                extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines == null || lines.length == 0) {
            return "";
        }
        final StringBuilder value = new StringBuilder();
        for (final CharSequence line : lines) {
            if (TextUtils.isEmpty(line)) {
                continue;
            }
            if (value.length() > 0) {
                value.append('\n');
            }
            value.append(line);
        }
        return trimText(value, 600);
    }

    static String trimText(final CharSequence text, final int maxLength) {
        if (text == null) {
            return "";
        }
        final String value = text.toString().trim();
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1) + "\u2026";
    }
}
