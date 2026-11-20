begin;
create schema desktop_private;
revoke all on schema desktop_private from public, anon, authenticated;

-- One administrator-assigned owner for this imported installation. No default assignment.
create table desktop_private.workspace (
    singleton boolean primary key default true check(singleton),
    professor_id uuid not null unique references public.portal_roles(user_id)
);
create table desktop_private.course_detail (
    course_id uuid primary key references academic_import.course, description text not null,
    created_at text not null, updated_at text not null
);
create table desktop_private.term_detail (
    term_id uuid primary key references academic_import.term, status text not null,
    created_at text not null, updated_at text not null
);
create table desktop_private.offering_detail (
    offering_id uuid primary key references academic_import.offering, notes text not null,
    created_at text not null, updated_at text not null
);
create table desktop_private.student_detail (
    student_id uuid primary key references academic_import.student, email text not null, notes text not null,
    created_at text not null, updated_at text not null
);
create table desktop_private.enrollment_detail (
    enrollment_id uuid primary key references academic_import.enrollment, enrolled_at text not null
);
create table desktop_private.lecture_detail (
    lecture_id uuid primary key references academic_import.lecture, topics text not null, description text not null,
    duration_minutes integer not null check(duration_minutes between 1 and 1440), professor_notes text not null,
    created_at text not null, updated_at text not null
);
create table desktop_private.attendance_detail (
    enrollment_id uuid not null, lecture_id uuid not null, created_at text not null, updated_at text not null,
    primary key(enrollment_id, lecture_id),
    foreign key(enrollment_id, lecture_id) references academic_import.attendance(enrollment_id, lecture_id)
);
create table desktop_private.questions (
    id bigint primary key, course_id uuid not null references academic_import.course,
    question_type text not null check(question_type in ('MCQ','TRUE_FALSE','SHORT_ANSWER','ESSAY','CODING')),
    question_text text not null, topic text not null, difficulty text not null check(difficulty in ('EASY','MEDIUM','HARD')),
    marks_hundredths integer not null check(marks_hundredths between 1 and 100000),
    answer text not null, explanation text not null, correct_choice_position integer,
    usage_count integer not null check(usage_count>=0), last_used_date date,
    created_at text not null, updated_at text not null,
    check((question_type='MCQ')=(correct_choice_position is not null))
);
create table desktop_private.question_choices (
    question_id bigint not null references desktop_private.questions on delete cascade,
    position integer not null check(position between 1 and 10), choice_text text not null,
    primary key(question_id,position)
);
alter table desktop_private.questions add foreign key(id,correct_choice_position)
    references desktop_private.question_choices(question_id,position) deferrable initially deferred;
create table desktop_private.exams (
    id bigint primary key, course_offering_id uuid not null references academic_import.offering,
    title text not null, exam_date date not null, duration_minutes integer not null check(duration_minutes between 1 and 1440),
    intended_marks_hundredths integer not null check(intended_marks_hundredths between 1 and 10000000),
    instructions text not null, status text not null check(status in ('DRAFT','FINALIZED','ARCHIVED')),
    created_at text not null, updated_at text not null
);
create table desktop_private.exam_questions (
    exam_id bigint not null references desktop_private.exams on delete cascade,
    question_id bigint not null references desktop_private.questions, position integer not null check(position between 1 and 100),
    question_type text not null, question_text text not null, topic text not null, difficulty text not null,
    marks_hundredths integer not null check(marks_hundredths between 1 and 100000),
    answer text not null, explanation text not null,
    primary key(exam_id,question_id), unique(exam_id,position)
);
create table desktop_private.exam_question_choices (
    exam_id bigint not null, question_id bigint not null, position integer not null check(position between 1 and 10),
    choice_text text not null, is_correct integer not null check(is_correct in (0,1)),
    primary key(exam_id,question_id,position), foreign key(exam_id,question_id) references desktop_private.exam_questions on delete cascade
);
create unique index desktop_one_correct on desktop_private.exam_question_choices(exam_id,question_id) where is_correct=1;
create table desktop_private.assessment_categories (
    id bigint primary key, course_offering_id uuid not null references academic_import.offering,
    name text not null, weight_units integer not null check(weight_units between 0 and 10000),
    created_at text not null, updated_at text not null, unique(id,course_offering_id)
);
create unique index desktop_category_name on desktop_private.assessment_categories(course_offering_id,lower(name));
create table desktop_private.assessments (
    id bigint primary key, course_offering_id uuid not null references academic_import.offering,
    category_id bigint not null, title text not null, maximum_units integer not null check(maximum_units between 1 and 10000000),
    assessment_date date not null, created_at text not null, updated_at text not null, unique(id,course_offering_id),
    foreign key(category_id,course_offering_id) references desktop_private.assessment_categories(id,course_offering_id)
);
create table desktop_private.student_grades (
    enrollment_id uuid not null, assessment_id bigint not null, course_offering_id uuid not null,
    score_units integer not null check(score_units>=0), created_at text not null, updated_at text not null,
    primary key(enrollment_id,assessment_id),
    foreign key(enrollment_id,course_offering_id) references academic_import.enrollment(id,offering_id),
    foreign key(assessment_id,course_offering_id) references desktop_private.assessments(id,course_offering_id)
);
create table desktop_private.grading_scales (
    course_offering_id uuid primary key references academic_import.offering,
    a_units integer not null, b_units integer not null, c_units integer not null, d_units integer not null,
    pass_units integer not null check(pass_units between 0 and 10000),
    check(10000>=a_units and a_units>b_units and b_units>c_units and c_units>d_units and d_units>=0)
);
-- No browser grants. A future per-domain writer must enforce ownership, state transitions and revisions.
do $rls$ declare t name; begin
    for t in select tablename from pg_catalog.pg_tables where schemaname='desktop_private' loop
        execute format('alter table desktop_private.%I enable row level security',t);
    end loop;
end $rls$;
revoke all on all tables in schema desktop_private from public, anon, authenticated;
commit;
