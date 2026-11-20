package com.professoros.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Session-local read model for the native academic services. Closing it releases all records. */
public final class WorkspaceProjection implements AutoCloseable {
    private static final List<String> TABLES = List.of("courses", "academic_terms", "course_offerings",
            "students", "enrollments", "lectures", "attendance", "questions", "question_choices",
            "exams", "exam_questions", "exam_question_choices", "assessment_categories", "assessments",
            "student_grades", "grading_scales");
    private final Database database = Database.transientWorkspace();
    private final Connection keeper;
    private final Map<String, List<String>> columns = new LinkedHashMap<>();

    public WorkspaceProjection(JsonObject snapshot) throws SQLException {
        keeper = database.openConnection();
        try {
            database.initialize();
            try (var statement = keeper.createStatement()) {
                for (var table : TABLES) {
                    var names = new ArrayList<String>();
                    try (var fields = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
                        while (fields.next()) names.add(fields.getString("name"));
                    }
                    if (names.isEmpty()) throw new SQLException("Missing projection table: " + table);
                    columns.put(table, List.copyOf(names));
                }
            }
            replace(snapshot);
        } catch (SQLException | RuntimeException failure) {
            try { keeper.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    public Database readOnlyDatabase() { return database.readOnlySessionView(); }

    /** For a disposable staging projection only. The displayed view uses readOnlyDatabase(). */
    public Database stagingDatabase() { return database; }

    /** Replace the complete projection or keep the previous one on a malformed response. */
    public synchronized void replace(JsonObject snapshot) throws SQLException {
        if (!columns.keySet().equals(snapshot.keySet())) throw new SQLException("Unexpected workspace tables");
        try (var db = database.openConnection(); var statement = db.createStatement()) {
            db.setAutoCommit(false);
            try {
                statement.execute("PRAGMA defer_foreign_keys=ON");
                for (var table : TABLES.reversed()) statement.executeUpdate("DELETE FROM " + table);
                for (var table : TABLES) {
                    var fields = columns.get(table);
                    var placeholders = String.join(",", Collections.nCopies(fields.size(), "?"));
                    var sql = "INSERT INTO " + table + "(" + String.join(",", fields) + ") VALUES (" + placeholders + ")";
                    try (var insert = db.prepareStatement(sql)) {
                        for (JsonElement item : snapshot.getAsJsonArray(table)) {
                            var row = item.getAsJsonObject();
                            if (row.size() != fields.size() || !row.keySet().containsAll(fields))
                                throw new SQLException("Unexpected fields in " + table);
                            for (int i = 0; i < fields.size(); i++) {
                                var value = row.get(fields.get(i));
                                if (value.isJsonNull()) insert.setNull(i + 1, Types.NULL);
                                else if (value.getAsJsonPrimitive().isNumber()) insert.setLong(i + 1, value.getAsBigDecimal().longValueExact());
                                else if (value.getAsJsonPrimitive().isString()) insert.setString(i + 1, value.getAsString());
                                else throw new SQLException("Invalid workspace field in " + table);
                            }
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                }
                db.commit();
            } catch (SQLException | RuntimeException failure) {
                db.rollback();
                throw failure;
            }
        }
    }

    public synchronized JsonObject records() throws SQLException {
        var result = new JsonObject();
        try (var db = database.openConnection(); var statement = db.createStatement()) {
            db.setAutoCommit(false);
            for (var table : TABLES) {
                var rows = new JsonArray();
                try (var records = statement.executeQuery("SELECT * FROM " + table + " ORDER BY rowid")) {
                    while (records.next()) {
                        var row = new JsonObject();
                        for (var field : columns.get(table)) {
                            var value = records.getObject(field);
                            if (value == null) row.add(field, JsonNull.INSTANCE);
                            else if (value instanceof Number number) row.addProperty(field, number);
                            else row.addProperty(field, value.toString());
                        }
                        rows.add(row);
                    }
                }
                result.add(table, rows);
            }
            db.commit();
        }
        return result;
    }

    @Override public synchronized void close() throws SQLException { keeper.close(); }
}
