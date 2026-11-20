begin;

-- Preserve portal membership rows used by published grade history.
-- Imported offerings additionally require a current link to the academic enrollment.
create or replace function public.portal_enrolled(offering uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select public.portal_access() = 'student' and exists (
        select 1 from public.portal_enrollments p where p.offering_id = offering and p.student_id = auth.uid()
    ) and (
        not exists (select 1 from academic_import.offering o where o.id = offering)
        or exists (
            select 1 from academic_import.enrollment e
            join academic_import.account_link link on link.student_id=e.student_id
            where e.offering_id=offering and link.user_id=auth.uid() and e.status in ('ACTIVE','COMPLETED')
        )
    )
$$;

do $sequence$
declare
    next_id bigint;
begin
    select greatest(
        (select last_value from desktop_private.catalog_local_id),
        coalesce((select max(local_id) from academic_import.enrollment),0)
    )+1 into next_id;
    execute format('alter sequence desktop_private.catalog_local_id restart with %s',next_id);
end;
$sequence$;

create function public.desktop_write_roster(catalog_changes jsonb, enrollment_changes jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    snapshot jsonb;
    edit jsonb;
    previous jsonb;
    desired jsonb;
    enrollment_uuid uuid;
    student_uuid uuid;
    offering_uuid uuid;
    status_name text;
    stamp text;
begin
    if public.portal_access() is distinct from 'professor' then
        raise exception 'Verified professor required' using errcode='42501';
    end if;
    perform 1 from desktop_private.workspace where professor_id=auth.uid() for update;
    if not found then raise exception 'Authorized workspace owner required' using errcode='42501'; end if;
    snapshot := public.desktop_read_workspace();
    if jsonb_typeof(catalog_changes) is distinct from 'array'
       or jsonb_typeof(enrollment_changes) is distinct from 'array'
       or jsonb_array_length(enrollment_changes) not between 1 and 5000 then
        raise exception 'Invalid roster batch' using errcode='22023';
    end if;
    if exists (
        select 1 from jsonb_array_elements(catalog_changes) x
        where x->>'entity' is distinct from 'students' or jsonb_typeof(x->'previous') is distinct from 'null'
    ) or exists (
        select 1 from jsonb_array_elements(enrollment_changes) x
        where not (x ?& array['previous','desired','student_number'])
           or jsonb_typeof(x->'previous') not in ('null','object')
           or jsonb_typeof(x->'desired') is distinct from 'object'
           or not (x->'desired' ?& array['course_offering_id','status'])
    ) or exists (
        select 1 from jsonb_array_elements(enrollment_changes) x where x->>'previous' is not null
        group by x->'previous'->>'id' having count(*)>1
    ) then
        raise exception 'Invalid or repeated roster edit' using errcode='22023';
    end if;

    if jsonb_array_length(catalog_changes)>0 then
        perform public.desktop_write_catalog(catalog_changes);
    end if;
    for edit in select value from jsonb_array_elements(enrollment_changes) loop
        previous := nullif(edit->'previous','null'::jsonb);
        desired := edit->'desired';
        status_name := desired->>'status';
        if status_name is null or status_name not in ('ACTIVE','COMPLETED','WITHDRAWN')
           or (previous is null and status_name<>'ACTIVE') then
            raise exception 'Invalid enrollment status' using errcode='22023';
        end if;
        select id into offering_uuid from academic_import.offering where local_id=(desired->>'course_offering_id')::bigint;
        if public.portal_teaches(offering_uuid) is not true then
            raise exception 'Verified teaching assignment required' using errcode='42501';
        end if;
        if previous is not null and not exists (
            select 1 from jsonb_array_elements(snapshot->'enrollments') row_value where row_value=previous
        ) then
            raise exception 'Enrollment changed. Refresh before saving.' using errcode='PT409';
        end if;
        select id into student_uuid from academic_import.student where number=edit->>'student_number';
        if student_uuid is null then raise exception 'Select an existing student' using errcode='22023'; end if;
        if status_name='ACTIVE' and previous->>'status' is distinct from 'ACTIVE' and not exists (
            select 1 from academic_import.offering o join desktop_private.term_detail t on t.term_id=o.term_id
            where o.id=offering_uuid and o.status in ('PLANNED','ACTIVE') and t.status in ('PLANNED','ACTIVE')
        ) then
            raise exception 'New or reactivated enrollment requires an open offering and term' using errcode='22023';
        end if;
        if previous is null then
            enrollment_uuid := gen_random_uuid();
            stamp := to_char(clock_timestamp() at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
            insert into academic_import.enrollment(id,local_id,offering_id,student_id,status)
            values(enrollment_uuid,nextval('desktop_private.catalog_local_id'),offering_uuid,student_uuid,status_name);
            insert into desktop_private.enrollment_detail values(enrollment_uuid,stamp);
        else
            update academic_import.enrollment set status=status_name
            where local_id=(previous->>'id')::bigint and offering_id=offering_uuid and student_id=student_uuid
              and status=previous->>'status';
            if not found then raise exception 'Enrollment changed. Refresh before saving.' using errcode='PT409'; end if;
        end if;
        if status_name in ('ACTIVE','COMPLETED') then
            insert into public.portal_enrollments(offering_id,student_id)
            select offering_uuid,link.user_id from academic_import.account_link link
            join public.portal_roles role on role.user_id=link.user_id and role.role='student'
            where link.student_id=student_uuid
            on conflict do nothing;
        end if;
    end loop;
    return public.desktop_read_workspace();
end;
$$;
revoke all on function public.desktop_write_roster(jsonb,jsonb) from public, anon, authenticated;
grant execute on function public.desktop_write_roster(jsonb,jsonb) to authenticated;
notify pgrst, 'reload schema';
commit;
