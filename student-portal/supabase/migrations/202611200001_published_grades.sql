begin;
-- Publication is a snapshot, never a replica of desktop drafts.
create table public.portal_published_grades (
    offering_id uuid not null references public.portal_offerings(id),
    student_id uuid not null,
    score numeric(5,2) not null check (score between 0 and 100),
    letter text not null check (letter in ('A','B','C','D','E','F')),
    published_at timestamptz not null default now(),
    primary key (offering_id, student_id),
    foreign key (offering_id, student_id)
        references public.portal_enrollments(offering_id, student_id)
);
alter table public.portal_published_grades enable row level security;
revoke all on public.portal_published_grades from public, anon, authenticated;
grant select, insert, update on public.portal_published_grades to authenticated;
grant all on public.portal_published_grades to service_role;
create policy published_read on public.portal_published_grades for select to authenticated
using (public.portal_teaches(offering_id) or
    (student_id = auth.uid() and public.portal_enrolled(offering_id)));
create policy published_insert on public.portal_published_grades for insert to authenticated
with check (public.portal_teaches(offering_id));
create policy published_update on public.portal_published_grades for update to authenticated
using (public.portal_teaches(offering_id)) with check (public.portal_teaches(offering_id));
create policy published_mfa on public.portal_published_grades as restrictive to authenticated
using (public.portal_access() is not null) with check (public.portal_access() is not null);
-- Timestamp always comes from the database, including idempotent republishing.
create function public.portal_publication_timestamp() returns trigger
language plpgsql set search_path = '' as $$
begin new.published_at = now(); return new; end
$$;
revoke all on function public.portal_publication_timestamp() from public, anon, authenticated;
create trigger publication_timestamp before insert or update on public.portal_published_grades
for each row execute function public.portal_publication_timestamp();
notify pgrst, 'reload schema';
commit;
