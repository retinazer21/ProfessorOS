begin;

create function public.desktop_attendance_sheet(target uuid) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
    if public.portal_teaches(target) is not true then
        raise exception 'Verified teaching assignment required' using errcode='42501';
    end if;
    return jsonb_build_object(
        'students', coalesce((select jsonb_agg(jsonb_build_object('id',e.id,'name',s.first_name || ' ' || s.last_name,
            'number',s.number,'active',e.status <> 'WITHDRAWN') order by s.number,e.id)
            from academic_import.enrollment e join academic_import.student s on s.id=e.student_id
            where e.offering_id=target), '[]'::jsonb),
        'lectures', coalesce((select jsonb_agg(jsonb_build_object('id',l.id,'title',l.title,'day',l.day,
            'editable',l.status in ('COMPLETED','PARTIALLY_COMPLETED')) order by l.day,l.number,l.id)
            from academic_import.lecture l where l.offering_id=target), '[]'::jsonb),
        'marks', coalesce((select jsonb_agg(jsonb_build_object('enrollment',a.enrollment_id,'lecture',a.lecture_id,'status',a.status))
            from academic_import.attendance a where a.offering_id=target), '[]'::jsonb)
    );
end $$;

-- All edits to one offering commit together. Missing rows represent unmarked.
create function public.desktop_save_attendance_sheet(target uuid, edits jsonb) returns integer
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
        perform 1 from academic_import.enrollment e join academic_import.lecture l on l.offering_id=e.offering_id
        where e.id=cell.enrollment and l.id=cell.lecture and e.offering_id=target and e.status<>'WITHDRAWN'
          and l.status in ('COMPLETED','PARTIALLY_COMPLETED') for share of e,l;
        if not found then raise exception 'Cell unavailable' using errcode='42501'; end if;
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
        if affected<>1 then raise exception 'Attendance changed. Refresh and review.' using errcode='40001'; end if;
    end loop;
    return jsonb_array_length(edits);
end $$;
-- An explicitly cleared mark has no remaining timestamp detail.
do $$ declare fk name; begin
    if to_regclass('desktop_private.attendance_detail') is not null then
        select conname into strict fk from pg_catalog.pg_constraint where conrelid='desktop_private.attendance_detail'::regclass
            and confrelid='academic_import.attendance'::regclass and contype='f';
        execute format('alter table desktop_private.attendance_detail drop constraint %I',fk);
        alter table desktop_private.attendance_detail add foreign key(enrollment_id,lecture_id)
            references academic_import.attendance(enrollment_id,lecture_id) on delete cascade;
        create function desktop_private.touch_attendance_detail() returns trigger
        language plpgsql set search_path='' as $touch$
        declare stamp text := to_char(clock_timestamp() at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
        begin
            insert into desktop_private.attendance_detail(enrollment_id,lecture_id,created_at,updated_at)
                values(new.enrollment_id,new.lecture_id,stamp,stamp)
                on conflict(enrollment_id,lecture_id) do update set updated_at=excluded.updated_at;
            return new;
        end $touch$;
        revoke all on function desktop_private.touch_attendance_detail() from public,anon,authenticated;
        create trigger desktop_attendance_timestamps after insert or update of status on academic_import.attendance
            for each row execute function desktop_private.touch_attendance_detail();
    end if;
end $$;
revoke all on function public.desktop_attendance_sheet(uuid), public.desktop_save_attendance_sheet(uuid,jsonb) from public,anon,authenticated;
grant execute on function public.desktop_attendance_sheet(uuid), public.desktop_save_attendance_sheet(uuid,jsonb) to authenticated;
notify pgrst,'reload schema';
commit;
