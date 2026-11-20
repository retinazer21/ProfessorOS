begin;

-- Keep the local IDs used by the desktop projection distinct from the permanent UUIDs.
create sequence desktop_private.catalog_local_id as bigint;
select setval('desktop_private.catalog_local_id',greatest(
    coalesce((select max(local_id) from academic_import.course),0),
    coalesce((select max(local_id) from academic_import.term),0),
    coalesce((select max(local_id) from academic_import.student),0),coalesce((select max(local_id) from academic_import.lecture),0),coalesce((select max(local_id) from academic_import.offering),0),1
));
revoke all on sequence desktop_private.catalog_local_id from public, anon, authenticated;

create function public.desktop_write_catalog(changes jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    snapshot jsonb;
    edit jsonb;
    entity text;
    previous jsonb;
    desired jsonb;
    local_key bigint;
    record_id uuid;
    stamp text;
    key_text text;
    offering_uuid uuid;
    course_uuid uuid;
    term_uuid uuid;
begin
    if public.portal_access() is distinct from 'professor' then
        raise exception 'Verified professor required' using errcode='42501';
    end if;
    perform 1 from desktop_private.workspace where professor_id=auth.uid() for update;
    if not found then
        raise exception 'Authorized workspace owner required' using errcode='42501';
    end if;
    snapshot := public.desktop_read_workspace();
    if jsonb_typeof(changes) is distinct from 'array' or jsonb_array_length(changes) not between 1 and 5000 then
        raise exception 'Invalid catalog batch' using errcode='22023';
    end if;
    if exists (
        select 1 from jsonb_array_elements(changes) x
        where not (x ?& array['entity','previous','desired']) or coalesce(x->>'entity','') not in ('courses','academic_terms','students','lectures','course_offerings')
           or jsonb_typeof(x->'previous') not in ('null','object') or jsonb_typeof(x->'desired') not in ('null','object')
           or (x->>'previous' is null and x->>'desired' is null)
    ) or exists (
        select 1 from jsonb_array_elements(changes) x where x->>'previous' is not null
        group by x->>'entity', x->'previous'->>'id' having count(*) > 1
    ) then
        raise exception 'Invalid or repeated catalog edit' using errcode='22023';
    end if;

    for edit in select value from jsonb_array_elements(changes) loop
        entity := edit->>'entity';
        previous := nullif(edit->'previous','null'::jsonb);
        desired := nullif(edit->'desired','null'::jsonb);
        local_key := (previous->>'id')::bigint;
        if previous is not null and not exists (
            select 1 from jsonb_array_elements(snapshot->entity) row_value where row_value=previous
        ) then
            raise exception 'Catalog record changed. Refresh before saving.' using errcode='PT409';
        end if;
        stamp := to_char(clock_timestamp() at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
        if previous is null then
            local_key := nextval('desktop_private.catalog_local_id');
            record_id := gen_random_uuid();
        end if;
        case entity
        when 'courses' then
            if previous is not null then
                select id into strict record_id from academic_import.course where local_id=local_key;
            end if;
            if desired is null then
                delete from desktop_private.course_detail where course_id=record_id;
                delete from academic_import.course where id=record_id;
            else
                if not (desired ?& array['course_code','course_name','department','credit_hours','description'])
                   or (desired->>'course_code') !~ '^[A-Z0-9][A-Z0-9 ._-]{0,19}$'
                   or desired->>'course_code' <> btrim(desired->>'course_code')
                   or length(btrim(desired->>'course_name')) not between 1 and 200
                   or length(desired->>'department') > 120 or length(desired->>'description') > 5000
                   or (desired->>'credit_hours')::integer not between 1 and 30 then
                    raise exception 'Invalid course details' using errcode='22023';
                end if;
                insert into academic_import.course(id,local_id,code,title,department,credits)
                values(record_id,local_key,desired->>'course_code',btrim(desired->>'course_name'),desired->>'department',(desired->>'credit_hours')::integer)
                on conflict(id) do update set code=excluded.code,title=excluded.title,department=excluded.department,credits=excluded.credits;
                insert into desktop_private.course_detail(course_id,description,created_at,updated_at)
                values(record_id,desired->>'description',stamp,stamp)
                on conflict(course_id) do update set description=excluded.description,updated_at=excluded.updated_at;
                update public.portal_offerings p set title=btrim(desired->>'course_name')
                from academic_import.offering o where o.id=p.id and o.course_id=record_id;
            end if;
        when 'academic_terms' then
            if previous is not null then
                select id into strict record_id from academic_import.term where local_id=local_key;
            end if;
            if desired is null then
                delete from desktop_private.term_detail where term_id=record_id;
                delete from academic_import.term where id=record_id;
            else
                if not (desired ?& array['name','academic_year','start_date','end_date','status'])
                   or length(btrim(desired->>'name')) not between 1 and 80
                   or length(btrim(desired->>'academic_year')) not between 1 and 40
                   or desired->>'start_date' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
                   or desired->>'end_date' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
                   or (desired->>'start_date')::date >= (desired->>'end_date')::date
                   or desired->>'status' not in ('PLANNED','ACTIVE','COMPLETED','ARCHIVED') then
                    raise exception 'Invalid academic term' using errcode='22023';
                end if;
                if exists(select 1 from academic_import.term
                  where id<>record_id and lower(name)=lower(btrim(desired->>'name'))
                    and lower(academic_year)=lower(btrim(desired->>'academic_year'))) then
                    raise exception 'Term already exists' using errcode='23505';
                end if;
                insert into academic_import.term(id,local_id,name,academic_year,starts,ends)
                values(record_id,local_key,btrim(desired->>'name'),btrim(desired->>'academic_year'),
                       (desired->>'start_date')::date,(desired->>'end_date')::date)
                on conflict(id) do update set name=excluded.name,academic_year=excluded.academic_year,starts=excluded.starts,ends=excluded.ends;
                insert into desktop_private.term_detail(term_id,status,created_at,updated_at)
                values(record_id,desired->>'status',stamp,stamp)
                on conflict(term_id) do update set status=excluded.status,updated_at=excluded.updated_at;
            end if;
        when 'students' then
            if previous is not null then
                select id into strict record_id from academic_import.student where local_id=local_key;
            end if;
            if desired is null then
                delete from desktop_private.student_detail where student_id=record_id;
                delete from academic_import.student where id=record_id;
            else
                key_text := desired->>'student_number';
                if not (desired ?& array['student_number','first_name','last_name','email','notes'])
                   or key_text !~ '^[A-Z0-9][A-Z0-9 ._-]{0,39}$' or key_text<>btrim(key_text)
                   or length(btrim(desired->>'first_name')) not between 1 and 100
                   or length(btrim(desired->>'last_name')) not between 1 and 100
                   or length(desired->>'email') > 254
                   or (desired->>'email' <> '' and desired->>'email' !~ '^[^[:space:]@]+@[^[:space:]@.]+(\.[^[:space:]@.]+)+$')
                   or length(desired->>'notes') > 5000 then
                    raise exception 'Invalid student details' using errcode='22023';
                end if;
                insert into academic_import.student(id,local_id,number,first_name,last_name)
                values(record_id,local_key,key_text,btrim(desired->>'first_name'),btrim(desired->>'last_name'))
                on conflict(id) do update set number=excluded.number,first_name=excluded.first_name,last_name=excluded.last_name;
                insert into desktop_private.student_detail(student_id,email,notes,created_at,updated_at)
                values(record_id,desired->>'email',desired->>'notes',stamp,stamp)
                on conflict(student_id) do update set email=excluded.email,notes=excluded.notes,updated_at=excluded.updated_at;
            end if;
        when 'lectures' then
            if previous is not null then
                select id,offering_id into strict record_id,offering_uuid from academic_import.lecture where local_id=local_key;
            end if;
            if desired is null then
                delete from desktop_private.lecture_detail where lecture_id=record_id;
                delete from academic_import.lecture where id=record_id;
            else
                if not (desired ?& array['course_offering_id','lecture_number','title','planned_date','status','topics',
                    'description','duration_minutes','professor_notes'])
                   or (desired->>'lecture_number')::integer not between 1 and 9999
                   or length(btrim(desired->>'title')) not between 1 and 200
                   or desired->>'planned_date' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
                   or desired->>'status' not in ('COMPLETED','PARTIALLY_COMPLETED','PLANNED','CANCELLED')
                   or length(desired->>'topics') > 5000
                   or length(desired->>'description') > 10000
                   or length(desired->>'professor_notes') > 10000
                   or (desired->>'duration_minutes')::integer not between 1 and 1440 then
                    raise exception 'Invalid lecture details' using errcode='22023';
                end if;
                if previous is null then
                    select id into offering_uuid from academic_import.offering where local_id=(desired->>'course_offering_id')::bigint;
                elsif previous->'course_offering_id' is distinct from desired->'course_offering_id' then
                    raise exception 'A lecture cannot move to another offering' using errcode='22023';
                end if;
                if public.portal_teaches(offering_uuid) is not true then
                    raise exception 'Verified teaching assignment required' using errcode='42501';
                end if;
                insert into academic_import.lecture(id,local_id,offering_id,number,title,day,status)
                values(record_id,local_key,offering_uuid,(desired->>'lecture_number')::integer,btrim(desired->>'title'),
                       (desired->>'planned_date')::date,desired->>'status')
                on conflict(id) do update set number=excluded.number,title=excluded.title,day=excluded.day,status=excluded.status;
                insert into desktop_private.lecture_detail(lecture_id,topics,description,duration_minutes,professor_notes,created_at,updated_at)
                values(record_id,desired->>'topics',desired->>'description',(desired->>'duration_minutes')::integer,
                       desired->>'professor_notes',stamp,stamp)
                on conflict(lecture_id) do update set topics=excluded.topics,description=excluded.description,
                    duration_minutes=excluded.duration_minutes,professor_notes=excluded.professor_notes,updated_at=excluded.updated_at;
            end if;
        when 'course_offerings' then
            if previous is not null then
                select id into strict record_id from academic_import.offering where local_id=local_key;
            end if;
            if desired is null then
                delete from desktop_private.offering_detail where offering_id=record_id;
                delete from academic_import.offering where id=record_id;
                delete from public.portal_offerings where id=record_id;
            else
                if not (desired ?& array['course_id','academic_term_id','section','status','notes'])
                   or (desired->>'section') !~ '^[A-Z0-9][A-Z0-9 ._-]{0,39}$'
                   or desired->>'section' <> btrim(desired->>'section')
                   or length(desired->>'notes') > 5000
                   or desired->>'status' not in ('PLANNED','ACTIVE','COMPLETED','ARCHIVED') then
                    raise exception 'Invalid offering details' using errcode='22023';
                end if;
                select id into course_uuid from academic_import.course where local_id=(desired->>'course_id')::bigint;
                select id into term_uuid from academic_import.term where local_id=(desired->>'academic_term_id')::bigint;
                if course_uuid is null or term_uuid is null then
                    raise exception 'Select an existing course and term' using errcode='22023';
                end if;
                if previous is not null and (
                    previous->'course_id' is distinct from desired->'course_id'
                    or previous->'academic_term_id' is distinct from desired->'academic_term_id'
                ) and (
                    exists(select 1 from academic_import.enrollment where offering_id=record_id)
                    or exists(select 1 from academic_import.lecture where offering_id=record_id)
                    or exists(select 1 from desktop_private.exams where course_offering_id=record_id)
                    or exists(select 1 from desktop_private.assessment_categories where course_offering_id=record_id)
                    or exists(select 1 from desktop_private.grading_scales where course_offering_id=record_id)
                    or exists(select 1 from public.portal_enrollments where offering_id=record_id)
                ) then
                    raise exception 'An offering with academic history cannot move' using errcode='22023';
                end if;
                if exists(select 1 from academic_import.offering where id<>record_id and course_id=course_uuid
                          and term_id=term_uuid and lower(section)=lower(desired->>'section')) then
                    raise exception 'Offering already exists' using errcode='23505';
                end if;
                insert into public.portal_offerings(id,title,professor_id)
                select record_id,c.title,auth.uid() from academic_import.course c where c.id=course_uuid
                on conflict(id) do update set title=excluded.title;
                insert into academic_import.offering(id,local_id,course_id,term_id,section,status)
                values(record_id,local_key,course_uuid,term_uuid,desired->>'section',desired->>'status')
                on conflict(id) do update set course_id=excluded.course_id,term_id=excluded.term_id,section=excluded.section,status=excluded.status;
                insert into desktop_private.offering_detail(offering_id,notes,created_at,updated_at)
                values(record_id,desired->>'notes',stamp,stamp)
                on conflict(offering_id) do update set notes=excluded.notes,updated_at=excluded.updated_at;
            end if;
        end case;
    end loop;
    return public.desktop_read_workspace();
end;
$$;

revoke all on function public.desktop_write_catalog(jsonb) from public, anon, authenticated;
grant execute on function public.desktop_write_catalog(jsonb) to authenticated;
notify pgrst, 'reload schema';
commit;
