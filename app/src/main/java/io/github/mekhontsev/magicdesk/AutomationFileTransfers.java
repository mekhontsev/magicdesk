package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;
import org.json.JSONException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Resumable, bounded transfers. The journal owns identities, never a long-lived descriptor. */
final class AutomationFileTransfers {
    static final int CHUNK_BYTES = 128 * 1024;
    static final int MAX_TRANSFERS = 16;

    record FileIdentity(String path, long device, long inode, long size, long modified) {
        JSONObject json() throws JSONException {
            return new JSONObject().put("path", path).put("device", device).put("inode", inode)
                    .put("size", size).put("modified", modified);
        }
        static FileIdentity parse(JSONObject json) throws JSONException {
            return new FileIdentity(json.getString("path"), json.getLong("device"),
                    json.getLong("inode"), json.getLong("size"), json.getLong("modified"));
        }
        boolean sameFile(FileIdentity other) { return device == other.device && inode == other.inode; }
    }

    interface Storage {
        FileIdentity stat(String path) throws IOException;
        FileIdentity create(String target, String id) throws IOException;
        InputStream read(FileIdentity file, long offset) throws IOException;
        void write(FileIdentity file, long offset, byte[] data) throws IOException;
        FileIdentity publish(FileIdentity file, String target, boolean overwrite) throws IOException;
        /** Delete only this identity; an already absent file is a successful retry. */
        void delete(FileIdentity file) throws IOException;
    }

    private final Path mDirectory;
    private final Storage mStorage;

    AutomationFileTransfers(Path directory, Storage storage) {
        mDirectory = directory;
        mStorage = storage;
    }

    synchronized JSONObject execute(String name, JSONObject args) throws IOException, JSONException {
        final String id = identifier(args.getString("transferId"));
        if (name.equals("files.upload_begin")) return begin(id, args, true);
        if (name.equals("files.download_begin")) return begin(id, args, false);
        final JSONObject transfer = load(id);
        final boolean upload = transfer.getBoolean("upload");
        if (name.startsWith("files.upload_") != upload) throw new IllegalArgumentException("wrong transfer direction");
        switch (name) {
            case "files.upload_status": return status(transfer);
            case "files.upload_chunk": return upload(transfer, args);
            case "files.upload_commit": return commit(transfer);
            case "files.upload_abort":
                if (transfer.getString("state").equals("active")) {
                    mStorage.delete(FileIdentity.parse(transfer.getJSONObject("file")));
                    transfer.put("state", "aborted");
                    save(transfer);
                }
                return status(transfer);
            case "files.download_chunk": return download(transfer, args);
            case "files.download_finish":
                transfer.put("state", "completed");
                save(transfer);
                return status(transfer);
            default: throw new IllegalArgumentException("unknown transfer command");
        }
    }

