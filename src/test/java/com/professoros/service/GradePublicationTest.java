package com.professoros.service;

import com.professoros.model.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GradePublicationTest {
    private static final UUID OFFERING=UUID.fromString("b5ad5efc-2093-4a2d-80d9-206e5082d4d1");
    private static final UUID STUDENT=UUID.fromString("894659e5-c2ed-429d-a7fe-952fa29a82d8");
    private Gradebook book(boolean scored) {
        var now=Instant.EPOCH;
        var student=new Student(1,"FICTIONAL-1","Fictional","Student","student@example.invalid","",now,now);
        var enrollment=new Enrollment(1,1,1,now,EnrollmentStatus.ACTIVE);
        return new Gradebook(1,List.of(new AssessmentCategory(1,1,"Final",new BigDecimal("100"),now,now)),
                List.of(new Assessment(1,1,1,"Exam",new BigDecimal("100"),LocalDate.of(2026,11,20),now,now)),
                List.of(new EnrolledStudent(enrollment,student)),
                scored?List.of(new StudentGrade(new GradeKey(1,1),new BigDecimal("72"),now,now)):List.of(),GradingScale.standard());
    }
    @Test void savedGradeUsesExistingCalculator() {
        var snapshot=GradePublication.fromSaved(book(true),1,OFFERING,STUDENT);
        assertEquals(new BigDecimal("72.00"),snapshot.score()); assertEquals("C",snapshot.letter());
        assertEquals(snapshot,GradePublication.fromSaved(book(true),1,OFFERING,STUDENT));
    }
    @Test void incompleteOrUnmappedStudentNeverPublishes() {
        assertThrows(DomainValidationException.class,()->GradePublication.fromSaved(book(false),1,OFFERING,STUDENT));
        assertThrows(DomainValidationException.class,()->GradePublication.fromSaved(book(true),2,OFFERING,STUDENT));
    }
    @Test void insecureOrPrivilegedConfigurationRejected() {
        assertThrows(DomainValidationException.class,()->new PortalPublishingClient("http://example.com","sb_publishable_test"));
        assertThrows(DomainValidationException.class,()->new PortalPublishingClient("https://example.com","sb_secret_test"));
    }
}
