begin;

create sequence desktop_private.grading_local_id as bigint;
select setval('desktop_private.grading_local_id',greatest(
    coalesce((select max(id) from desktop_private.assessment_categories),0),
    coalesce((select max(id) from desktop_private.assessments),0),1
));
revoke all on sequence desktop_private.grading_local_id from public, anon, authenticated;

create function public.desktop_write_gradebook(changes jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    snapshot jsonb;
    edit jsonb;
    entity text;
    previous jsonb;
    desired jsonb;
    value jsonb;
    existing jsonb;
    offering uuid;
    enrollment uuid;
    record_id bigint;
    assessment_key bigint;
    score integer;
    stamp text;
begin
    if public.portal_access() is distinct from 'professor' then
        raise exception 'Verified professor required' using errcode='42501';
    end if;
    perform 1 from desktop_private.workspace where professor_id=auth.uid() for update;
    if not found then raise exception 'Authorized workspace owner required' using errcode='42501'; end if;
    if jsonb_typeof(changes) is distinct from 'array' or jsonb_array_length(changes) not between 1 and 10000 then
        raise exception 'Invalid gradebook batch' using errcode='22023';
    end if;
    snapshot := public.desktop_read_workspace();
    -- Clear dependent grades before removing assessments; other edits follow dependency order.
    for edit in select item from jsonb_array_elements(changes) with ordinality x(item,position)
        order by case when item->>'entity'='student_grades' and item->>'desired' is null then 0 else 1 end,position
    loop
        entity := edit->>'entity';
        previous := nullif(edit->'previous','null'::jsonb);
        desired := nullif(edit->'desired','null'::jsonb);
        value := coalesce(previous,desired);
        if entity is null or entity not in ('assessment_categories','assessments','student_grades','grading_scales')
           or not (edit ?& array['previous','desired']) or jsonb_typeof(value) is distinct from 'object'
           or (desired is not null and jsonb_typeof(desired) is distinct from 'object')
           or (previous is not null and (previous->'course_offering_id' is distinct from coalesce(desired,previous)->'course_offering_id')) then
            raise exception 'Invalid gradebook edit' using errcode='22023';
        end if;
        select id into offering from academic_import.offering where local_id=(value->>'course_offering_id')::bigint;
        if public.portal_teaches(offering) is not true then
            raise exception 'Verified teaching assignment required' using errcode='42501';
        end if;
        if previous is not null and not exists (
            select 1 from jsonb_array_elements(snapshot->entity) row_value where row_value=previous
        ) then
            raise exception 'Gradebook changed. Refresh and review.' using errcode='PT409';
        end if;

        record_id := (previous->>'id')::bigint;
        stamp := to_char(clock_timestamp() at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
        case entity
        when 'assessment_categories' then
            if desired is null then
                delete from desktop_private.assessment_categories where id=record_id and course_offering_id=offering;
                if not found then raise exception 'Category changed' using errcode='PT409'; end if;
            else
                if length(btrim(desired->>'name')) not between 1 and 100 then
                    raise exception 'Invalid category name' using errcode='22023';
                end if;
                if previous is null then
                    insert into desktop_private.assessment_categories
                    values(nextval('desktop_private.grading_local_id'),offering,btrim(desired->>'name'),
                           (desired->>'weight_units')::integer,stamp,stamp);
                else
                    update desktop_private.assessment_categories set name=btrim(desired->>'name'),
                      weight_units=(desired->>'weight_units')::integer,updated_at=stamp
                    where id=record_id and course_offering_id=offering and updated_at=previous->>'updated_at';
                    if not found then raise exception 'Category changed' using errcode='PT409'; end if;
                end if;
            end if;
        when 'assessments' then
            if desired is null then
                delete from desktop_private.assessments where id=record_id and course_offering_id=offering;
                if not found then raise exception 'Assessment changed' using errcode='PT409'; end if;
            else
                if length(btrim(desired->>'title')) not between 1 and 200
                   or desired->>'assessment_date' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
                   or exists(select 1 from desktop_private.student_grades where assessment_id=record_id
                             and score_units>(desired->>'maximum_units')::integer) then
                    raise exception 'Invalid assessment details or maximum below an existing grade' using errcode='22023';
                end if;
                if previous is null then
                    insert into desktop_private.assessments
                    values(nextval('desktop_private.grading_local_id'),offering,(desired->>'category_id')::bigint,
                           btrim(desired->>'title'),(desired->>'maximum_units')::integer,(desired->>'assessment_date')::date,stamp,stamp);
                else
                    update desktop_private.assessments set category_id=(desired->>'category_id')::bigint,
                      title=btrim(desired->>'title'),maximum_units=(desired->>'maximum_units')::integer,
                      assessment_date=(desired->>'assessment_date')::date,updated_at=stamp
                    where id=record_id and course_offering_id=offering and updated_at=previous->>'updated_at';
                    if not found then raise exception 'Assessment changed' using errcode='PT409'; end if;
                end if;
            end if;
        when 'student_grades' then
            assessment_key := (value->>'assessment_id')::bigint;
            select id into enrollment from academic_import.enrollment
            where local_id=(value->>'enrollment_id')::bigint and offering_id=offering;
            if enrollment is null or (previous is not null and desired is not null and
                (previous->'enrollment_id',previous->'assessment_id') is distinct from (desired->'enrollment_id',desired->'assessment_id')) then
                raise exception 'Grade must stay in its existing assessment and enrollment' using errcode='22023';
            end if;
            if desired is not null then
                score := (desired->>'score_units')::integer;
                if not exists(select 1 from desktop_private.assessments a
                              where a.id=assessment_key and a.course_offering_id=offering and score between 0 and a.maximum_units) then
                    raise exception 'Grade exceeds assessment maximum' using errcode='22023';
                end if;
            end if;
            if previous is null then
                insert into desktop_private.student_grades
                values(enrollment,assessment_key,offering,score,stamp,stamp) on conflict do nothing;
            elsif desired is null then
                delete from desktop_private.student_grades g where g.enrollment_id=enrollment and g.assessment_id=assessment_key
                    and g.course_offering_id=offering and g.updated_at=previous->>'updated_at';
            else
                update desktop_private.student_grades g set score_units=score,updated_at=stamp
                where g.enrollment_id=enrollment and g.assessment_id=assessment_key
                    and g.course_offering_id=offering and g.updated_at=previous->>'updated_at';
            end if;
            if not found then raise exception 'Draft grade changed' using errcode='PT409'; end if;
        when 'grading_scales' then
            select jsonb_set(to_jsonb(s),'{course_offering_id}',value->'course_offering_id') into existing
            from desktop_private.grading_scales s where s.course_offering_id=offering;
            if desired is null or previous is distinct from existing then
                raise exception 'Grading scale changed' using errcode='PT409';
            end if;
            insert into desktop_private.grading_scales
            values(offering,(desired->>'a_units')::integer,(desired->>'b_units')::integer,(desired->>'c_units')::integer,
                   (desired->>'d_units')::integer,(desired->>'pass_units')::integer)
            on conflict(course_offering_id) do update set a_units=excluded.a_units,b_units=excluded.b_units,c_units=excluded.c_units,
                d_units=excluded.d_units,pass_units=excluded.pass_units;
        end case;
    end loop;
    return public.desktop_read_workspace();
end;
$$;
revoke all on function public.desktop_write_gradebook(jsonb) from public, anon, authenticated;
grant execute on function public.desktop_write_gradebook(jsonb) to authenticated;
create function public.desktop_grade_publication_target(offering_local_id bigint, enrollment_local_id bigint) returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object('offering',o.id,'student',link.user_id)
    from academic_import.offering o join academic_import.enrollment e on e.offering_id=o.id
    join academic_import.account_link link on link.student_id=e.student_id
    join public.portal_enrollments p on p.offering_id=o.id and p.student_id=link.user_id
    join public.portal_roles r on r.user_id=link.user_id and r.role='student'
    where o.local_id=offering_local_id and e.local_id=enrollment_local_id and e.status='ACTIVE'
      and public.portal_teaches(o.id)
$$;
revoke all on function public.desktop_grade_publication_target(bigint,bigint) from public, anon, authenticated;
grant execute on function public.desktop_grade_publication_target(bigint,bigint) to authenticated;
notify pgrst, 'reload schema';
commit;
