package com.professoros.demo;

import com.professoros.database.Database;
import com.professoros.model.*;
import com.professoros.repository.CourseRepository;
import com.professoros.service.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.Map;

/** Explicit fictional fixture. Refuses an existing directory; never modifies user data. */
public final class PublicationDemo {
    private PublicationDemo() { }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Supply a NEW fictional data directory.");
        var directory=Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectory(directory); // Deliberately fails if it already exists.
        var database=new Database(directory.resolve("professor-os.db")); database.initialize();
        var services=AcademicServices.create(database);
        var course=new CourseService(new CourseRepository(database)).create(new CourseInput("E2E-PUBLISH","Fictional publication course","Testing",3,"Fictional acceptance only"));
        var term=services.terms().create(new AcademicTermInput("Fictional term","2026–2027",LocalDate.of(2026,9,1),LocalDate.of(2027,1,1),AcademicStatus.ACTIVE));
        var offering=services.offerings().create(new CourseOfferingInput(course.id(),term.id(),"TEST","Fictional acceptance only",AcademicStatus.ACTIVE));
        var student=services.students().create(new StudentInput("FICTIONAL-A","Fictional","Student A","professor-os-e2e-student-a@example.invalid","No real identity"));
        var enrollment=services.enrollments().enroll(student.id(),offering.id());
        var category=services.grades().saveCategory(offering.id(),null,"Final",new BigDecimal("100"));
        var assessment=services.grades().saveAssessment(offering.id(),null,category.id(),"Fictional final",new BigDecimal("100"),LocalDate.of(2026,11,20));
        services.grades().saveGrades(offering.id(),Map.of(new GradeKey(enrollment.id(),assessment.id()),"72"));
        System.out.println("Fictional data ready: "+directory+"; offering="+offering.id()+"; enrollment="+enrollment.id()+"; final=72 C. Nothing uploaded.");
    }
}
