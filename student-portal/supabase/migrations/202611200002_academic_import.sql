begin;

-- Desktop roster identities are independent of managed Auth accounts.
create schema academic_import;
revoke all on schema academic_import from public, anon, authenticated;
create table academic_import.course (
    id uuid primary key, local_id bigint unique not null, code text unique not null,
    title text not null, department text not null, credits integer not null
);
create table academic_import.term (
    id uuid primary key, local_id bigint unique not null, name text not null,
    academic_year text not null, starts date not null, ends date not null
);
create table academic_import.student (
    id uuid primary key, local_id bigint unique not null, number text unique not null,
    first_name text not null, last_name text not null
);
create table academic_import.offering (
    id uuid primary key references public.portal_offerings(id), local_id bigint unique not null,
    course_id uuid not null references academic_import.course,
    term_id uuid not null references academic_import.term, section text not null, status text not null
);
create table academic_import.enrollment (
    id uuid primary key, local_id bigint unique not null,
    offering_id uuid not null references academic_import.offering,
    student_id uuid not null references academic_import.student, status text not null,
    unique(offering_id, student_id), unique(id, offering_id)
);
create table academic_import.lecture (
    id uuid primary key, local_id bigint unique not null, offering_id uuid not null references academic_import.offering,
    number integer not null, title text not null, day date not null, status text not null,
    unique(id, offering_id), unique(offering_id, number)
);
create table academic_import.attendance (
    enrollment_id uuid not null, lecture_id uuid not null, offering_id uuid not null,
    status text not null check (status in ('PRESENT','ABSENT','LATE','EXCUSED')),
    primary key(enrollment_id,lecture_id),
    foreign key(enrollment_id, offering_id) references academic_import.enrollment(id, offering_id),
    foreign key(lecture_id, offering_id) references academic_import.lecture(id, offering_id)
);
create table academic_import.account_link (
    student_id uuid primary key references academic_import.student,
    user_id uuid not null unique references public.portal_roles(user_id)
);
alter table academic_import.course enable row level security;
alter table academic_import.term enable row level security;
alter table academic_import.student enable row level security;
alter table academic_import.offering enable row level security;
alter table academic_import.enrollment enable row level security;
alter table academic_import.lecture enable row level security;
alter table academic_import.attendance enable row level security;
alter table academic_import.account_link enable row level security;
revoke all on all tables in schema academic_import from public, anon, authenticated;

-- These narrowly scoped read functions also include legacy portal offerings.
create function public.portal_course_catalog()
returns table (id uuid, title text, code text, credits integer, department text, term text, section text)
language sql stable security definer set search_path = '' as $$
    select p.id,p.title,c.code,c.credits,c.department,t.name || ' ' || t.academic_year,o.section
    from public.portal_offerings p left join academic_import.offering o on o.id=p.id
    left join academic_import.course c on c.id=o.course_id left join academic_import.term t on t.id=o.term_id
    where public.portal_teaches(p.id) or public.portal_enrolled(p.id)
$$;
create function public.portal_course_lectures(target uuid)
returns table (id uuid, number integer, title text, day date, status text)
language sql stable security definer set search_path = '' as $$
    select l.id,l.number,l.title,l.day,l.status from academic_import.lecture l
    where l.offering_id=target and l.status in ('COMPLETED','PARTIALLY_COMPLETED')
      and (public.portal_teaches(target) or public.portal_enrolled(target))
$$;
create function public.portal_attendance_register()
returns table (enrollment_id uuid, lecture_id uuid, course text, lecture text, day date, student text, status text)
language sql stable security definer set search_path = '' as $$
    select a.enrollment_id,a.lecture_id,p.title,l.title,l.day,s.first_name || ' ' || s.last_name,a.status
    from academic_import.attendance a join academic_import.lecture l on l.id=a.lecture_id
    join public.portal_offerings p on p.id=a.offering_id
    join academic_import.enrollment e on e.id=a.enrollment_id join academic_import.student s on s.id=e.student_id
    left join academic_import.account_link k on k.student_id=s.id
    where l.status in ('COMPLETED','PARTIALLY_COMPLETED') and
      (public.portal_teaches(p.id) or (k.user_id=auth.uid() and public.portal_enrolled(p.id)))
$$;
revoke all on function public.portal_course_catalog(), public.portal_course_lectures(uuid),
    public.portal_attendance_register() from public, anon, authenticated;
grant execute on function public.portal_course_catalog(), public.portal_course_lectures(uuid),
    public.portal_attendance_register() to authenticated;
notify pgrst, 'reload schema';
commit;
