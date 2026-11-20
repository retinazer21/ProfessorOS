package com.professoros.service;

import com.google.gson.*;
import com.professoros.model.DomainValidationException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/** Optional online boundary. Only publishable configuration and short-lived user tokens are used.
 * Passwords/tokens are never persisted or logged. Redirects are never followed. */
public final class PortalPublishingClient {
    private static final String SAVE_ATTENDANCE = "/rest/v1/rpc/desktop_save_attendance_sheet";
    private static final String PUBLISH_GRADE = "/rest/v1/rpc/desktop_publish_grade";
    private static final String WRITE_CATALOG = "/rest/v1/rpc/desktop_write_catalog";
    private static final String WRITE_ROSTER = "/rest/v1/rpc/desktop_write_roster";
    private static final String WRITE_GRADEBOOK = "/rest/v1/rpc/desktop_write_gradebook";
    private static final String WRITE_QUESTION = "/rest/v1/rpc/desktop_write_question";
    private static final String WRITE_EXAM = "/rest/v1/rpc/desktop_write_exam";

    public static final class PublicationConflict extends DomainValidationException {
        private PublicationConflict() {
            super("The published grade changed. Your grade is still pending. Load the latest publication and review it before confirming again.");
        }
    }

    public static final class AttendanceConflict extends DomainValidationException {
        private AttendanceConflict() {
            super("Attendance changed since you opened the register. No edits in this batch were saved. Refresh to review your pending edits.");
        }
    }
    private final URI base;
    private final String key;
    private final HttpClient http;
    private final Gson json = new Gson();
    private String token;
    private UUID account;
    private boolean professorAuthorized;
    private String refreshToken;
    private long refreshAfter;
    private Path imageDirectory;

    private void acceptSession(JsonObject response) {
        token = response.get("access_token").getAsString();
        refreshToken = response.has("refresh_token") ? response.get("refresh_token").getAsString() : null;
        long lifetime = response.has("expires_in") ? response.get("expires_in").getAsLong() : 3600;
        refreshAfter = java.time.Instant.now().getEpochSecond() + Math.max(1, lifetime - 60);
    }

    private void refreshIfDue() throws Exception {
        if (refreshToken != null && java.time.Instant.now().getEpochSecond() >= refreshAfter) {
            var response = request("POST", "/auth/v1/token?grant_type=refresh_token",
                    Map.of("refresh_token", refreshToken), null);
            acceptSession(response.getAsJsonObject());
        }
    }

