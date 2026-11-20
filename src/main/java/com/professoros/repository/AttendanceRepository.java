package com.professoros.repository;
import com.professoros.database.Database;
import com.professoros.model.*;
import java.time.Instant;
import java.util.*;
public final class AttendanceRepository {
    private final JdbcOperations jdbc;
    public AttendanceRepository(Database database) { jdbc=new JdbcOperations(database,"Attendance already exists for this enrollment and lecture."); }
    public List<Attendance> findByOffering(long offering) {
        return jdbc.query("SELECT * FROM attendance WHERE course_offering_id=? ORDER BY lecture_id,enrollment_id",r->new Attendance(r.getLong("enrollment_id"),r.getLong("lecture_id"),r.getLong("course_offering_id"),AttendanceStatus.valueOf(r.getString("status")),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at"))),offering);
    }
    public void saveSheet(long offering,List<AttendanceCellEdit> edits,Instant now) {
        jdbc.transaction(c -> {
            try (var current=c.prepareStatement("""
                    SELECT a.status FROM enrollments e JOIN lectures l ON l.course_offering_id=e.course_offering_id
                    LEFT JOIN attendance a ON a.enrollment_id=e.id AND a.lecture_id=l.id
                    WHERE e.course_offering_id=? AND e.id=? AND l.id=? AND l.status<>'CANCELLED' AND e.status<>'WITHDRAWN'
                    """);
                 var clear=c.prepareStatement("DELETE FROM attendance WHERE enrollment_id=? AND lecture_id=?");
                 var save=c.prepareStatement("""
                    INSERT INTO attendance(enrollment_id,lecture_id,course_offering_id,status,created_at,updated_at) VALUES(?,?,?,?,?,?)
                    ON CONFLICT(enrollment_id,lecture_id) DO UPDATE SET status=excluded.status,updated_at=MAX(attendance.updated_at,excluded.updated_at)
                    """)) {
                for(var edit:edits) {
                    JdbcOperations.bind(current,new Object[]{offering,edit.enrollment(),edit.lecture()});
                    try(var row=current.executeQuery()) {
                        String expected=edit.expected()==null ? null : edit.expected().name();
                        if (!row.next() || !Objects.equals(row.getString(1),expected))
                            throw new DomainValidationException("Attendance changed or is read-only. Refresh and review your edits.");
                    }
                    if(edit.desired()==null) {
                        JdbcOperations.bind(clear,new Object[]{edit.enrollment(),edit.lecture()});
                        clear.executeUpdate();
                    } else {
                        JdbcOperations.bind(save,new Object[]{edit.enrollment(),edit.lecture(),offering,edit.desired(),now,now});
                        save.executeUpdate();
                    }
                }
            }
            return null;
        });
    }
    /** Only submitted cells change. Null clears a mark; the whole batch commits or rolls back. */
    public void save(long offering,long lecture,Map<Long,AttendanceStatus> changes,Instant now) {
        jdbc.transaction(c->{
            try(var upsert=c.prepareStatement("""
                    INSERT INTO attendance(enrollment_id,lecture_id,course_offering_id,status,created_at,updated_at) VALUES(?,?,?,?,?,?)
                    ON CONFLICT(enrollment_id,lecture_id) DO UPDATE SET status=excluded.status,updated_at=MAX(attendance.updated_at,excluded.updated_at)
                    WHERE attendance.course_offering_id=excluded.course_offering_id
                    """);var clear=c.prepareStatement("DELETE FROM attendance WHERE course_offering_id=? AND lecture_id=? AND enrollment_id=?")) {
                for(var entry:changes.entrySet()) {
                    if(entry.getValue()==null) { JdbcOperations.bind(clear,new Object[]{offering,lecture,entry.getKey()});clear.executeUpdate(); }
                    else { JdbcOperations.bind(upsert,new Object[]{entry.getKey(),lecture,offering,entry.getValue(),now,now});upsert.executeUpdate(); }
                }
            } return null;
        });
    }
}
