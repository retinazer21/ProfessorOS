package com.professoros.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceProjectionTest {
    private static JsonObject workspace() {
        var snapshot = new JsonObject();
        for (var name : ("courses academic_terms course_offerings students enrollments lectures attendance questions "
                + "question_choices exams exam_questions exam_question_choices assessment_categories assessments "
                + "student_grades grading_scales").split(" ")) snapshot.add(name, new JsonArray());
        var course = new JsonObject();
        course.addProperty("id", 1);
        course.addProperty("course_code", "CS101");
        course.addProperty("course_name", "Foundations");
        course.addProperty("credit_hours", 3);
        course.addProperty("description", "");
        course.addProperty("department", "");
        course.addProperty("created_at", "2026-10-01T00:00:00Z");
        course.addProperty("updated_at", "2026-10-01T00:00:00Z");
        snapshot.getAsJsonArray("courses").add(course);
        return snapshot;
    }

    @Test void rejectedRefreshPreservesRowsAndSeparateSessionsCannotSeeThem() throws Exception {
        var original = workspace();
        var empty = workspace();
        empty.add("courses", new JsonArray());
        try (var session = new WorkspaceProjection(original); var other = new WorkspaceProjection(empty)) {
            var invalid = workspace();
            invalid.getAsJsonArray("courses").get(0).getAsJsonObject().addProperty("credit_hours", 0);
            assertThrows(SQLException.class, () -> session.replace(invalid));
            assertEquals(original, session.records());
            assertEquals(empty, other.records());
            try (var connection = session.readOnlyDatabase().openConnection(); var change = connection.createStatement()) {
                assertThrows(SQLException.class, () -> change.executeUpdate("DELETE FROM courses"));
            }
        }
    }
}
