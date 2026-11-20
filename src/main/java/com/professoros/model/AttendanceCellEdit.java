package com.professoros.model;

public record AttendanceCellEdit(long enrollment, long lecture, AttendanceStatus expected, AttendanceStatus desired) {}
