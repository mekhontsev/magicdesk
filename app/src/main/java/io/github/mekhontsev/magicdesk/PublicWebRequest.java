package io.github.mekhontsev.magicdesk;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** One public web origin per stream. HTTP is read-only; HTTPS is end-to-end TLS. */
record PublicWebRequest(String host,int port,byte[] request) {
    private static final Set<String> HEADERS=Set.of("accept","accept-encoding","user-agent","range",
            "if-range","if-none-match","if-modified-since","cache-control","authorization");

    static PublicWebRequest read(InputStream in) throws IOException {
        int[] budget={16384};
        String[] words=line(in,budget).split(" ",-1);
        if (words.length!=3 || !(words[2].equals("HTTP/1.1") || words[2].equals("HTTP/1.0")))
            throw new IOException("Invalid proxy request");
        boolean tunnel=words[0].equals("CONNECT");
        if (!tunnel && !words[0].equals("GET") && !words[0].equals("HEAD")) throw new IOException("Unsupported method");
        URI url;
        try { url=new URI(tunnel ? "https://"+words[1] : words[1]); }
        catch (URISyntaxException e) { throw new IOException("Invalid target",e); }
        String host=url.getHost();
        if (host==null || !host.matches("[a-zA-Z0-9.-]+") || host.indexOf('.')<1 || url.getRawUserInfo()!=null
                || url.getRawFragment()!=null || !(tunnel ? url.getPort()==443 && url.getRawPath().isEmpty()
                && url.getQuery()==null : "http".equalsIgnoreCase(url.getScheme()) && (url.getPort()==80 || url.getPort()==-1)))
            throw new IOException("Unsupported web target");
        String path=url.getRawPath().isEmpty() ? "/" : url.getRawPath();
        var header=new StringBuilder().append(words[0]).append(' ').append(path);
        if (url.getRawQuery()!=null) header.append('?').append(url.getRawQuery());
        header.append(" HTTP/1.1\r\nHost: ").append(host).append("\r\nConnection: close\r\n");
        for (;;) {
            String line=line(in,budget);
            if (line.isEmpty()) break;
            int colon=line.indexOf(':');
            if (colon<=0) throw new IOException("Invalid header");
            String key=line.substring(0,colon).toLowerCase(Locale.ROOT);
            if (!key.matches("[a-z0-9-]+") || key.equals("transfer-encoding")
                    || (key.equals("content-length") && !line.substring(colon+1).trim().equals("0")))
                throw new IOException("Invalid request framing");
            if (HEADERS.contains(key)) header.append(line).append("\r\n");
        }
        return new PublicWebRequest(host,tunnel ? 443 : 80,tunnel ? null
                : header.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
    }
    private static String line(InputStream in,int[] budget) throws IOException {
        var result=new StringBuilder();
        while (--budget[0]>=0) {
            int b=in.read();
            if (b=='\r' && --budget[0]>=0 && in.read()=='\n') return result.toString();
            if (b<32 || b>=127) throw new IOException("Invalid proxy header");
            result.append((char)b);
        }
        throw new IOException("Proxy headers too large");
    }

    // Conservative IPv4-only boundary: in particular no mapped, scoped or translated IPv6 addresses.
    static boolean publicAddress(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        byte[] ip=address.getAddress(); int a=ip[0]&255,b=ip[1]&255,c=ip[2]&255;
        return a!=0 && a!=10 && a!=127 && a<224 && !(a==100 && b>=64 && b<=127)
                && !(a==169 && b==254) && !(a==172 && b>=16 && b<=31)
                && !(a==192 && (b==168 || b==0 && (c==0 || c==2) || b==88 && c==99))
                && !(a==198 && (b==18 || b==19 || b==51 && c==100))
                && !(a==203 && b==0 && c==113);
    }
}
