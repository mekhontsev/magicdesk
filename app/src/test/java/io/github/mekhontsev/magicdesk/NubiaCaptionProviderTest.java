package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class NubiaCaptionProviderTest {
    @Test public void vendorDeathFailsClosedAndReleasesOnlyAnUnstableClient() throws Exception {
        RuntimeSourceFixture.verify("""
            static final String PROJECTION_PROVIDER="content://provider",PROVIDER_METHOD="MagicDesk",TAG="test";
            static class RemoteException extends Exception { }
            static class Uri { static String parse(String value){return value;} }
            static class Log { static void i(String tag,String text){} static void w(String tag,String text,Throwable e){} }
            static class Bundle {
                String value; void putBoolean(String key,boolean value){}
                String getString(String key){return value;}
            }
            static class Client implements AutoCloseable {
                int closes, calls; boolean dead, denied; Bundle result=new Bundle();
                Bundle call(String method,String argument,Bundle request) throws RemoteException {
                    calls++; if(dead)throw new RemoteException(); if(denied)throw new SecurityException(); return result;
                }
                public void close(){closes++;}
            }
            static class Resolver {
                Client client; boolean denied;
                Client acquireUnstableContentProviderClient(String uri){if(denied)throw new SecurityException();return client;}
            }
            static class Context { Resolver resolver=new Resolver(); Resolver getContentResolver(){return resolver;} }
            """ + RuntimeSourceFixture.nestedClass("platform/nubia/NubiaCaptionVisibilityManager", "Transport")
                + RuntimeSourceFixture.methods("platform/nubia/NubiaCaptionVisibilityManager", "readPrivacyMode", "parsePrivacyValue") + """
            public static void verify() {
                Context context=new Context(); Client client=new Client(); context.resolver.client=client;
                client.result.value="on";
                check(readPrivacyMode(context,Transport.WIRED)==1 && client.closes==1, "successful read leaked client");
                client.dead=true;
                check(readPrivacyMode(context,Transport.WIRED)==null && client.closes==2, "provider death escaped or leaked client");
                client.dead=false; client.denied=true;
                check(readPrivacyMode(context,Transport.WIRELESS)==null && client.closes==3, "permission failure escaped");
                client.denied=false; client.result=null;
                check(readPrivacyMode(context,Transport.WIRELESS)==null && client.closes==4, "missing value synthesized state");
                context.resolver.client=null;
                check(readPrivacyMode(context,Transport.WIRED)==null, "unavailable provider synthesized state");
                context.resolver.denied=true;
                check(readPrivacyMode(context,Transport.WIRED)==null, "acquisition failure escaped");
                check(client.calls==4, "implicit retry");
            }
            """);
    }
}
