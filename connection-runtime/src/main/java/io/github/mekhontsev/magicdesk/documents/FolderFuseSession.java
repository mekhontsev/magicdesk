package io.github.mekhontsev.magicdesk.documents;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import static android.system.OsConstants.*;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

/** One FUSE 7.31 connection; node IDs and file handles never cross mount boundaries. */
final class FolderFuseSession implements AutoCloseable {
    private static final int HEADER=40, OUT=16, IO=16384;
    private final DocumentTree tree;
    private final HashMap<Long,Node> nodes=new HashMap<>();
    private final HashMap<String,Node> byId=new HashMap<>();
    private final HashMap<Long,Handle> handles=new HashMap<>();
    private long nextNode=2, nextHandle=1;
    private static final class Node {
        final long ino; String id;
        long lookups, opens;
        boolean knownSize;
        Node(long ino,String id) { this.ino=ino; this.id=id; }
    }
    private static final class Handle {
        final Node node;
        final ParcelFileDescriptor fd;
        final List<DocumentTree.Item> directory;
        final int access;
        boolean stream;
        long position;
        Handle(Node n,ParcelFileDescriptor fd,List<DocumentTree.Item> directory,int access) {
            this.node=n; this.fd=fd; this.directory=directory; this.access=access;
        }
        int transfer(boolean write,byte[] bytes,int at,int size,long offset) throws Exception {
            if (!stream) try {
                return write ? Os.pwrite(fd.getFileDescriptor(),bytes,at,size,offset)
                        : Os.pread(fd.getFileDescriptor(),bytes,at,size,offset);
            } catch (ErrnoException e) { if (e.errno!=ESPIPE) throw e; stream=true; }
            if (position!=offset) throw DocumentTree.error(ESPIPE);
            int count=write ? Os.write(fd.getFileDescriptor(),bytes,at,size) : Os.read(fd.getFileDescriptor(),bytes,at,size);
            position+=count; return count;
        }
    }
    FolderFuseSession(DocumentTree tree) {
        this.tree=tree;
        Node root=new Node(1,tree.root()); nodes.put(1L,root); byId.put(root.id,root);
    }
    private static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    void serve(InputStream input, OutputStream output) throws IOException {
        var in=new DataInputStream(input);
        var req=buffer(HEADER+IO+512); var reply=buffer(OUT+IO+256);
        while (true) {
            int first=in.read();
            if (first<0) return;
            req.clear(); req.array()[0]=(byte)first; in.readFully(req.array(),1,HEADER-1);
            int len=req.getInt(), op=req.getInt(); long unique=req.getLong(), ino=req.getLong();
            if (len<HEADER || len>req.capacity()) throw new IOException("Invalid FUSE message length");
            in.readFully(req.array(),HEADER,len-HEADER); req.limit(len).position(HEADER);
            reply.clear().position(OUT); int error=0;
            try { respond(op,ino,req,reply); }
            catch (ErrnoException e) { error=e.errno; }
            catch (SecurityException e) { error=EACCES; }
            catch (java.io.FileNotFoundException e) { error=ENOENT; }
            catch (UnsupportedOperationException e) { error=EOPNOTSUPP; }
            catch (java.nio.BufferUnderflowException | IllegalArgumentException e) { error=EINVAL; }
            catch (Exception e) { error=EIO; }
            if (op==2 || op==42) continue; // FORGET / BATCH_FORGET are one-way.
            if (error!=0) reply.position(OUT);
            int size=reply.position(); reply.putInt(0,size).putInt(4,-error).putLong(8,unique);
            output.write(reply.array(),0,size);
        }
    }
    private static String name(ByteBuffer in) throws Exception {
        int start=in.position();
        while (in.hasRemaining()) if (in.get()==0) {
            var bytes=ByteBuffer.wrap(in.array(),start,in.position()-start-1);
            String value=StandardCharsets.UTF_8.newDecoder().decode(bytes).toString();
            if (DocumentTree.validName(value)) return value;
            break;
        }
        throw DocumentTree.error(EINVAL);
    }
    private Node node(long ino) throws ErrnoException {
        Node n=nodes.get(ino); if (n==null) throw DocumentTree.error(ESTALE); return n;
    }
    private Node entry(DocumentTree.Item item, ByteBuffer out) {
        Node n=byId.computeIfAbsent(item.id(), id -> {
            var created=new Node(nextNode++,id); nodes.put(created.ino,created); return created;
        });
        n.lookups++;
        out.putLong(n.ino).putLong(1).putLong(0).putLong(0).putInt(0).putInt(0);
        attr(n,item,out); return n;
    }
    private void prune(Node n) {
        if (n.ino!=1 && n.lookups==0 && n.opens==0) { nodes.remove(n.ino); byId.remove(n.id,n); }
    }
    private void attr(Node n,DocumentTree.Item item,ByteBuffer out) {
        long seconds=item.modified()/1000;
        int frac=(int)(item.modified()%1000)*1000000;
        boolean directory=item.directory();
        int mode=(directory ? S_IFDIR|0555 : S_IFREG|0444) | (tree.canWrite(item) ? 0222 : 0);
        n.knownSize=item.size()>=0;
        long size=Math.max(0,item.size());
        out.putLong(n.ino).putLong(size).putLong(size/512+(size%512==0 ? 0 : 1))
                .putLong(seconds).putLong(seconds).putLong(seconds).putInt(frac).putInt(frac).putInt(frac)
                .putInt(mode).putInt(directory ? 2 : 1).putInt(0).putInt(0).putInt(0).putInt(4096).putInt(0);
    }
    private void attributes(Node n,ByteBuffer out) throws Exception {
        out.putLong(0).putInt(0).putInt(0); attr(n,tree.stat(n.id),out);
    }
    private void open(Node n,int flags,boolean directory,ByteBuffer out) throws Exception {
        if (handles.size()>=4096) throw DocumentTree.error(EMFILE);
        Handle h=directory ? new Handle(n,null,tree.children(n.id),O_RDONLY)
                : new Handle(n,tree.open(n.id,flags),null,flags&O_ACCMODE);
        long id=nextHandle++; handles.put(id,h); n.opens++;
        // DIRECT_IO prevents an unknown provider length from turning every read into EOF.
        boolean direct=h.fd!=null && (!n.knownSize || h.fd.getStatSize()<0);
        out.putLong(id).putInt(direct ? 1 : 0).putInt(0);
    }
    private Handle handle(Node n,long id) throws ErrnoException {
        Handle h=handles.get(id); if (h==null || h.node!=n) throw DocumentTree.error(EBADF); return h;
    }
    private void forget(long ino,long count) {
        Node n=nodes.get(ino);
        if (n!=null) { n.lookups=Long.compareUnsigned(count,n.lookups)>=0 ? 0 : n.lookups-count; prune(n); }
    }
    private void respond(int op,long ino,ByteBuffer in,ByteBuffer out) throws Exception {
        if (op==26) { // INIT (no optional kernel capabilities)
            if (in.getInt()!=7) throw DocumentTree.error(EPROTO);
            out.putInt(7).putInt(Math.min(in.getInt(),31)).putInt(0).putInt(0)
                    .putShort((short)0).putShort((short)0).putInt(IO).putInt(1);
            while (out.position()<OUT+64) out.putInt(0);
            return;
        }
        if (op==2) { forget(ino,in.getLong()); return; }
        if (op==42) { int count=in.getInt(); in.getInt(); for (int i=0;i<count;++i) forget(in.getLong(),in.getLong()); return; }
        if (op==38) return; // DESTROY
        // Preserve the mountpoint after revocation. A missing grant must not
        // prevent Linux startup or reveal a writable underlying directory.
        if (!tree.available()) throw DocumentTree.error(EACCES);
        Node n=node(ino);
        switch (op) {
            case 1 -> entry(tree.child(n.id,name(in)),out);
            case 3 -> attributes(n,out);
            case 4 -> { // SETATTR: only size and an optional file handle can be expressed by SAF.
                tree.requireWrite(); int valid=in.getInt(); in.getInt(); long fh=in.getLong(),size=in.getLong();
                if ((valid&8)==0 || (valid&~(8|64|512))!=0) throw DocumentTree.error(EOPNOTSUPP);
                if ((valid&64)!=0) {
                    Handle h=handle(n,fh); if (h.fd==null || h.access==O_RDONLY) throw DocumentTree.error(EBADF);
                    Os.ftruncate(h.fd.getFileDescriptor(),size);
                } else try (var fd=tree.open(n.id,O_RDWR)) { Os.ftruncate(fd.getFileDescriptor(),size); }
                attributes(n,out);
            }
            case 9 -> { in.getInt(); in.getInt(); entry(tree.create(n.id,name(in),true),out); }
            case 10,11 -> tree.remove(n.id,name(in),op==11);
            case 12,45 -> {
                var dest=node(in.getLong());
                int flags=op==45 ? in.getInt() : 0;
                if (op==45) in.getInt();
                if ((flags&~1)!=0) throw DocumentTree.error(EOPNOTSUPP);
                var moved=tree.rename(n.id,name(in),dest.id,name(in),(flags&1)!=0);
                Node live=byId.remove(moved.oldId());
                if (live!=null) { live.id=moved.newId(); byId.put(live.id,live); }
            }
            case 14,27 -> open(n,in.getInt(),op==27,out);
            case 15,16,28 -> {
                Handle h=handle(n,in.getLong()); long offset=in.getLong(); int size=in.getInt();
                if (offset<0 || size<0 || size>IO) throw DocumentTree.error(EINVAL);
                if (op==28) {
                    if (h.directory==null) throw DocumentTree.error(ENOTDIR);
                    directory(h,offset,size,out);
                } else if (h.fd==null) throw DocumentTree.error(EISDIR);
                else if (op==15) {
                    if (h.access==O_WRONLY) throw DocumentTree.error(EBADF);
                    int count=h.transfer(false,out.array(),out.position(),size,offset);
                    out.position(out.position()+count);
                } else {
                    tree.requireWrite(); if (h.access==O_RDONLY) throw DocumentTree.error(EBADF);
                    if (in.limit()-HEADER-40!=size) throw DocumentTree.error(EINVAL);
                    out.putInt(h.transfer(true,in.array(),HEADER+40,size,offset)).putInt(0);
                }
            }
            case 18,29 -> {
                long id=in.getLong(); Handle h=handle(n,id); handles.remove(id); n.opens--; prune(n);
                if (h.fd!=null) h.fd.close();
            }
            case 20 -> { Handle h=handle(n,in.getLong()); if (h.fd==null) throw DocumentTree.error(EISDIR); Os.fsync(h.fd.getFileDescriptor()); }
            case 25 -> handle(n,in.getLong()); // FLUSH; writes are synchronous with the provider FD.
            case 34 -> {
                int mask=in.getInt(); if ((mask&W_OK)!=0) tree.requireWrite();
                var item=tree.stat(n.id);
                if ((mask&W_OK)!=0 && !tree.canWrite(item)) throw DocumentTree.error(EACCES);
                if (!item.directory() && (mask&X_OK)!=0) throw DocumentTree.error(EACCES);
            }
            case 35 -> {
                int flags=in.getInt(); in.position(HEADER+16);
                var item=tree.create(n.id,name(in),false); Node created=entry(item,out);
                try { open(created,flags,false,out); }
                catch (Exception e) { forget(created.ino,1); throw e; }
            }
            default -> throw DocumentTree.error(ENOSYS);
        }
    }
    private void directory(Handle h,long offset,int size,ByteBuffer out) {
        int limit=out.position()+size;
        for (long i=offset; i<(long)h.directory.size()+2;++i) {
            var item=i<2 ? null : h.directory.get((int)i-2);
            String name=i==0 ? "." : i==1 ? ".." : item.name();
            byte[] bytes=name.getBytes(StandardCharsets.UTF_8);
            int length=(24+bytes.length+7)&~7;
            if (out.position()+length>limit) break;
            int end=out.position()+length;
            Node known=item==null ? null : byId.get(item.id());
            out.putLong(i==0 ? h.node.ino : known==null ? 0 : known.ino).putLong(i+1).putInt(bytes.length)
                    .putInt(item==null || item.directory() ? 4 : 8).put(bytes);
            while (out.position()<end) out.put((byte)0);
        }
    }
    @Override public void close() {
        for (var h : handles.values()) if (h.fd!=null) try { h.fd.close(); } catch (IOException ignored) { }
        handles.clear(); nodes.clear(); byId.clear();
    }
}