    private JSONObject begin(String id, JSONObject args, boolean upload) throws IOException, JSONException {
        final String path = args.getString("path");
        if (!Path.of(path).isAbsolute() || !Path.of(path).normalize().toString().equals(path)) {
            throw new IllegalArgumentException("path must be absolute and normalized");
        }
        final long size = upload ? args.getLong("size") : 0;
        final String sha = upload ? digestArgument(args.getString("sha256")) : "";
        if (size < 0) throw new IllegalArgumentException("size must be nonnegative");
        final boolean overwrite = upload && args.optBoolean("overwrite", false);
        if (Files.exists(journal(id))) {
            final JSONObject old = load(id);
            if (!path.equals(old.getString("path")) || upload != old.getBoolean("upload")
                    || (upload && (size != old.getLong("size") || !sha.equals(old.getString("sha256"))
                    || overwrite != old.getBoolean("overwrite")))) {
                throw new IllegalArgumentException("transferId already belongs to another request");
            }
            return status(old);
        }
        reserveJournal();
        final FileIdentity file = upload ? mStorage.create(path, id) : mStorage.stat(path);
        final JSONObject transfer = new JSONObject().put("transferId", id).put("path", path)
                .put("upload", upload).put("overwrite", overwrite).put("file", file.json())
                .put("size", upload ? size : file.size).put("offset", 0)
                .put("sha256", upload ? sha : digest(mStorage.read(file, 0)))
                .put("state", "active");
        try {
            if (!upload) verifySnapshot(file);
            save(transfer);
        } catch (IOException | JSONException failure) {
            if (upload) {
                try { mStorage.delete(file); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        return status(transfer);
    }

    private JSONObject upload(JSONObject transfer, JSONObject args) throws IOException, JSONException {
        requireActive(transfer);
        final String encoded = args.getString("data");
        if (encoded.length() > (CHUNK_BYTES + 2) / 3 * 4) throw new IllegalArgumentException("chunk too large");
        final byte[] data = Base64.getDecoder().decode(encoded);
        if (data.length == 0 || data.length > CHUNK_BYTES) throw new IllegalArgumentException("invalid chunk size");
        final long offset = args.getLong("offset");
        final long acknowledged = transfer.getLong("offset");
        final long size = transfer.getLong("size");
        if (offset < 0 || offset > size || data.length > size - offset) {
            throw new IllegalArgumentException("chunk exceeds declared file size");
        }
        final FileIdentity file = FileIdentity.parse(transfer.getJSONObject("file"));
        if (offset < acknowledged && data.length <= acknowledged - offset) {
            try (InputStream stream = mStorage.read(file, offset)) {
                if (!Arrays.equals(data, stream.readNBytes(data.length))) {
                    throw new IllegalArgumentException("retry contains different bytes");
                }
            }
        } else if (offset == acknowledged) {
            mStorage.write(file, offset, data);
            // A crash before journal commit is recoverable by rewriting this same chunk.
            transfer.put("offset", offset + data.length);
            save(transfer);
        } else throw new IllegalArgumentException("offset does not match acknowledged upload position");
        return status(transfer);
    }

    private JSONObject commit(JSONObject transfer) throws IOException, JSONException {
        if (transfer.getString("state").equals("completed")) return status(transfer);
        requireActive(transfer);
        if (transfer.getLong("offset") != transfer.getLong("size")) throw new IllegalArgumentException("upload incomplete");
        final FileIdentity file = FileIdentity.parse(transfer.getJSONObject("file"));
        final String target = transfer.getString("path");
        FileIdentity current;
        try { current = mStorage.stat(file.path); }
        catch (IOException missing) {
            // Publishing may have succeeded before the process or connection disappeared.
            current = mStorage.stat(target);
            if (!file.sameFile(current)) throw missing;
        }
        if (!file.sameFile(current) || current.size != transfer.getLong("size")
                || !digest(mStorage.read(current, 0)).equals(transfer.getString("sha256"))) {
            throw new IOException("upload identity, size or SHA-256 mismatch");
        }
        if (!current.path.equals(target)) mStorage.publish(current, target, transfer.getBoolean("overwrite"));
        transfer.put("state", "completed");
        save(transfer);
        return status(transfer);
    }

    private JSONObject download(JSONObject transfer, JSONObject args) throws IOException, JSONException {
        requireActive(transfer);
        final FileIdentity file = FileIdentity.parse(transfer.getJSONObject("file"));
        final long offset = args.getLong("offset");
        final int length = args.optInt("length", CHUNK_BYTES);
        if (offset < 0 || offset > file.size || length < 1 || length > CHUNK_BYTES) {
            throw new IllegalArgumentException("invalid download range");
        }
        verifySnapshot(file);
        final byte[] data;
        try (InputStream stream = mStorage.read(file, offset)) {
            data = stream.readNBytes((int) Math.min(length, file.size - offset));
        }
        if (data.length != Math.min(length, file.size - offset)) throw new IOException("file shortened during download");
        verifySnapshot(file);
        return status(transfer).put("offset", offset).put("nextOffset", offset + data.length)
                .put("data", Base64.getEncoder().encodeToString(data)).put("eof", offset + data.length == file.size);
    }

    private void verifySnapshot(FileIdentity file) throws IOException {
        final FileIdentity now = mStorage.stat(file.path);
        if (!file.sameFile(now) || file.size != now.size || file.modified != now.modified) {
            throw new IOException("file changed; begin a new download");
        }
    }

    private static void requireActive(JSONObject transfer) throws JSONException {
        if (!transfer.getString("state").equals("active")) throw new IllegalArgumentException("transfer is not active");
    }

    private static JSONObject status(JSONObject transfer) throws JSONException {
        return new JSONObject().put("transferId", transfer.getString("transferId"))
                .put("path", transfer.getString("path")).put("size", transfer.getLong("size"))
                .put("sha256", transfer.getString("sha256")).put("offset", transfer.getLong("offset"))
                .put("state", transfer.getString("state")).put("chunkBytes", CHUNK_BYTES);
    }

    static String identifier(String id) {
        if (!id.matches("[a-zA-Z0-9_-]{16,64}")) throw new IllegalArgumentException("transferId must have 16-64 letters, digits, underscores or hyphens");
        return id;
    }

    static String digestArgument(String value) {
        if (!value.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("SHA-256 must be 64 hexadecimal digits");
        return value.toLowerCase(Locale.ROOT);
    }

    static String digest(InputStream source) throws IOException {
        try (InputStream stream = source) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[CHUNK_BYTES];
            for (int count; (count = stream.read(buffer)) >= 0;) digest.update(buffer, 0, count);
            final StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private Path journal(String id) { return mDirectory.resolve(id + ".json"); }

    private JSONObject load(String id) throws IOException, JSONException {
        final Path path = journal(id);
        if (Files.size(path) > 16384) throw new IOException("invalid transfer journal");
        return new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    private void save(JSONObject transfer) throws IOException, JSONException {
        final Path path = journal(transfer.getString("transferId"));
        final Path temporary = path.resolveSibling(path.getFileName() + ".new");
        try (FileOutputStream stream = new FileOutputStream(temporary.toFile())) {
            stream.write(transfer.toString().getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void reserveJournal() throws IOException, JSONException {
        Files.createDirectories(mDirectory);
        final List<Path> records;
        try (var paths = Files.list(mDirectory)) {
            records = paths.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified())).toList();
        }
        int count = records.size();
        for (Path record : records) {
            if (count < MAX_TRANSFERS) break;
            final String id = record.getFileName().toString().replace(".json", "");
            if (!load(id).getString("state").equals("active")) { Files.delete(record); count--; }
        }
        if (count >= MAX_TRANSFERS) throw new IOException("too many active transfers; abort or finish one first");
    }
}
