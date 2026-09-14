package io.github.mekhontsev.magicdesk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** Bounded output encoder. Receipts describe PTY acceptance, not screen visibility. */
final class TerminalOutputStream implements ShellCommandOutput.Sink {
    interface Writer { PtyPeerOutput.Receipt write(byte[] bytes) throws IOException; }

    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final int PIXEL_CHUNK = 3072; // 4096 base64 bytes per Kitty APC.
    private final Writer mWriter;
    private final boolean mPng;
    private final boolean mTmux;
    private final ByteArrayOutputStream mEncoded = new ByteArrayOutputStream();
    private final byte[] mPixels = new byte[PIXEL_CHUNK];
    private int mPixelCount;
    private boolean mFirst = true;
    private boolean mStopped;
    long sourceBytes;
    long bytesWritten;
    boolean completed;
    boolean writeUnconfirmed;
    int errno;

    TerminalOutputStream(Writer writer, String mimeType, boolean tmux) {
        if (mimeType != null && !mimeType.equals("image/png")) {
            throw new IllegalArgumentException("supported mimeType is image/png; omit it for raw bytes");
        }
        mWriter = writer;
        mPng = mimeType != null;
        mTmux = tmux;
    }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        if (mStopped || completed) throw new IOException("terminal output is already stopped");
        sourceBytes += length;
        if (!mPng) { append(bytes, offset, length); flush(); return; }
        while (length > 0) {
            // Retain one full chunk until the next byte or EOF identifies m=0.
            if (mPixelCount == mPixels.length) pixelChunk(false);
            final int count = Math.min(length, mPixels.length - mPixelCount);
            System.arraycopy(bytes, offset, mPixels, mPixelCount, count);
            mPixelCount += count;
            offset += count;
            length -= count;
        }
    }

    void finish() throws IOException {
        if (mStopped || completed) throw new IOException("terminal output is already stopped");
        if (mPng) pixelChunk(true);
        flush();
        completed = true;
    }

    private void pixelChunk(boolean last) throws IOException {
        if (mFirst && (mPixelCount < PNG.length
                || !Arrays.equals(PNG, Arrays.copyOf(mPixels, PNG.length)))) {
            mStopped = true;
            throw new IOException("stdout is not a PNG stream");
        }
        final String control = mFirst ? "a=T,f=100,q=2,C=1," : "";
        String frame = "\033_G" + control + "m=" + (last ? "0" : "1") + ";"
                + Base64.getEncoder().encodeToString(Arrays.copyOf(mPixels, mPixelCount)) + "\033\\";
        // tmux owns pane output; use its explicit passthrough protocol for Kitty.
        // Raw output is never wrapped or interpreted here.
        if (mTmux) frame = "\033Ptmux;" + frame.replace("\033", "\033\033") + "\033\\";
        final byte[] bytes = frame.getBytes(StandardCharsets.US_ASCII);
        append(bytes, 0, bytes.length);
        mFirst = false;
        mPixelCount = 0;
    }

    private void append(byte[] bytes, int offset, int length) throws IOException {
        while (length > 0) {
            final int count = Math.min(length, PtyPeerOutput.MAX_BYTES - mEncoded.size());
            mEncoded.write(bytes, offset, count);
            offset += count;
            length -= count;
            if (mEncoded.size() == PtyPeerOutput.MAX_BYTES) flush();
        }
    }

    private void flush() throws IOException {
        if (mEncoded.size() == 0) return;
        final byte[] bytes = mEncoded.toByteArray();
        mEncoded.reset();
        final PtyPeerOutput.Receipt receipt;
        try { receipt = mWriter.write(bytes); }
        catch (IOException | RuntimeException error) {
            mStopped = true;
            writeUnconfirmed = true;
            throw new IOException("PTY write acknowledgement unavailable; do not replay", error);
        }
        bytesWritten += receipt.bytesWritten();
        errno = receipt.errno();
        if (errno != 0) {
            mStopped = true;
            throw new IOException("PTY delivery stopped (errno " + errno + "); do not replay");
        }
    }
}
