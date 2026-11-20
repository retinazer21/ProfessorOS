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
    @Test void publicationUsesReviewedRevisionAndDoesNotRetryConflict() throws Exception {
        var calls=new ArrayList<String>();
        var revision=UUID.randomUUID();
        var publicationStatus=new java.util.concurrent.atomic.AtomicInteger(200);
        var publicationRequest=new java.util.concurrent.atomic.AtomicReference<JsonObject>();
        var role = new java.util.concurrent.atomic.AtomicReference<>("professor");
        var enrollment = UUID.randomUUID();
        var lecture = UUID.randomUUID();
        var legacy = new java.util.concurrent.atomic.AtomicBoolean(false);
        var offering = UUID.randomUUID();
        var saveStatus = new java.util.concurrent.atomic.AtomicInteger(200);
        var saveReply = new java.util.concurrent.atomic.AtomicReference<>("1");
        var lastBatch = new java.util.concurrent.atomic.AtomicReference<JsonObject>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath(); calls.add(path);
            String input=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            if (path.endsWith("/desktop_attendance_sheet") && legacy.get()) {
                var missing="{\"code\":\"PGRST202\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404,missing.length);
                exchange.getResponseBody().write(missing);
                exchange.close();
                return;
            }
            if (path.equals("/rest/v1/rpc/portal_access") && role.get().equals("unavailable")) {
                exchange.sendResponseHeaders(503,-1);
                exchange.close();
                return;
            }
            String answer=switch(path) {
                case "/auth/v1/token" -> "{\"access_token\":\"password-only-test-token\"}";
                case "/auth/v1/user" -> "{\"id\":\"8c94434b-a43f-4c36-b860-e7c2bd673a2c\",\"factors\":[{\"factor_type\":\"totp\",\"status\":\"verified\",\"id\":\"factor\"}]}";
                case "/auth/v1/factors/factor/challenge" -> "{\"id\":\"challenge\"}";
                case "/auth/v1/factors/factor/verify" -> "{\"access_token\":\"mfa-test-token\"}";
                case "/rest/v1/rpc/portal_access" -> new Gson().toJson(role.get());
                case "/rest/v1/rpc/portal_course_catalog" -> new Gson().toJson(List.of(Map.of("id",offering,"code","CS","term","Fall","section","A","title","Course")));
                case "/rest/v1/rpc/portal_course_lectures" -> new Gson().toJson(List.of(Map.of("id",lecture,"day","2026-09-14","title","Lecture")));
                case "/rest/v1/rpc/desktop_attendance_sheet" -> "{\"lectures\":[],\"students\":[],\"marks\":[]}";
                case "/rest/v1/rpc/desktop_save_attendance_sheet" -> {
                    var body=JsonParser.parseString(input).getAsJsonObject();
                    lastBatch.set(body);
                    yield saveReply.get();
                }
                case "/rest/v1/rpc/portal_attendance_register" -> {
                    assertEquals("Bearer mfa-test-token", exchange.getRequestHeaders().getFirst("Authorization"));
                    var mark = Map.of("enrollment_id", enrollment, "lecture_id", lecture, "course", "Course",
                            "lecture", "Lecture", "student", "Student", "day", "2026-09-14", "status", "PRESENT");
                    yield exchange.getRequestURI().getRawQuery().contains("offset=0")
                            ? new Gson().toJson(List.of(mark)) : "[]";
                }
                case "/rest/v1/rpc/desktop_change_attendance" -> {
                    var fields = JsonParser.parseString(input).getAsJsonObject();
                    assertEquals(enrollment.toString(), fields.get("target_enrollment").getAsString());
                    assertEquals(lecture.toString(), fields.get("target_lecture").getAsString());
                    assertEquals("PRESENT", fields.get("expected_status").getAsString());
                    yield "\"LATE\"";
                }
                case "/rest/v1/portal_published_grades" -> {
                    assertEquals("Bearer mfa-test-token",exchange.getRequestHeaders().getFirst("Authorization"));
                    assertEquals("GET", exchange.getRequestMethod());
                    yield "[{\"revision\":\""+revision+"\",\"score\":72,\"letter\":\"C\"}]";
                }
                case "/rest/v1/rpc/desktop_publish_grade" -> {
                    assertEquals("Bearer mfa-test-token",exchange.getRequestHeaders().getFirst("Authorization"));
                    publicationRequest.set(JsonParser.parseString(input).getAsJsonObject());
                    yield publicationStatus.get()==409 ? "{\"code\":\"PT409\"}" : "\""+revision+"\"";
                }
                default -> "";
            };
            byte[] output=answer.getBytes(StandardCharsets.UTF_8);
            int responseStatus = switch (path) {
                case "/rest/v1/rpc/desktop_save_attendance_sheet" -> saveStatus.get();
                case "/rest/v1/rpc/desktop_publish_grade" -> publicationStatus.get();
                default -> 200;
            };
            exchange.sendResponseHeaders(responseStatus,output.length==0?-1:output.length);
            if(output.length>0)exchange.getResponseBody().write(output);exchange.close();
        });
        server.start();
        try {
            var client=new PortalPublishingClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"sb_publishable_test",HttpClient.newHttpClient());
            var grade=new GradePublication(UUID.randomUUID(),UUID.randomUUID(),new BigDecimal("72"),"C");
            assertThrows(DomainValidationException.class,()->client.publish(grade,null));
            client.signIn("professor@example.invalid","test-only-password","123456");
            var mark = client.attendance().getFirst();
            assertEquals(offering,client.attendanceOfferings().getFirst().id());
            assertTrue(client.attendanceSheet(offering).complete());
            client.saveAttendanceSheet(offering,List.of(new PortalPublishingClient.AttendanceEdit(enrollment,lecture,"PRESENT",null)));
            assertTrue(lastBatch.get().getAsJsonArray("edits").get(0).getAsJsonObject().get("desired").isJsonNull());
            var batch = List.of(new PortalPublishingClient.AttendanceEdit(enrollment,lecture,"PRESENT","EXCUSED"),
                    new PortalPublishingClient.AttendanceEdit(enrollment,UUID.randomUUID(),"ABSENT","EXCUSED"));
            saveStatus.set(409);
            saveReply.set("{\"code\":\"PT409\"}");
            int savesBefore = Collections.frequency(calls,"/rest/v1/rpc/desktop_save_attendance_sheet");
            assertThrows(PortalPublishingClient.AttendanceConflict.class, () -> client.saveAttendanceSheet(offering,batch));
            assertEquals(savesBefore+1,Collections.frequency(calls,"/rest/v1/rpc/desktop_save_attendance_sheet"));
            assertEquals(offering.toString(),lastBatch.get().get("target").getAsString());
            assertEquals(new Gson().toJsonTree(batch),lastBatch.get().get("edits"));
            saveReply.set("{\"code\":\"23505\"}");
            assertThrowsExactly(DomainValidationException.class, () -> client.saveAttendanceSheet(offering,batch));
            saveStatus.set(503);
            assertThrows(java.io.IOException.class, () -> client.saveAttendanceSheet(offering,batch));
            saveStatus.set(200);
            saveReply.set("2.5");
            assertThrowsExactly(DomainValidationException.class, () -> client.saveAttendanceSheet(offering,batch));
            saveReply.set("2");
            client.saveAttendanceSheet(offering,batch);
            legacy.set(true);
            var recorded=client.attendanceSheet(offering);
            assertFalse(recorded.complete());
            assertEquals(enrollment,recorded.students().getFirst().id());
            assertEquals(lecture,recorded.marks().getFirst().lecture());
            assertEquals("LATE", client.changeAttendance(mark, "LATE"));
            assertThrows(DomainValidationException.class, () -> client.changeAttendance(mark, "EXCUSED"));
            role.set("unavailable");
            assertThrows(java.io.IOException.class, client::authorizedProfessor);
            role.set("professor");
            assertTrue(client.authorizedProfessor());
            assertEquals(revision,client.currentPublication(grade).orElseThrow().revision());
            assertEquals(revision,client.publish(grade,null));
            assertTrue(publicationRequest.get().get("expected_revision").isJsonNull());
            publicationStatus.set(409);
            int publicationsBefore = Collections.frequency(calls,"/rest/v1/rpc/desktop_publish_grade");
            assertThrows(PortalPublishingClient.PublicationConflict.class,()->client.publish(grade,revision));
            assertEquals(publicationsBefore+1,Collections.frequency(calls,"/rest/v1/rpc/desktop_publish_grade"));
            assertEquals(revision.toString(),publicationRequest.get().get("expected_revision").getAsString());
            assertEquals(grade.score(),publicationRequest.get().get("new_score").getAsBigDecimal());
            client.signOut();
            assertEquals("/auth/v1/logout",calls.getLast());
            assertThrows(DomainValidationException.class,()->client.publish(grade,revision));
            role.set("student");
            assertThrows(DomainValidationException.class, () ->
                    client.signIn("student@example.invalid","test-only-password","123456"));
            assertThrows(DomainValidationException.class,client::attendance);
            assertThrows(DomainValidationException.class,()->client.changeAttendance(mark,"LATE"));
            client.signOut();
        } finally {server.stop(0);}
    }
}
