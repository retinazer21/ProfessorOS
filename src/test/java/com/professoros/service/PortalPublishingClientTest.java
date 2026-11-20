package com.professoros.service;

import com.sun.net.httpserver.HttpServer;
import com.google.gson.*;
import com.professoros.model.DomainValidationException;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Contract test with a local fake Auth/REST boundary, not a claim of hosted Auth verification. */
class PortalPublishingClientTest {
    @Test void mfaTokenUsedForIdempotentPublicationAndRevoked() throws Exception {
        var calls=new ArrayList<String>(); var snapshots=new HashMap<String,String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath(); calls.add(path);
            String input=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            String answer=switch(path) {
                case "/auth/v1/token" -> "{\"access_token\":\"password-only-test-token\"}";
                case "/auth/v1/user" -> "{\"factors\":[{\"factor_type\":\"totp\",\"status\":\"verified\",\"id\":\"factor\"}]}";
                case "/auth/v1/factors/factor/challenge" -> "{\"id\":\"challenge\"}";
                case "/auth/v1/factors/factor/verify" -> "{\"access_token\":\"mfa-test-token\"}";
                case "/rest/v1/rpc/portal_access" -> "\"professor\"";
                case "/rest/v1/portal_published_grades" -> {
                    assertEquals("Bearer mfa-test-token",exchange.getRequestHeaders().getFirst("Authorization"));
                    assertTrue(exchange.getRequestHeaders().getFirst("Prefer").contains("merge-duplicates"));
                    assertEquals("on_conflict=offering_id,student_id",exchange.getRequestURI().getRawQuery());
                    var data=JsonParser.parseString(input).getAsJsonObject();
                    snapshots.put(data.get("offering_id")+":"+data.get("student_id"),input);
                    yield "["+input+"]";
                }
                default -> "";
            };
            byte[] output=answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,output.length==0?-1:output.length);
            if(output.length>0)exchange.getResponseBody().write(output);exchange.close();
        });
        server.start();
        try {
            var client=new PortalPublishingClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"sb_publishable_test",HttpClient.newHttpClient());
            var grade=new GradePublication(UUID.randomUUID(),UUID.randomUUID(),new BigDecimal("72"),"C");
            assertThrows(DomainValidationException.class,()->client.publish(grade));
            client.signIn("professor@example.invalid","test-only-password","123456");
            client.publish(grade);client.publish(grade);client.signOut();
            assertEquals(1,snapshots.size());assertEquals("/auth/v1/logout",calls.getLast());
            assertThrows(DomainValidationException.class,()->client.publish(grade));
        } finally {server.stop(0);}
    }
}
