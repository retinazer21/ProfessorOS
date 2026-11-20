package com.professoros.service;

import com.professoros.model.Gradebook;
import com.professoros.model.DomainValidationException;
import java.math.BigDecimal;
import java.util.UUID;

/** Explicitly mapped single-student snapshot; no local identifiers or roster are uploaded. */
public record GradePublication(UUID offering, UUID student, BigDecimal score, String letter) {
    public GradePublication {
        if (offering == null || student == null || score == null || score.signum() < 0
                || score.compareTo(new BigDecimal("100")) > 0 || score.scale() > 2
                || letter == null || !letter.matches("[A-F]"))
            throw new DomainValidationException("Invalid final-grade publication.");
    }
    public static GradePublication fromSaved(Gradebook book, long enrollment, UUID offering, UUID student) {
        if (book.students().stream().noneMatch(s -> s.enrollment().id() == enrollment
                && s.enrollment().status() == com.professoros.model.EnrollmentStatus.ACTIVE)) {
            throw new DomainValidationException("Choose one active enrolled student.");
        }
        var result = GradeCalculator.calculate(book, enrollment, GradeCalculator.scores(book));
        if (!result.complete()) throw new DomainValidationException("Only complete saved final grades can be published.");
        return new GradePublication(offering, student, result.weightedPoints(), result.letter());
    }
}
