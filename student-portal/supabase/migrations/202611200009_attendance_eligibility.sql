begin;

-- Keep both RPCs on the same eligibility test. The row locks prevent withdrawal
-- or lecture cancellation between this check and the attendance mutation.
create function desktop_private.eligible_attendance_cell(offering uuid, enrollment uuid, lecture uuid)
returns boolean language plpgsql security invoker set search_path = '' as $$
begin
    perform 1 from academic_import.enrollment e
    join academic_import.lecture l on l.offering_id = e.offering_id
    where e.id = enrollment and l.id = lecture and e.offering_id = offering
      and e.status <> 'WITHDRAWN' and l.status in ('COMPLETED','PARTIALLY_COMPLETED')
    for share of e,l;
    return found;
end
$$;
revoke all on function desktop_private.eligible_attendance_cell(uuid,uuid,uuid) from public, anon, authenticated;

-- Preserve the scalar response, status validation and existing-mark-only contract.
create or replace function public.desktop_change_attendance(
    target_enrollment uuid, target_lecture uuid, expected_status text, new_status text
) returns text language plpgsql security definer set search_path = '' as $$
declare
    offering uuid;
    confirmed text;
begin
    if public.portal_access() is distinct from 'professor' then
        raise exception 'Professor verification required' using errcode = '42501';
    end if;
    if new_status is null or new_status not in ('ABSENT','PRESENT','LATE','EXCUSED') then
        raise exception 'Invalid attendance status' using errcode = '22023';
    end if;
    select offering_id into offering from academic_import.enrollment where id = target_enrollment;
    if public.portal_teaches(offering) is not true
       or not desktop_private.eligible_attendance_cell(offering,target_enrollment,target_lecture) then
        raise exception 'Record unavailable or changed. Refresh before retrying.' using errcode = '42501';
    end if;
    update academic_import.attendance set status = new_status
    where enrollment_id = target_enrollment and lecture_id = target_lecture
      and offering_id = offering and status = expected_status
    returning status into confirmed;
    if not found then
        raise exception 'Record unavailable or changed. Refresh before retrying.' using errcode = 'PT409';
    end if;
    return confirmed;
end
$$;

create or replace function public.desktop_save_attendance_sheet(target uuid, edits jsonb) returns integer
language plpgsql security definer set search_path = '' as $$
declare
    cell record;
    affected integer;
begin
    if public.portal_teaches(target) is not true then
        raise exception 'Verified teaching assignment required' using errcode='42501';
    end if;
    if jsonb_typeof(edits) is distinct from 'array' or jsonb_array_length(edits) not between 1 and 10000 then
        raise exception 'Invalid attendance batch' using errcode='22023';
    end if;
    if exists(select 1 from jsonb_array_elements(edits) x where not (x ?& array['enrollment','lecture','expected','desired']))
        or exists(select 1 from jsonb_to_recordset(edits) as x(enrollment uuid,lecture uuid)
                  group by enrollment,lecture having count(*)>1) then
        raise exception 'Incomplete or repeated attendance cell' using errcode='22023';
    end if;
    for cell in select * from jsonb_to_recordset(edits) as x(enrollment uuid,lecture uuid,expected text,desired text)
        order by enrollment,lecture loop
        if cell.desired is not distinct from cell.expected or cell.desired not in ('PRESENT','ABSENT','LATE','EXCUSED') then
            raise exception 'Invalid mark' using errcode='22023';
        end if;
        if not desktop_private.eligible_attendance_cell(target,cell.enrollment,cell.lecture) then
            raise exception 'Cell unavailable' using errcode='42501';
        end if;
        if cell.expected is null then
            insert into academic_import.attendance(enrollment_id,lecture_id,offering_id,status)
            values(cell.enrollment,cell.lecture,target,cell.desired) on conflict do nothing;
        elsif cell.desired is null then
            delete from academic_import.attendance where enrollment_id=cell.enrollment and lecture_id=cell.lecture
                and offering_id=target and status=cell.expected;
        else
            update academic_import.attendance set status=cell.desired where enrollment_id=cell.enrollment
                and lecture_id=cell.lecture and offering_id=target and status=cell.expected;
        end if;
        get diagnostics affected = row_count;
        if affected<>1 then raise exception 'Attendance changed. Refresh and review.' using errcode='PT409'; end if;
    end loop;
    return jsonb_array_length(edits);
end $$;

commit;
