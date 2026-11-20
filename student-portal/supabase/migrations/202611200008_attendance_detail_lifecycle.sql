begin;

-- On the hosted project the attendance writer predates the private detail table.
-- Fresh databases can already have the cascade and trigger from 006.
do $migration$
declare
    fk pg_catalog.pg_constraint;
begin
    select * into strict fk from pg_catalog.pg_constraint
    where contype = 'f' and conrelid = 'desktop_private.attendance_detail'::regclass
      and confrelid = 'academic_import.attendance'::regclass;
    if not fk.convalidated or pg_catalog.pg_get_constraintdef(fk.oid) not in (
        'FOREIGN KEY (enrollment_id, lecture_id) REFERENCES academic_import.attendance(enrollment_id, lecture_id)',
        'FOREIGN KEY (enrollment_id, lecture_id) REFERENCES academic_import.attendance(enrollment_id, lecture_id) ON DELETE CASCADE'
    ) then
        raise exception 'Unexpected attendance detail foreign key';
    end if;
    if fk.confdeltype = 'a' then
        execute format('alter table desktop_private.attendance_detail drop constraint %I', fk.conname);
        alter table desktop_private.attendance_detail add foreign key (enrollment_id, lecture_id)
            references academic_import.attendance(enrollment_id, lecture_id) on delete cascade;
    end if;
end
$migration$;

create or replace function desktop_private.touch_attendance_detail() returns trigger
language plpgsql security invoker set search_path = '' as $$
declare
    stamp text := to_char(clock_timestamp() at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
begin
    insert into desktop_private.attendance_detail(enrollment_id, lecture_id, created_at, updated_at)
    values (new.enrollment_id, new.lecture_id, stamp, stamp)
    on conflict (enrollment_id, lecture_id) do update set updated_at = excluded.updated_at;
    return new;
end
$$;
revoke all on function desktop_private.touch_attendance_detail() from public, anon, authenticated;
create or replace trigger desktop_attendance_timestamps after insert or update of status
on academic_import.attendance for each row execute function desktop_private.touch_attendance_detail();

commit;
