package com.professoros.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DesktopSessionTest {
    @Test @EnabledOnOs(OS.WINDOWS)
    void protectedCredentialSurvivesStoreReopening(@TempDir Path directory) throws Exception {
        String fixture = "fictional-refresh-value";
        try (var store = new DesktopSessionStore(directory,"project")) {
            store.save(fixture);
            assertFalse(Files.readString(directory.resolve("project.dpapi")).contains(fixture));
            assertThrows(java.io.IOException.class, () -> new DesktopSessionStore(directory,"project"));
        }
        try (var reopened = new DesktopSessionStore(directory,"project")) {
            assertEquals(fixture,reopened.load());
            reopened.clear();
            assertNull(reopened.load());
        }
    }

    @Test void restartRotatesRefreshAndKeepsSessionOnTransientFailure() throws Exception {
        var vault = new Vault("initial");
        var mode = new AtomicInteger(0);
        var logout = new AtomicBoolean(false);
        var refreshes = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            int status=200;
            String body;
            switch (exchange.getRequestURI().getPath()) {
                case "/auth/v1/token" -> {
                    int request=refreshes.incrementAndGet();
                    if (mode.get()==1) { status=503; body="{}"; }
                    else if (mode.get()==2) { status=400; body="{\"error_code\":\"refresh_token_not_found\"}"; }
                    else body="{\"access_token\":\"aal2-test\",\"refresh_token\":\"rotated-"+request+"\"}";
                }
                case "/auth/v1/user" -> body="{\"id\":\"8c94434b-a43f-4c36-b860-e7c2bd673a2c\"}";
                case "/rest/v1/rpc/portal_access" -> body=mode.get()==3?"null":"\"professor\"";
                case "/auth/v1/logout" -> {
                    logout.set("scope=local".equals(exchange.getRequestURI().getQuery()));
                    body="{}";
                }
                default -> { status=404; body="{}"; }
            }
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });
        server.start();
        try {
            var origin=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            var transport=HttpClient.newHttpClient();
            var desktop=new PortalPublishingClient(origin,"fixture",transport);
            assertTrue(desktop.restoreSession(vault));
            desktop.closeLocalSession();
            assertFalse(logout.get());
            assertEquals("rotated-1",vault.value);
            mode.set(1);
            assertThrows(java.io.IOException.class,
                    () -> new PortalPublishingClient(origin,"fixture",transport).restoreSession(vault));
            assertEquals("rotated-1",vault.value);
            mode.set(0);
            desktop=new PortalPublishingClient(origin,"fixture",transport);
            assertTrue(desktop.restoreSession(vault));
            desktop.signOut();
            assertTrue(logout.get());
            assertNull(vault.value);
            mode.set(2);
            vault.value="revoked";
            assertFalse(new PortalPublishingClient(origin,"fixture",transport).restoreSession(vault));
            assertNull(vault.value);
            mode.set(3);
            vault.value="valid-token-with-revoked-role";
            assertFalse(new PortalPublishingClient(origin,"fixture",transport).restoreSession(vault));
            assertNull(vault.value);
        } finally {server.stop(0);}
    }

    private static final class Vault implements PortalPublishingClient.RefreshStore {
        private String value;
        Vault(String value){this.value=value;}
        public String load(){return value;}
        public void save(String credential){value=credential;}
        public void clear(){value=null;}
    }
}
