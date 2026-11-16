package io.github.mekhontsev.magicdesk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Bounded template packaging and APK v2 framing. All cryptography is supplied by Java/Android. */
final class LauncherApk {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int RSA_SHA256 = 0x0103;
    private static final int CHUNK = 1024 * 1024;

    static byte[] assemble(InputStream template, String host, String packageName, String label, byte[] icon)
            throws IOException {
        var bytes = new ByteArrayOutputStream();
        var names = new HashSet<String>();
        int size = 0, icons = 0;
        try (var input = new ZipInputStream(template); var output = new ZipOutputStream(bytes)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/")) continue;
                if (!names.add(name) || names.size() > 64) throw new IOException("Invalid launcher template entries");
                byte[] data = input.readNBytes(MAX_BYTES + 1);
                size += data.length;
                if (size > MAX_BYTES) throw new IOException("Launcher template is too large");
                if (name.equals("AndroidManifest.xml")) data = LauncherManifest.replace(data, Map.of(
                        "io.github.mekhontsev.magicdesk.launchertemplate", packageName,
                        "MAGICDESK_LAUNCHER_LABEL", label, "MAGICDESK_TARGET_PACKAGE", host));
                // The template has exactly one image; AGP may shorten its resource path.
                else if (name.startsWith("res/") && name.endsWith(".png")) { data = icon; icons++; }
                if (data.length > MAX_BYTES) throw new IOException("Launcher resource is too large");
                var copy = new ZipEntry(name);
                copy.setMethod(ZipEntry.STORED);
                copy.setTime(315532800000L);
                copy.setSize(data.length);
                var crc = new CRC32(); crc.update(data); copy.setCrc(crc.getValue());
                // Android requires uncompressed, four-byte aligned resources.arsc on API 30+.
                int padding = -(bytes.size() + 30 + name.getBytes(StandardCharsets.UTF_8).length + 4) & 3;
                copy.setExtra(le(4 + padding).putShort((short) 0xd935).putShort((short) padding).array());
                output.putNextEntry(copy); output.write(data); output.closeEntry();
            }
        }
        if (icons != 1 || !names.containsAll(java.util.List.of("AndroidManifest.xml", "classes.dex", "resources.arsc"))
                || bytes.size() > MAX_BYTES) throw new IOException("Incomplete launcher template");
        return bytes.toByteArray();
    }

    static byte[] sign(byte[] unsigned, PrivateKey key, X509Certificate certificate)
            throws IOException, GeneralSecurityException {
        if (unsigned.length < 22 || unsigned.length > MAX_BYTES) throw new IOException("Invalid launcher ZIP");
        // Our ZIP writer emits neither a comment nor ZIP64. Reject anything outside that contract.
        var zip = ByteBuffer.wrap(unsigned).order(ByteOrder.LITTLE_ENDIAN);
        int end = unsigned.length - 22;
        int directory = zip.getInt(end + 16), directorySize = zip.getInt(end + 12);
        if (zip.getInt(end) != 0x06054b50 || zip.getShort(end + 20) != 0 || zip.getInt(end + 4) != 0
                || zip.getShort(end + 8) != zip.getShort(end + 10) || directory < 0 || directorySize < 0
                || (long) directory + directorySize != end) throw new IOException("Unsupported launcher ZIP layout");
        byte[] digest = contentDigest(unsigned, directory, end);
        byte[] block = signingBlock(digest,key,certificate);
        var result = le(unsigned.length + block.length).put(unsigned, 0, directory).put(block)
                .put(unsigned, directory, unsigned.length - directory);
        result.putInt(end + block.length + 16, directory + block.length);
        return result.array();
    }

    private static byte[] signingBlock(byte[] digest, PrivateKey key, X509Certificate certificate)
            throws GeneralSecurityException {
        byte[] signedData = concat(sequence(concat(integer(RSA_SHA256), field(digest))),
                sequence(certificate.getEncoded()), field(new byte[0]));
        var signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key); signer.update(signedData);
        byte[] signature = signer.sign();
        var verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(certificate); verifier.update(signedData);
        if (!verifier.verify(signature)) throw new GeneralSecurityException("Launcher signing key does not match its certificate");
        byte[] value = sequence(concat(field(signedData), sequence(concat(integer(RSA_SHA256), field(signature))),
                field(certificate.getPublicKey().getEncoded())));
        int blockSize = 8 + 8 + 4 + value.length + 8 + 16;
        return le(blockSize).putLong(blockSize - 8).putLong(4L + value.length).putInt(0x7109871a)
                .put(value).putLong(blockSize - 8).put("APK Sig Block 42".getBytes(StandardCharsets.US_ASCII)).array();
    }

    /** Runtime payloads are streamed; distribution data is never embedded in an APK. */
    static void assemble(InputStream template, Map<String,String> markers, byte[] icon,
            Map<String,Path> libraries, byte[] configuration, Path destination) throws IOException {
        assemble(template,markers,icon,libraries,configuration,Map.of(),destination);
    }

    static void assemble(InputStream template, Map<String,String> markers, byte[] icon,
            Map<String,Path> libraries, byte[] configuration, Map<String,byte[]> notices, Path destination) throws IOException {
        if (configuration.length > 32768 || libraries.isEmpty() || libraries.size() > 32)
            throw new IOException("Invalid application payload");
        var names = new HashSet<String>();
        int size = 0, icons = 0;
        try (var input = new ZipInputStream(template);
             var counted = new CountingOutput(Files.newOutputStream(destination));
             var output = new ZipOutputStream(counted)) {
            ZipEntry entry;
            while ((entry=input.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/")) continue;
                if (!names.add(name) || names.size() > 128 || name.startsWith("lib/")
                        || name.equals("assets/environment.json")) throw new IOException("Invalid runtime template");
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                size += bytes.length;
                if (size > MAX_BYTES) throw new IOException("Runtime template is too large");
                if (name.equals("AndroidManifest.xml")) bytes=LauncherManifest.replace(bytes, markers);
                else if (name.startsWith("res/") && name.endsWith(".png")) { bytes=icon; icons++; }
                put(output,counted,name,bytes);
            }
            put(output,counted,"assets/environment.json",configuration);
            long noticeBytes=0;
            for (var notice : notices.entrySet()) {
                String name=notice.getKey();
                if (!name.startsWith("assets/licenses/") || name.contains("..") || name.indexOf('\\')>=0
                        || !names.add(name) || notices.size()>128 || (noticeBytes+=notice.getValue().length)>8L*1024*1024)
                    throw new IOException("Invalid runtime notices");
                put(output,counted,name,notice.getValue());
            }
            for (var library : libraries.entrySet()) {
                if (!library.getKey().matches("lib[a-zA-Z0-9_]+\\.so")) throw new IOException("Invalid native library name");
                Path file=library.getValue();
                long length=Files.size(file);
                if (length<=0 || length>512L*1024*1024) throw new IOException("Invalid native library size");
                var crc=new CRC32(); byte[] buffer=new byte[65536];
                try (var in=Files.newInputStream(file)) {
                    int n; while ((n=in.read(buffer))>=0) crc.update(buffer,0,n);
                }
                begin(output,counted,"lib/arm64-v8a/"+library.getKey(),length,crc.getValue(),16384);
                Files.copy(file,output); output.closeEntry();
            }
            if (icons!=1 || !names.containsAll(java.util.List.of("AndroidManifest.xml","classes.dex","resources.arsc")))
                throw new IOException("Incomplete runtime template");
        }
    }

    private static void put(ZipOutputStream out,CountingOutput counted,String name,byte[] bytes) throws IOException {
        var crc=new CRC32(); crc.update(bytes);
        begin(out,counted,name,bytes.length,crc.getValue(),4);
        out.write(bytes); out.closeEntry();
    }

    private static void begin(ZipOutputStream out,CountingOutput counted,String name,long size,long crc,int alignment)
            throws IOException {
        var entry=new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED); entry.setTime(315532800000L);
        entry.setSize(size); entry.setCrc(crc);
        int padding=(int)(-(counted.count+30+name.getBytes(StandardCharsets.UTF_8).length+4)&(alignment-1));
        entry.setExtra(le(4+padding).putShort((short)0xd935).putShort((short)padding).array());
        out.putNextEntry(entry);
    }

    static void sign(Path unsigned,Path destination,PrivateKey key,X509Certificate certificate)
            throws IOException,GeneralSecurityException {
        if (unsigned.toAbsolutePath().equals(destination.toAbsolutePath())) throw new IOException("APK output must be distinct");
        try (var input=new RandomAccessFile(unsigned.toFile(),"r")) {
            long length=input.length();
            if (length<22 || length>Integer.MAX_VALUE-65536) throw new IOException("Unsupported APK size");
            long end=length-22;
            byte[] trailer=new byte[22]; input.seek(end); input.readFully(trailer);
            var zip=ByteBuffer.wrap(trailer).order(ByteOrder.LITTLE_ENDIAN);
            int directory=zip.getInt(16), size=zip.getInt(12);
            if (zip.getInt(0)!=0x06054b50 || zip.getShort(20)!=0 || zip.getInt(4)!=0
                    || zip.getShort(8)!=zip.getShort(10) || directory<0 || size<0 || (long)directory+size!=end)
                throw new IOException("Unsupported runtime ZIP layout");
            long[] bounds={0,directory,end,length};
            int chunks=0;
            for (int i=0;i<3;i++) chunks+=(int)((bounds[i+1]-bounds[i]+CHUNK-1)/CHUNK);
            var digest=MessageDigest.getInstance("SHA-256");
            digest.update((byte)0x5a); digest.update(integer(chunks));
            var part=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[CHUNK];
            for (int i=0;i<3;i++) for (long at=bounds[i];at<bounds[i+1];at+=CHUNK) {
                int n=(int)Math.min(CHUNK,bounds[i+1]-at);
                input.seek(at); input.readFully(buffer,0,n);
                part.update((byte)0xa5); part.update(integer(n)); part.update(buffer,0,n);
                digest.update(part.digest());
            }
            byte[] block=signingBlock(digest.digest(),key,certificate);
            try (OutputStream output=Files.newOutputStream(destination)) {
                copy(input,output,0,directory,buffer); output.write(block);
                copy(input,output,directory,size,buffer);
                zip.putInt(16,directory+block.length); output.write(trailer);
            }
        }
    }

    private static void copy(RandomAccessFile input,OutputStream output,long offset,long length,byte[] buffer) throws IOException {
        input.seek(offset);
        while (length>0) { int n=(int)Math.min(buffer.length,length); input.readFully(buffer,0,n); output.write(buffer,0,n); length-=n; }
    }

    private static final class CountingOutput extends java.io.FilterOutputStream {
        long count;
        CountingOutput(OutputStream output) { super(output); }
        @Override public void write(int value) throws IOException { out.write(value); count++; }
        @Override public void write(byte[] value,int offset,int length) throws IOException { out.write(value,offset,length); count+=length; }
    }

    private static byte[] contentDigest(byte[] bytes, int directory, int end) throws GeneralSecurityException {
        int[] bounds = {0, directory, end, bytes.length};
        int chunks = 0;
        for (int i = 0; i < 3; i++) chunks += (bounds[i + 1] - bounds[i] + CHUNK - 1) / CHUNK;
        var total = MessageDigest.getInstance("SHA-256");
        total.update((byte) 0x5a); total.update(integer(chunks));
        var part = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < 3; i++) for (int at = bounds[i]; at < bounds[i + 1]; at += CHUNK) {
            int length = Math.min(CHUNK, bounds[i + 1] - at);
            part.update((byte) 0xa5); part.update(integer(length)); part.update(bytes, at, length);
            total.update(part.digest());
        }
        return total.digest();
    }

    private static ByteBuffer le(int bytes) { return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static byte[] integer(int value) { return le(4).putInt(value).array(); }
    private static byte[] field(byte[] bytes) { return concat(integer(bytes.length), bytes); }
    private static byte[] sequence(byte[] bytes) { return field(field(bytes)); }
    private static byte[] concat(byte[]... parts) {
        var output = new ByteArrayOutputStream();
        for (byte[] part : parts) output.writeBytes(part);
        return output.toByteArray();
    }
    private LauncherApk() { }
}
