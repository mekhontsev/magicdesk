package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class PublicWebRequestTest {
    private static PublicWebRequest parse(String text) throws IOException {
        return PublicWebRequest.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)));
    }
    @Test public void httpUsesOneOriginAndDoesNotForwardProxyCredentials() throws Exception {
        var r=parse("GET http://mirror.example/index?x=1 HTTP/1.1\r\nHost: unrelated\r\n"
                +"Proxy-Authorization: secret\r\nRange: bytes=12-\r\n\r\n");
        assertEquals("mirror.example",r.host()); assertEquals(80,r.port());
        assertEquals("GET /index?x=1 HTTP/1.1\r\nHost: mirror.example\r\nConnection: close\r\nRange: bytes=12-\r\n\r\n",
                new String(r.request(),StandardCharsets.US_ASCII));
        assertNull(parse("CONNECT mirror.example:443 HTTP/1.1\r\n\r\n").request());
    }
    @Test public void rejectsAmbiguousTargetAndFraming() {
        for (var line:new String[]{"CONNECT mirror.example:22","CONNECT user@mirror.example:443",
                "CONNECT mirror.example:443/path","CONNECT mirror.example:443?x",
                "GET file://mirror.example/a","POST http://mirror.example/a","GET http://localhost",
                "GET http://mirror.example:8080/a"})
            assertThrows(line,IOException.class,()->parse(line+" HTTP/1.1\r\n\r\n"));
        for (var header:new String[]{"Content-Length: 32","Transfer-Encoding: chunked"," WithFold: example",
                "Long: "+"x".repeat(16384)})
            assertThrows(IOException.class,()->parse("GET http://mirror.example HTTP/1.1\r\n"+header+"\r\n\r\n"));
    }
    @Test public void localAndSpecialAddressesCannotBeOrigins() throws Exception {
        for (var ip:new String[]{"0.0.0.0","127.0.0.2","10.0.0.1","100.127.255.255","169.254.169.254","172.31.1.1",
                "192.168.7.1","192.0.2.5","198.19.0.1","198.51.100.1","203.0.113.5","224.0.0.1","255.255.255.255",
                "::1","fd00::1","64:ff9b::c0a8:0101"})
            assertFalse(ip,PublicWebRequest.publicAddress(InetAddress.getByName(ip)));
        assertTrue(PublicWebRequest.publicAddress(InetAddress.getByName("1.1.1.1")));
    }
}
