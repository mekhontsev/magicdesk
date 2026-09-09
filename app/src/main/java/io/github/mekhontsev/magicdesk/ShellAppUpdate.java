package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Stages a verified APK in Android's installer; system_server owns installation after commit. */
final class ShellAppUpdate {
    private static final long MAX_APK_BYTES = 512L * 1024 * 1024;

    private ShellAppUpdate() { }

    static PackageInstaller installer(Context context, int userId) throws Exception {
        return FrameworkUserApi.contextForUser(ShellIdentityContext.create(context), userId)
                .getPackageManager().getPackageInstaller();
    }

    static String prepare(Context context, ShellFileSystem files, String source,
            long device, long inode, String expectedSha, int userId) {
        int sessionId = -1;
        PackageInstaller installer = null;
        final ParcelFileDescriptor descriptor = files.openVerified(source, "r", device, inode);
        try (var stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            final String sha = AutomationFileTransfers.digestArgument(expectedSha);
            final PackageManager manager = ShellIdentityContext.create(context).getPackageManager();
            // Parsing through our open descriptor keeps pathname replacement out of the operation.
            final PackageInfo candidate = manager.getPackageArchiveInfo("/proc/self/fd/" + descriptor.getFd(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            final PackageInfo installed = manager.getPackageInfo(BuildConfig.APPLICATION_ID, PackageManager.GET_SIGNING_CERTIFICATES);
            validate(candidate, installed);
            final PackageInstaller.SessionParams params =
                    FrameworkPackageInstallerApi.replacementParams(BuildConfig.APPLICATION_ID);
            installer = installer(context, userId);
            sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                stream.getChannel().position(0);
                try (OutputStream output = session.openWrite("base.apk", 0, -1)) {
                    final byte[] buffer = new byte[AutomationFileTransfers.CHUNK_BYTES];
                    long size = 0;
                    for (int count; (count = stream.read(buffer)) >= 0;) {
                        size += count;
                        if (size > MAX_APK_BYTES) throw new IOException("APK exceeds 512 MiB");
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                    session.fsync(output);
                }
                if (!Arrays.equals(digest.digest(), hexBytes(sha))) throw new IllegalArgumentException("APK SHA-256 mismatch");
            }
            return new JSONObject().put("sessionId", sessionId).put("sha256", sha)
                    .put("versionCode", candidate.getLongVersionCode()).put("versionName", candidate.versionName).toString();
        } catch (Exception error) {
            if (installer != null && sessionId >= 0) {
                try { installer.abandonSession(sessionId); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            }
            throw new IllegalArgumentException("cannot prepare MagicDesk update: " + ShellAccess.usefulMessage(error), error);
        }
    }

    static void commit(Context context, int sessionId, int userId, IntentSender callback) {
        try (PackageInstaller.Session session = installer(context, userId).openSession(sessionId)) {
            session.commit(callback);
        } catch (Exception error) {
            throw new IllegalStateException("cannot commit MagicDesk update", error);
        }
    }

    static void abandon(Context context, int sessionId, int userId) {
        try {
            installer(context, userId).abandonSession(sessionId);
        } catch (Exception error) {
            throw new IllegalStateException("cannot abandon MagicDesk update", error);
        }
    }

    static void validate(PackageInfo candidate, PackageInfo installed) {
        if (candidate == null || !BuildConfig.APPLICATION_ID.equals(candidate.packageName)
                || candidate.signingInfo == null || installed.signingInfo == null) {
            throw new IllegalArgumentException("a signed MagicDesk APK is required");
        }
        if (!signers(candidate.signingInfo.getApkContentsSigners()).equals(signers(installed.signingInfo.getApkContentsSigners()))) {
            throw new IllegalArgumentException("APK signing certificates do not match the installed application");
        }
        if (candidate.getLongVersionCode() < installed.getLongVersionCode()) {
            throw new IllegalArgumentException("APK downgrade is not allowed");
        }
    }

    private static Set<String> signers(Signature[] signatures) {
        if (signatures == null || signatures.length == 0) throw new IllegalArgumentException("missing APK signer");
        final Set<String> values = new HashSet<>();
        for (Signature signature : signatures) values.add(Base64.getEncoder().encodeToString(signature.toByteArray()));
        return values;
    }

    private static byte[] hexBytes(String hex) {
        final byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
