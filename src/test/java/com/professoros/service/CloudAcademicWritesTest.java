package com.professoros.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.professoros.database.WorkspaceProjection;
import com.professoros.model.CourseInput;
import com.professoros.model.DomainValidationException;
import com.professoros.repository.CourseRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CloudAcademicWritesTest {
    @Test void serviceSaveUsesServerAssignedIdAndPreservesViewOnConflict() throws Exception {
        var records = new JsonObject();
        for (var table : ("courses academic_terms course_offerings students enrollments lectures attendance questions "
                +"question_choices exams exam_questions exam_question_choices assessment_categories assessments student_grades grading_scales").split(" "))
            records.add(table,new JsonArray());
        var responseStatus = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",request -> {
            String path = request.getRequestURI().getPath();
            int status = 200;
            String response = switch (path) {
                case "/auth/v1/token" -> "{\"access_token\":\"temporary\"}";
                case "/auth/v1/user" -> "{\"id\":\"8c94434b-a43f-4c36-b860-e7c2bd673a2c\",\"factors\":[{\"factor_type\":\"totp\",\"status\":\"verified\",\"id\":\"factor\"}]}";
                case "/auth/v1/factors/factor/challenge" -> "{\"id\":\"challenge\"}";
                case "/auth/v1/factors/factor/verify" -> "{\"access_token\":\"verified\"}";
                case "/rest/v1/rpc/portal_access" -> "\"professor\"";
                case "/rest/v1/rpc/desktop_write_catalog" -> {
                    assertEquals("Bearer verified",request.getRequestHeaders().getFirst("Authorization"));
                    var payload = JsonParser.parseString(new String(request.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                    var edit = payload.getAsJsonArray("changes").get(0).getAsJsonObject();
                    status = responseStatus.get();
                    if (status == 409) {
                        assertEquals(100,edit.getAsJsonObject("previous").get("id").getAsLong());
                        yield "{\"code\":\"PT409\"}";
                    }
                    assertTrue(edit.get("previous").isJsonNull());
                    var committed = records.deepCopy();
                    var row = edit.getAsJsonObject("desired");
                    assertEquals("CS200",row.get("course_code").getAsString());
                    row.addProperty("id",100);
                    committed.getAsJsonArray("courses").add(row);
                    yield committed.toString();
                }
                default -> throw new AssertionError("Unexpected request: " + path);
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(status,bytes.length);
            try (var body = request.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        var client = new PortalPublishingClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),
                "sb_publishable_test",HttpClient.newHttpClient());
        try (var view = new WorkspaceProjection(records)) {
            client.signIn("professor@example.invalid","fictional","123456");
            var remote = new CloudAcademicWrites(client,view);
            remote.save(draft -> new CourseService(new CourseRepository(draft))
                    .create(new CourseInput("cs200","Cloud course","",3,"")));
            var reader = new CourseService(new CourseRepository(view.readOnlyDatabase()));
            assertEquals("Cloud course",reader.findById(100).courseName());
            responseStatus.set(409);
            assertThrows(DomainValidationException.class,() ->
                    remote.save(draft -> new CourseService(new CourseRepository(draft))
                            .update(100,new CourseInput("CS200","Pending rename","",3,""))));
            assertEquals("Cloud course",reader.findById(100).courseName());
        } finally { server.stop(0); }
    }
}
