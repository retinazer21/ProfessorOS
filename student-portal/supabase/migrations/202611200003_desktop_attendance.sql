begin;

-- A single existing register entry. Both composite foreign keys preserve offering context.
create function public.desktop_change_attendance(
    target_enrollment uuid, target_lecture uuid, expected_status text, new_status text
) returns text language plpgsql security definer set search_path = '' as $$
declare
    confirmed text;
begin
    if public.portal_access() is distinct from 'professor' then
        raise exception 'Professor verification required' using errcode = '42501';
    end if;
    if new_status is null or new_status not in ('ABSENT','PRESENT','LATE','EXCUSED') then
        raise exception 'Invalid attendance status' using errcode = '22023';
    end if;
    update academic_import.attendance a set status = new_status
    from academic_import.lecture l
    where a.enrollment_id = target_enrollment and a.lecture_id = target_lecture
      and l.id = a.lecture_id and l.offering_id = a.offering_id
      and l.status in ('COMPLETED','PARTIALLY_COMPLETED')
      and public.portal_teaches(a.offering_id) and a.status = expected_status
    returning a.status into confirmed;
    if not found then
        raise exception 'Record unavailable or changed. Refresh before retrying.' using errcode = '40001';
    end if;
    return confirmed;
end
$$;
revoke all on function public.desktop_change_attendance(uuid,uuid,text,text) from public, anon, authenticated;
grant execute on function public.desktop_change_attendance(uuid,uuid,text,text) to authenticated;
notify pgrst, 'reload schema';
commit;
