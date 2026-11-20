begin;

alter table public.portal_published_grades
    add column revision uuid not null default gen_random_uuid();

-- Keep existing read access and policies. All end-user writes go through the RPC.
revoke insert, update, delete, truncate on public.portal_published_grades from public, anon, authenticated;

create function public.desktop_publish_grade(
    target_offering uuid, target_student uuid, expected_revision uuid,
    new_score numeric, new_letter text
) returns uuid language plpgsql security definer set search_path = '' as $$
declare
    confirmed uuid;
begin
    if public.portal_teaches(target_offering) is not true then
        raise exception 'Verified teaching assignment required' using errcode = '42501';
    end if;
    perform 1 from public.portal_offerings
    where id = target_offering and professor_id = auth.uid() for share;
    if not found then
        raise exception 'Verified teaching assignment required' using errcode = '42501';
    end if;
    perform 1 from public.portal_enrollments
    where offering_id = target_offering and student_id = target_student for key share;
    if not found then
        raise exception 'Student is not enrolled in the portal offering' using errcode = '42501';
    end if;
    if new_score is null or new_score not between 0 and 100 or new_score <> round(new_score,2)
       or new_letter is null or new_letter not in ('A','B','C','D','E','F') then
        raise exception 'Invalid final grade' using errcode = '22023';
    end if;

    if expected_revision is null then
        insert into public.portal_published_grades(offering_id, student_id, score, letter)
        values (target_offering, target_student, new_score, new_letter)
        on conflict (offering_id, student_id) do nothing
        returning revision into confirmed;
    else
        update public.portal_published_grades
        set score = new_score, letter = new_letter, revision = gen_random_uuid()
        where offering_id = target_offering and student_id = target_student
          and revision = expected_revision
        returning revision into confirmed;
    end if;
    if confirmed is null then
        raise exception 'Published grade changed. Refresh and review before publishing.' using errcode = 'PT409';
    end if;
    return confirmed;
end;
$$;

revoke all on function public.desktop_publish_grade(uuid,uuid,uuid,numeric,text) from public, anon, authenticated;
grant execute on function public.desktop_publish_grade(uuid,uuid,uuid,numeric,text) to authenticated;
notify pgrst, 'reload schema';
commit;
