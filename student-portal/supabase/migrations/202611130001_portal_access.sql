begin;
-- New portal tables only. Desktop SQLite migrations and data are independent.
create table public.portal_roles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    role text not null check (role in ('student', 'professor'))
);
create table public.portal_offerings (
    id uuid primary key default gen_random_uuid(),
    title text not null,
    professor_id uuid not null references public.portal_roles(user_id)
);
create table public.portal_enrollments (
    offering_id uuid not null references public.portal_offerings(id),
    student_id uuid not null references public.portal_roles(user_id),
    primary key (offering_id, student_id)
);
-- Published records only; future desktop synchronization requires an explicit mapping.
create table public.portal_records (
    id uuid primary key default gen_random_uuid(),
    offering_id uuid not null,
    student_id uuid not null,
    kind text not null check (kind in ('grade', 'attendance')),
    label text not null,
    value text not null,
    foreign key (offering_id, student_id) references public.portal_enrollments(offering_id, student_id)
);
create index portal_records_student on public.portal_records(student_id);
create index portal_offerings_professor on public.portal_offerings(professor_id);
create index portal_enrollments_student on public.portal_enrollments(student_id);

-- SECURITY DEFINER is narrowly scoped: no parameters, dynamic SQL or writable search path.
-- A live auth.sessions row prevents replay of a signed-out but unexpired access JWT.
create function public.portal_access() returns text
language sql stable security definer set search_path = '' as $$
    select r.role from public.portal_roles r
    where r.user_id = auth.uid()
      and auth.jwt()->>'aal' = 'aal2'
      and exists (select 1 from auth.sessions s
          where s.id::text = auth.jwt()->>'session_id' and s.user_id = auth.uid()
            and (s.not_after is null or s.not_after > now()))
$$;
revoke all on function public.portal_access() from public, anon;
grant execute on function public.portal_access() to authenticated;

create function public.portal_teaches(offering uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select public.portal_access() = 'professor' and exists (
        select 1 from public.portal_offerings o where o.id = offering and o.professor_id = auth.uid())
$$;
create function public.portal_enrolled(offering uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select public.portal_access() = 'student' and exists (
        select 1 from public.portal_enrollments e where e.offering_id = offering and e.student_id = auth.uid())
$$;
revoke all on function public.portal_teaches(uuid), public.portal_enrolled(uuid) from public, anon;
grant execute on function public.portal_teaches(uuid), public.portal_enrolled(uuid) to authenticated;

alter table public.portal_roles enable row level security;
alter table public.portal_offerings enable row level security;
alter table public.portal_enrollments enable row level security;
alter table public.portal_records enable row level security;
revoke all on public.portal_roles, public.portal_offerings, public.portal_enrollments, public.portal_records from anon, authenticated;
grant select on public.portal_roles, public.portal_offerings, public.portal_enrollments, public.portal_records to authenticated;
grant insert, update, delete on public.portal_records to authenticated;
grant all on public.portal_roles, public.portal_offerings, public.portal_enrollments, public.portal_records to service_role;

create policy own_role on public.portal_roles for select to authenticated
using (user_id = auth.uid() and public.portal_access() is not null);
create policy offering_access on public.portal_offerings for select to authenticated
using (public.portal_teaches(id) or public.portal_enrolled(id));
create policy enrollment_access on public.portal_enrollments for select to authenticated
using (public.portal_teaches(offering_id) or (student_id = auth.uid() and public.portal_access() = 'student'));
create policy record_read on public.portal_records for select to authenticated
using (public.portal_teaches(offering_id) or (student_id = auth.uid() and public.portal_enrolled(offering_id)));
create policy professor_insert on public.portal_records for insert to authenticated
with check (public.portal_teaches(offering_id));
create policy professor_update on public.portal_records for update to authenticated
using (public.portal_teaches(offering_id)) with check (public.portal_teaches(offering_id));
create policy professor_delete on public.portal_records for delete to authenticated
using (public.portal_teaches(offering_id));

-- Separate restrictive gate remains effective if a future permissive policy is added.
create policy role_mfa on public.portal_roles as restrictive to authenticated
using (public.portal_access() is not null) with check (public.portal_access() is not null);
create policy offering_mfa on public.portal_offerings as restrictive to authenticated
using (public.portal_access() is not null) with check (public.portal_access() is not null);
create policy enrollment_mfa on public.portal_enrollments as restrictive to authenticated
using (public.portal_access() is not null) with check (public.portal_access() is not null);
create policy record_mfa on public.portal_records as restrictive to authenticated
using (public.portal_access() is not null) with check (public.portal_access() is not null);
commit;
