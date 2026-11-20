begin;

-- Return the same domain field names used by the native services. Include only
-- imported workspace rows. Auth identities and published snapshots have separate APIs.
create function public.desktop_read_workspace()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare
    records jsonb;
begin
    if public.portal_access() is distinct from 'professor'
       or not exists (select 1 from desktop_private.workspace where professor_id = auth.uid())
       or exists (select 1 from academic_import.offering where public.portal_teaches(id) is not true) then
        raise exception 'Authorized workspace owner required' using errcode = '42501';
    end if;

    records := (
        with courses as (
            select c.local_id id, c.code course_code, c.title course_name, c.department, c.credits credit_hours,
                d.description, d.created_at, d.updated_at
            from academic_import.course c join desktop_private.course_detail d on d.course_id = c.id
        ), academic_terms as (
            select t.local_id id, t.name, t.academic_year, t.starts start_date, t.ends end_date,
                d.status, d.created_at, d.updated_at
            from academic_import.term t join desktop_private.term_detail d on d.term_id = t.id
        ), course_offerings as (
            select o.local_id id, c.local_id course_id, t.local_id academic_term_id, o.section,
                d.notes, o.status, d.created_at, d.updated_at
            from academic_import.offering o join desktop_private.offering_detail d on d.offering_id = o.id
            join academic_import.course c on c.id = o.course_id join academic_import.term t on t.id = o.term_id
        ), students as (
            select s.local_id id, s.number student_number, s.first_name, s.last_name,
                d.email, d.notes, d.created_at, d.updated_at
            from academic_import.student s join desktop_private.student_detail d on d.student_id = s.id
        ), enrollments as (
            select e.local_id id, s.local_id student_id, o.local_id course_offering_id, d.enrolled_at, e.status
            from academic_import.enrollment e join desktop_private.enrollment_detail d on d.enrollment_id = e.id
            join academic_import.student s on s.id = e.student_id join academic_import.offering o on o.id = e.offering_id
        ), lectures as (
            select l.local_id id, o.local_id course_offering_id, l.number lecture_number, l.title, l.day planned_date,
                d.topics, d.description, d.duration_minutes, l.status, d.professor_notes, d.created_at, d.updated_at
            from academic_import.lecture l join desktop_private.lecture_detail d on d.lecture_id = l.id
            join academic_import.offering o on o.id = l.offering_id
        ), attendance as (
            select e.local_id enrollment_id, l.local_id lecture_id, o.local_id course_offering_id,
                a.status, d.created_at, d.updated_at
            from academic_import.attendance a join desktop_private.attendance_detail d using(enrollment_id,lecture_id)
            join academic_import.enrollment e on e.id = a.enrollment_id join academic_import.lecture l on l.id = a.lecture_id
            join academic_import.offering o on o.id = a.offering_id
        )
        select jsonb_build_object(
            'courses', (select jsonb_agg(c order by id) from courses c),
            'academic_terms', (select jsonb_agg(t order by id) from academic_terms t),
            'course_offerings', (select jsonb_agg(o order by id) from course_offerings o),
            'students', (select jsonb_agg(s order by id) from students s),
            'enrollments', (select jsonb_agg(e order by id) from enrollments e),
            'lectures', (select jsonb_agg(l order by id) from lectures l),
            'attendance', (select jsonb_agg(a order by enrollment_id,lecture_id) from attendance a),
            'questions', (select jsonb_agg(to_jsonb(q) || jsonb_build_object('course_id',c.local_id,'image_path','') order by q.id)
                from desktop_private.questions q join academic_import.course c on c.id = q.course_id),
            'question_choices', (select jsonb_agg(q order by question_id,position) from desktop_private.question_choices q),
            'exams', (select jsonb_agg(to_jsonb(e) || jsonb_build_object('course_offering_id',o.local_id) order by e.id)
                from desktop_private.exams e join academic_import.offering o on o.id = e.course_offering_id),
            'exam_questions', (select jsonb_agg(to_jsonb(q) || jsonb_build_object('image_path','') order by exam_id,position)
                from desktop_private.exam_questions q),
            'exam_question_choices', (select jsonb_agg(c order by exam_id,question_id,position) from desktop_private.exam_question_choices c),
            'assessment_categories', (select jsonb_agg(to_jsonb(c) || jsonb_build_object('course_offering_id',o.local_id) order by c.id)
                from desktop_private.assessment_categories c join academic_import.offering o on o.id = c.course_offering_id),
            'assessments', (select jsonb_agg(to_jsonb(a) || jsonb_build_object('course_offering_id',o.local_id) order by a.id)
                from desktop_private.assessments a join academic_import.offering o on o.id = a.course_offering_id),
            'student_grades', (select jsonb_agg(to_jsonb(g) || jsonb_build_object('enrollment_id',e.local_id,'course_offering_id',o.local_id)
                order by e.local_id,g.assessment_id) from desktop_private.student_grades g
                join academic_import.offering o on o.id = g.course_offering_id join academic_import.enrollment e on e.id = g.enrollment_id),
            'grading_scales', (select jsonb_agg(to_jsonb(s) || jsonb_build_object('course_offering_id',o.local_id) order by o.local_id)
                from desktop_private.grading_scales s join academic_import.offering o on o.id = s.course_offering_id)
        )
    );
    return (select jsonb_object_agg(key, case when value = 'null'::jsonb then '[]'::jsonb else value end)
            from jsonb_each(records));
end
$$;
revoke all on function public.desktop_read_workspace() from public, anon, authenticated;
grant execute on function public.desktop_read_workspace() to authenticated;
notify pgrst, 'reload schema';
commit;