    public PortalPublishingClient(String url, String publishableKey) {
        this(URI.create(url == null ? "" : url), publishableKey,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build());
        if (!"https".equals(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null || !base.getPath().isEmpty()
                || publishableKey == null || !publishableKey.startsWith("sb_publishable_")) {
            throw new DomainValidationException("Configure the HTTPS Supabase project origin and publishable key.");
        }
    }
    // Package-private transport injection for isolated HTTP contract tests, never used by the UI.
    PortalPublishingClient(URI origin, String publishableKey, HttpClient transport) {
        base = origin; key = publishableKey; http = transport;
    }
    private JsonElement request(String method, String path, Object body, String prefer) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30))
                .header("apikey", key).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (prefer != null) builder.header("Prefer", prefer);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body instanceof JsonElement element ? element.toString() : json.toJson(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 409 && path.equals(SAVE_ATTENDANCE) && hasErrorCode(response.body(), "PT409"))
            throw new AttendanceConflict();
        if (status == 409 && path.equals(PUBLISH_GRADE) && hasErrorCode(response.body(), "PT409"))
            throw new PublicationConflict();
        if (status == 409 && List.of(WRITE_CATALOG,WRITE_ROSTER,WRITE_GRADEBOOK,WRITE_QUESTION,WRITE_EXAM).contains(path))
            throw new DomainValidationException("A record changed or already exists. Your edits are still pending. Refresh and review before saving.");
        if (status >= 500 || status == 408 || status == 429) {
            throw new java.io.IOException("Supabase is temporarily unavailable (HTTP " + status + ").");
        }
        if (status == 404 && path.equals("/rest/v1/rpc/desktop_attendance_sheet")
                && hasErrorCode(response.body(), "PGRST202")) {
            throw new MissingAttendanceSheet();
        }
        if (status < 200 || status >= 300)
            throw new DomainValidationException("Portal request rejected (HTTP " + response.statusCode() + "). Check sign-in, MFA, mapping and connectivity; no success is assumed.");
        return response.body().isBlank() ? JsonNull.INSTANCE : JsonParser.parseString(response.body());
    }

    private static boolean hasErrorCode(String body, String expected) {
        try {
            var error = JsonParser.parseString(body);
            return error.isJsonObject() && new JsonPrimitive(expected).equals(error.getAsJsonObject().get("code"));
        } catch (JsonParseException invalidResponse) {
            return false;
        }
    }
    public synchronized void signIn(String email, String password, String code) throws Exception {
        if (token != null) throw new IllegalStateException("Sign out before signing in again.");
        if (email == null || email.isBlank() || password == null || password.isBlank() || code == null || !code.matches("[0-9]{6}"))
            throw new DomainValidationException("Enter professor email, password and current six-digit code.");
        var signed = request("POST", "/auth/v1/token?grant_type=password", Map.of("email", email.strip(), "password", password), null).getAsJsonObject();
        acceptSession(signed);
        var user = request("GET", "/auth/v1/user", null, null).getAsJsonObject();
        account=UUID.fromString(user.get("id").getAsString());
        String factor = null;
        if (user.has("factors")) for (var item : user.getAsJsonArray("factors")) {
            var f = item.getAsJsonObject();
            if ("totp".equals(f.get("factor_type").getAsString()) && "verified".equals(f.get("status").getAsString())) {
                factor = f.get("id").getAsString(); break;
            }
        }
        if (factor == null) throw new DomainValidationException("Enroll a professor authenticator in the portal first.");
        var challenge = request("POST", "/auth/v1/factors/" + factor + "/challenge", Map.of(), null).getAsJsonObject();
        var verified = request("POST", "/auth/v1/factors/" + factor + "/verify", Map.of("challenge_id", challenge.get("id").getAsString(), "code", code), null).getAsJsonObject();
        acceptSession(verified);
        if (!authorizedProfessor())
            throw new DomainValidationException("An MFA-verified professor role is required.");
        professorAuthorized = true;
    }
    public synchronized UUID professorId() {
        if (!professorAuthorized) throw new DomainValidationException("Professor verification is required.");
        return account;
    }
    public synchronized boolean authorizedProfessor() throws Exception {
        if (token == null) return false;
        refreshIfDue();
        var role = request("POST", "/rest/v1/rpc/portal_access", Map.of(), null);
        return role.isJsonPrimitive() && "professor".equals(role.getAsString());
    }

    public record AttendanceMark(java.util.UUID enrollment, java.util.UUID lecture, String course,
                                 String lectureTitle, String student, String day, String status) {}

    public record Offering(UUID id, String title, String code, String term, String section) {}
    public record RegisterStudent(UUID id, String name, String number, boolean active) {}
    public record RegisterLecture(UUID id, String title, String day, boolean editable) {}
    public record RegisterCell(UUID enrollment, UUID lecture, String status) {}
    public record AttendanceSheet(List<RegisterStudent> students, List<RegisterLecture> lectures,
                                  List<RegisterCell> marks, boolean complete) {}
    public record AttendanceEdit(UUID enrollment, UUID lecture, String expected, String desired) {}
    private static final class MissingAttendanceSheet extends Exception {}

    private void requireProfessor() throws Exception {
        if (!professorAuthorized || !authorizedProfessor())
            throw new DomainValidationException("Professor authorization has expired. Sign in again.");
    }

    public synchronized List<Offering> attendanceOfferings() throws Exception {
        requireProfessor();
        return java.util.Arrays.stream(json.fromJson(request("GET",
                "/rest/v1/rpc/portal_course_catalog?order=term,code,section,id", null, null), Offering[].class))
                .filter(o -> o.code() != null && o.term() != null && o.section() != null).toList();
    }

    /** Full private academic projection, restricted by the database to the assigned MFA workspace owner. */
    public synchronized JsonObject readWorkspace() throws Exception {
        requireProfessor();
        var response = request("POST", "/rest/v1/rpc/desktop_read_workspace", Map.of(), null);
        if (!response.isJsonObject())
            throw new DomainValidationException("Supabase did not return the academic workspace.");
        return materializeImages(response.getAsJsonObject());
    }

    public com.professoros.database.WorkspaceProjection openWorkspaceView() throws Exception {
        return new com.professoros.database.WorkspaceProjection(readWorkspace());
    }

    public void refreshWorkspaceView(com.professoros.database.WorkspaceProjection view) throws Exception {
        view.replace(readWorkspace());
    }

    synchronized JsonObject writeCatalog(JsonArray edits, JsonArray enrollments) throws Exception {
        requireProfessor();
        var payload = new JsonObject();
        String path = WRITE_CATALOG;
        if (enrollments.isEmpty()) payload.add("changes", edits);
        else {
            path = WRITE_ROSTER;
            payload.add("catalog_changes", edits);
            payload.add("enrollment_changes", enrollments);
        }
        return writeAcademic(path, payload);
    }

    synchronized JsonObject writeGradebook(JsonArray edits) throws Exception {
        requireProfessor();
        var payload = new JsonObject();
        payload.add("changes", edits);
        return writeAcademic(WRITE_GRADEBOOK, payload);
    }

    synchronized JsonObject writeQuestion(JsonObject previous, JsonObject desired, JsonArray choices) throws Exception {
        requireProfessor();
        var payload = new JsonObject();
        var old = normalizeImagePath(previous);
        var next = normalizeImagePath(desired);
        payload.add("previous",old);
        payload.add("desired",next);
        payload.add("choices",choices);
        String encoded = null;
        if (desired != null) {
            String path = desired.get("image_path").getAsString();
            if (!path.isBlank() && path.equals(next.get("image_path").getAsString())) {
                com.professoros.pdf.PdfImages.read(path,"Question image");
                var image = Files.readAllBytes(Path.of(path));
                if (image.length > 10*1024*1024) throw new DomainValidationException("Question image exceeds 10 MB.");
                encoded = java.util.Base64.getEncoder().encodeToString(image);
                next.addProperty("image_path","");
            }
        }
        payload.addProperty("encoded_image",encoded);
        return writeAcademic(WRITE_QUESTION,payload);
    }

    synchronized JsonObject writeExam(JsonObject previous, JsonObject desired, JsonArray selection) throws Exception {
        requireProfessor();
        var payload = new JsonObject();
        payload.add("previous",previous);
        payload.add("desired",desired);
        payload.add("selection",selection);
        for (var entry : selection) {
            var item = entry.getAsJsonObject();
            item.add("source", normalizeImagePath(item.getAsJsonObject("source")));
        }
        return writeAcademic(WRITE_EXAM,payload);
    }

    private JsonObject normalizeImagePath(JsonObject question) {
        if (question == null) return null;
        var normalized = question.deepCopy();
        var field = question.get("image_path");
        if (imageDirectory != null && field != null && !field.getAsString().isBlank()) {
            Path path = Path.of(field.getAsString());
            if (imageDirectory.equals(path.getParent()) && path.getFileName().toString().matches("[a-f0-9]{64}"))
                normalized.addProperty("image_path",path.getFileName().toString());
        }
        return normalized;
    }

    private JsonObject materializeImages(JsonObject snapshot) throws Exception {
        for (String table : List.of("questions","exam_questions")) {
            if (!snapshot.has(table)) continue;
            for (var row : snapshot.getAsJsonArray(table)) {
                var question = row.getAsJsonObject();
                String key = question.get("image_path").getAsString();
                if (key.isBlank()) continue;
                if (!key.matches("[a-f0-9]{64}")) throw new DomainValidationException("Invalid question image reference.");
                if (imageDirectory == null) imageDirectory = Files.createTempDirectory("professor-os-exam-images-");
                Path target = imageDirectory.resolve(key);
                if (!Files.exists(target)) {
                    var encoded = request("POST","/rest/v1/rpc/desktop_read_exam_image",Map.of("image_digest",key),null);
                    if (!encoded.isJsonPrimitive()) throw new DomainValidationException("Question image could not be loaded.");
                    var bytes = java.util.Base64.getMimeDecoder().decode(encoded.getAsString());
                    var sha256 = java.security.MessageDigest.getInstance("SHA-256");
                    if (bytes.length > 10*1024*1024 || !key.equals(java.util.HexFormat.of().formatHex(sha256.digest(bytes))))
                        throw new DomainValidationException("Question image failed verification.");
                    Files.write(target,bytes);
                }
                question.addProperty("image_path",target.toString());
            }
        }
        return snapshot;
    }

    private JsonObject writeAcademic(String path, JsonObject payload) throws Exception {
        var response = request("POST", path, payload, null);
        if (!response.isJsonObject()) throw new DomainValidationException("Save could not be confirmed. Refresh before retrying.");
        return materializeImages(response.getAsJsonObject());
    }

    public synchronized AttendanceSheet attendanceSheet(UUID offering) throws Exception {
        requireProfessor();
        try {
            var sheet = json.fromJson(request("POST", "/rest/v1/rpc/desktop_attendance_sheet",
                    Map.of("target", offering), null), AttendanceSheet.class);
            return new AttendanceSheet(List.copyOf(sheet.students()), List.copyOf(sheet.lectures()), List.copyOf(sheet.marks()), true);
        } catch (MissingAttendanceSheet missing) {
            // Deployed v1 exposes recorded cells only. Correlate by lecture UUID, never by course or student name.
            var lectures = new java.util.LinkedHashMap<UUID, RegisterLecture>();
            for (var item : request("GET","/rest/v1/rpc/portal_course_lectures?target="+offering+"&order=day,number",null,null).getAsJsonArray()) {
                var row = item.getAsJsonObject();
                var id = UUID.fromString(row.get("id").getAsString());
                lectures.put(id,new RegisterLecture(id,row.get("title").getAsString(),row.get("day").getAsString(),true));
            }
            var students = new java.util.LinkedHashMap<UUID, RegisterStudent>();
            var cells = new java.util.ArrayList<RegisterCell>();
            var ids = List.copyOf(lectures.keySet());
            for (int start=0; start<ids.size(); start+=40) {
                var slice=ids.subList(start,Math.min(start+40,ids.size()));
                var filter=slice.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
                for (var mark : attendanceRows("&lecture_id=in.("+filter+")")) {
                    if (!lectures.containsKey(mark.lecture())) continue;
                    students.putIfAbsent(mark.enrollment(),new RegisterStudent(mark.enrollment(),mark.student(),"",true));
                    cells.add(new RegisterCell(mark.enrollment(),mark.lecture(),mark.status()));
                }
            }
            return new AttendanceSheet(List.copyOf(students.values()), List.copyOf(lectures.values()),List.copyOf(cells),false);
        }
    }

    public synchronized void saveAttendanceSheet(UUID offering, List<AttendanceEdit> edits) throws Exception {
        requireProfessor();
        var payload = new JsonArray();
        for (var change : edits) {
            var cell = new JsonObject();
            cell.addProperty("enrollment",change.enrollment().toString());
            cell.addProperty("lecture",change.lecture().toString());
            cell.addProperty("expected",change.expected());
            cell.addProperty("desired",change.desired());
            payload.add(cell);
        }
        var body = new JsonObject();
        body.addProperty("target",offering.toString());
        body.add("edits",payload);
        var result = request("POST",SAVE_ATTENDANCE,body,null);
        if (!result.isJsonPrimitive() || !result.getAsJsonPrimitive().isNumber()
                || result.getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(edits.size())) != 0)
            throw new DomainValidationException("Save unconfirmed. Refresh attendance before retrying.");
    }

    public synchronized java.util.List<AttendanceMark> attendance() throws Exception {
        requireProfessor();
        return attendanceRows("");
    }
    private List<AttendanceMark> attendanceRows(String filter) throws Exception {
        var rows = new java.util.ArrayList<AttendanceMark>();
        while (true) {
            var page = request("GET", "/rest/v1/rpc/portal_attendance_register?order=enrollment_id,lecture_id&limit=200&offset=" + rows.size()+filter, null, null).getAsJsonArray();
            if (page.isEmpty()) return java.util.List.copyOf(rows);
            for (var item : page) {
                var row = item.getAsJsonObject();
                rows.add(new AttendanceMark(java.util.UUID.fromString(row.get("enrollment_id").getAsString()),
                    java.util.UUID.fromString(row.get("lecture_id").getAsString()),row.get("course").getAsString(),
                    row.get("lecture").getAsString(),row.get("student").getAsString(),row.get("day").getAsString(),
                    row.get("status").getAsString()));
            }
        }
    }

    public synchronized String changeAttendance(AttendanceMark original, String status) throws Exception {
        if (!professorAuthorized) throw new DomainValidationException("Sign in as a professor first.");
        refreshIfDue();
        var result = request("POST", "/rest/v1/rpc/desktop_change_attendance",
            Map.of("target_enrollment",original.enrollment(),"target_lecture",original.lecture(),
                "expected_status",original.status(),"new_status",status), null);
        if (!result.isJsonPrimitive() || !status.equals(result.getAsString()))
            throw new DomainValidationException("Attendance change was not confirmed. Refresh before retrying.");
        return result.getAsString();
    }
    public record PublishedGrade(UUID revision, java.math.BigDecimal score, String letter) {}
    public record PublicationTarget(UUID offering, UUID student) {}

    public synchronized PublicationTarget publicationTarget(long offering, long enrollment) throws Exception {
        requireProfessor();
        var result = request("POST", "/rest/v1/rpc/desktop_grade_publication_target",
                Map.of("offering_local_id",offering,"enrollment_local_id",enrollment), null);
        if (!result.isJsonObject())
            throw new DomainValidationException("This enrollment has no active Student Portal account mapping.");
        var mapped = json.fromJson(result, PublicationTarget.class);
        if (mapped.offering() == null || mapped.student() == null)
            throw new DomainValidationException("The portal account mapping could not be verified.");
        return mapped;
    }

    public synchronized java.util.Optional<PublishedGrade> currentPublication(GradePublication grade) throws Exception {
        requireProfessor();
        var rows = request("GET", "/rest/v1/portal_published_grades?select=revision,score,letter&offering_id=eq."
                + grade.offering() + "&student_id=eq." + grade.student(), null, null).getAsJsonArray();
        if (rows.isEmpty()) return java.util.Optional.empty();
        if (rows.size() != 1) throw new DomainValidationException("Unexpected publication response.");
        var published = json.fromJson(rows.get(0), PublishedGrade.class);
        if (published.revision() == null || published.score() == null || published.letter() == null)
            throw new DomainValidationException("The current publication could not be verified.");
        return java.util.Optional.of(published);
    }

    public synchronized UUID publish(GradePublication publication, UUID expectedRevision) throws Exception {
        requireProfessor();
        var payload = new JsonObject();
        payload.addProperty("target_offering", publication.offering().toString());
        payload.addProperty("target_student", publication.student().toString());
        payload.addProperty("expected_revision", expectedRevision == null ? null : expectedRevision.toString());
        payload.addProperty("new_score", publication.score());
        payload.addProperty("new_letter", publication.letter());
        var result = request("POST", PUBLISH_GRADE, payload, null);
        if (!result.isJsonPrimitive() || !result.getAsJsonPrimitive().isString())
            throw new DomainValidationException("Publication was not confirmed. Load the latest publication before trying again.");
        return UUID.fromString(result.getAsString());
    }
    public synchronized void signOut() throws Exception {
        if (token == null) return;
        try { request("POST", "/auth/v1/logout?scope=local", Map.of(), null); }
        finally {
            token = null;
            refreshToken = null;
            account = null;
            professorAuthorized = false;
            if (imageDirectory != null) {
                try {
                    List<Path> cached;
                    try (var images = Files.list(imageDirectory)) { cached = images.toList(); }
                    for (Path image : cached) Files.deleteIfExists(image);
                    Files.deleteIfExists(imageDirectory);
                } catch (java.io.IOException cleanupFailure) {
                    java.util.logging.Logger.getLogger(PortalPublishingClient.class.getName()).warning("Could not remove the local exam image cache.");
                } finally { imageDirectory = null; }
            }
        }
    }
}
