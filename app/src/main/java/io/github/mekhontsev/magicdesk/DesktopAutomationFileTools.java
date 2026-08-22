package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/** Explicitly gated adapter over the same shell file API used by Files. */
final class DesktopAutomationFileTools {
    DesktopAutomationResult list(final JSONObject arguments) {
        try {
            requireShell();
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final int limit = boundedLimit(args.optInt("limit", 100));
            final int offset = cursor(args.optString("cursor", ""));
            final ShellFilePage page = ShellAccess.listShellDirectory(
                    required(args, "path"),
                    offset,
                    limit,
                    args.optBoolean("showHidden", false),
                    sortMode(args.optString("sort", "name")),
                    !"descending".equalsIgnoreCase(
                            args.optString("order", "ascending")));
            final JSONArray entries = new JSONArray();
            for (final ShellFileInfo entry : page.entries) {
                entries.put(fileJson(entry));
            }
            return DesktopAutomationResult.success(
                    "directory listed",
                    new JSONObject()
                            .put("path", page.directoryPath)
                            .put("parent", page.parentPath)
                            .put("entries", entries)
                            .put("items", entries)
                            .put("count", entries.length())
                            .put("nextCursor", page.complete
                                    ? JSONObject.NULL
                                    : Integer.toString(page.nextOffset)));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (IOException | RuntimeException error) {
            return unavailable(error);
        }
    }

    DesktopAutomationResult stat(final JSONObject arguments) {
        try {
            requireShell();
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            return DesktopAutomationResult.success(
                    "file information read",
                    new JSONObject().put("file", fileJson(
                            ShellAccess.getShellFileInfo(
                                    required(args, "path")))));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (IOException | RuntimeException error) {
            return unavailable(error);
        }
    }

    DesktopAutomationResult create(final JSONObject arguments) {
        try {
            requireShell();
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final ShellFileInfo created = ShellAccess.createShellEntry(
                    required(args, "parent"),
                    required(args, "name"),
                    args.optBoolean("directory", false));
            return DesktopAutomationResult.success(
                    "file entry created",
                    new JSONObject().put("file", fileJson(created)));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (IOException | RuntimeException error) {
            return unavailable(error);
        }
    }

    DesktopAutomationResult rename(final JSONObject arguments) {
        try {
            requireShell();
            final JSONObject args = arguments == null
                    ? new JSONObject() : arguments;
            final ShellFileInfo renamed = ShellAccess.renameShellEntry(
                    required(args, "path"),
                    required(args, "newName"));
            return DesktopAutomationResult.success(
                    "file entry renamed",
                    new JSONObject().put("file", fileJson(renamed)));
        } catch (IllegalArgumentException | JSONException error) {
            return invalid(error);
        } catch (IOException | RuntimeException error) {
            return unavailable(error);
        }
    }

    private static JSONObject fileJson(final ShellFileInfo file)
            throws JSONException {
        return new JSONObject()
                .put("path", file.absolutePath)
                .put("name", file.name)
                .put("mimeType", file.mimeType)
                .put("size", file.size)
                .put("modified", file.modified)
                .put("directory", file.directory)
                .put("symbolicLink", file.symbolicLink)
                .put("linkTarget", file.linkTarget)
                .put("readable", file.readable)
                .put("writable", file.writable)
                .put("executable", file.executable)
                .put("hidden", file.hidden)
                .put("ownerUid", file.ownerUid)
                .put("ownerGid", file.ownerGid)
                .put("mode", file.mode);
    }

    private static void requireShell() throws IOException {
        if (!ShellAccess.isReady()) {
            throw new IOException("shell command service is unavailable");
        }
    }

    private static int sortMode(final String value) {
        if ("name".equalsIgnoreCase(value)) {
            return ShellFileSystem.SORT_NAME;
        }
        if ("modified".equalsIgnoreCase(value)) {
            return ShellFileSystem.SORT_MODIFIED;
        }
        if ("size".equalsIgnoreCase(value)) {
            return ShellFileSystem.SORT_SIZE;
        }
        throw new IllegalArgumentException(
                "sort must be name, modified, or size");
    }

    private static int boundedLimit(final int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 200");
        }
        return limit;
    }

    private static int cursor(final String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            final int offset = Integer.parseInt(value);
            if (offset < 0) {
                throw new NumberFormatException();
            }
            return offset;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid cursor");
        }
    }

    private static String required(
            final JSONObject object,
            final String name) {
        final String value = object.optString(name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static DesktopAutomationResult invalid(final Throwable error) {
        return DesktopAutomationResult.failure(
                DesktopAutomationErrorCode.INVALID_ARGUMENT,
                ShellAccess.usefulMessage(error), false);
    }

    private static DesktopAutomationResult unavailable(final Throwable error) {
        return DesktopAutomationResult.failure(
                DesktopAutomationErrorCode.FILE_ACCESS_FAILED,
                ShellAccess.usefulMessage(error), true);
    }
}
