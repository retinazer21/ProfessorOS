package com.professoros.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.professoros.database.Database;
import com.professoros.database.WorkspaceProjection;
import com.professoros.model.DomainValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Runs the existing academic services against an isolated draft and commits only the validated delta. */
public final class CloudAcademicWrites {
    private static final Set<String> ENTITIES = Set.of("courses", "academic_terms", "students", "course_offerings", "lectures", "enrollments");
    private static final Set<String> GRADEBOOK = Set.of("assessment_categories", "assessments", "student_grades", "grading_scales");
    private static final Set<String> EXAMS = Set.of("exams", "exam_questions", "exam_question_choices");
    private static final Set<String> QUESTIONS = Set.of("questions", "question_choices");
    private final PortalPublishingClient client;
    private final WorkspaceProjection view;

    public CloudAcademicWrites(PortalPublishingClient client, WorkspaceProjection view) {
        this.client = client;
        this.view = view;
    }

    public void save(Consumer<Database> operation) throws Exception {
        var previous = view.records();
        try (var draft = new WorkspaceProjection(previous)) {
            operation.accept(draft.stagingDatabase());
            var desired = draft.records();
            var edits = workspaceChanges(previous, desired);
            if (edits.isEmpty()) return;
            var exam = rootEdit(edits,previous,desired,"exams",EXAMS,"exam_id");
            if (exam != null) {
                var old = nullableRow(exam,"previous");
                var next = nullableRow(exam,"desired");
                var selection = new JsonArray();
                if (next != null && "DRAFT".equals(next.get("status").getAsString())) {
                    var sources = index(previous.getAsJsonArray("questions"),"questions");
                    children(desired,"exam_questions","exam_id",next.get("id").getAsLong()).forEach(question -> {
                        var choice = new JsonObject();
                        choice.add("question_id",question.get("question_id"));
                        choice.add("marks_hundredths",question.get("marks_hundredths"));
                        choice.add("source",sources.get(question.get("question_id").getAsString()));
                        selection.add(choice);
                    });
                }
                view.replace(client.writeExam(old,next,selection));
                return;
            }
            var question = rootEdit(edits,previous,desired,"questions",QUESTIONS,"question_id");
            if (question != null) {
                var old = nullableRow(question,"previous");
                var next = nullableRow(question,"desired");
                var choices = new JsonArray();
                if (next != null) children(desired,"question_choices","question_id",next.get("id").getAsLong())
                        .forEach(row -> choices.add(row.get("choice_text")));
                view.replace(client.writeQuestion(old,next,choices));
                return;
            }
            if (GRADEBOOK.contains(edits.get(0).getAsJsonObject().get("entity").getAsString())) {
                view.replace(client.writeGradebook(edits));
                return;
            }
            var catalog = new JsonArray();
            var enrollments = new JsonArray();
            var students = index(desired.getAsJsonArray("students"),"students");
            for (var item : edits) {
                var edit = item.getAsJsonObject();
                if (!edit.get("entity").getAsString().equals("enrollments")) {
                    catalog.add(edit);
                    continue;
                }
                var enrollment = edit.getAsJsonObject("desired");
                edit.add("student_number", students.get(enrollment.get("student_id").getAsString()).get("student_number"));
                enrollments.add(edit);
            }
            view.replace(client.writeCatalog(catalog, enrollments));
        }
    }

    static JsonArray workspaceChanges(JsonObject previous, JsonObject desired) {
        var edits = new JsonArray();
        for (var entity : previous.keySet()) {
            if (previous.get(entity).equals(desired.get(entity))) continue;
            if (!ENTITIES.contains(entity) && !GRADEBOOK.contains(entity)
                    && !EXAMS.contains(entity) && !QUESTIONS.contains(entity))
                throw new DomainValidationException("This operation changes records outside the online academic workspace. No changes were saved.");
            var after = index(desired.getAsJsonArray(entity),entity);
            index(previous.getAsJsonArray(entity),entity).forEach((id, before) -> {
                var next = after.remove(id);
                if (!before.equals(next)) edits.add(edit(entity, before, next));
            });
            after.values().forEach(next -> edits.add(edit(entity, null, next)));
        }
        return edits;
    }

    private static Map<String, JsonObject> index(JsonArray rows, String entity) {
        var result = new LinkedHashMap<String, JsonObject>();
        rows.forEach(row -> {
            var value = row.getAsJsonObject();
            String key = switch (entity) {
                case "student_grades" -> value.get("enrollment_id").getAsString()+":"+value.get("assessment_id").getAsString();
                case "grading_scales" -> value.get("course_offering_id").getAsString();
                case "question_choices" -> value.get("question_id").getAsString()+":"+value.get("position").getAsString();
                case "exam_questions" -> value.get("exam_id").getAsString()+":"+value.get("question_id").getAsString();
                case "exam_question_choices" -> value.get("exam_id").getAsString()+":"+value.get("question_id").getAsString()+":"+value.get("position").getAsString();
                default -> value.get("id").getAsString();
            };
            result.put(key, value);
        });
        return result;
    }

    private static JsonObject rootEdit(JsonArray edits, JsonObject previous, JsonObject desired,
                                       String root, Set<String> domain, String parentKey) {
        var children = edits.asList().stream().map(item -> item.getAsJsonObject())
                .filter(edit -> domain.contains(edit.get("entity").getAsString())).toList();
        if (children.isEmpty()) return null;
        for (var edit : children) if (root.equals(edit.get("entity").getAsString())) return edit;
        var child = children.getFirst();
        var row = nullableRow(child,"desired");
        String id = (row == null ? nullableRow(child,"previous") : row).get(parentKey).getAsString();
        return edit(root,index(previous.getAsJsonArray(root),root).get(id),index(desired.getAsJsonArray(root),root).get(id));
    }

    private static JsonObject nullableRow(JsonObject edit, String field) {
        return edit.get(field).isJsonNull() ? null : edit.getAsJsonObject(field);
    }

    private static java.util.stream.Stream<JsonObject> children(JsonObject workspace, String table, String key, long id) {
        return workspace.getAsJsonArray(table).asList().stream().map(item -> item.getAsJsonObject())
                .filter(row -> row.get(key).getAsLong()==id)
                .sorted(java.util.Comparator.comparingInt(row -> row.get("position").getAsInt()));
    }

    private static JsonObject edit(String entity, JsonObject previous, JsonObject desired) {
        var result = new JsonObject();
        result.addProperty("entity", entity);
        result.add("previous", previous == null ? JsonNull.INSTANCE : previous);
        result.add("desired", desired == null ? JsonNull.INSTANCE : desired);
        return result;
    }
}
