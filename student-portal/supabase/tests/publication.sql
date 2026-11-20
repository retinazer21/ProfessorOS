-- Run as the trusted migration operator AFTER the new migration. All fixtures roll back.
begin;
insert into auth.users(id) select ('30000000-0000-4000-8000-00000000000'||i)::uuid from generate_series(1,4) i;
insert into auth.sessions(id,user_id,aal) select id,id,'aal2' from auth.users where id::text like '30000000-0000-4000-8000-00000000000%';
insert into public.portal_roles values
('30000000-0000-4000-8000-000000000001','student'),
('30000000-0000-4000-8000-000000000002','student'),
('30000000-0000-4000-8000-000000000003','professor'),
('30000000-0000-4000-8000-000000000004','professor');
insert into public.portal_offerings values ('40000000-0000-4000-8000-000000000001','Fictional publication test','30000000-0000-4000-8000-000000000003');
insert into public.portal_enrollments values ('40000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001'),('40000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000002');
set local role anon;
do $$ begin begin perform * from public.portal_published_grades; raise exception 'Anonymous read accepted'; exception when insufficient_privilege then null; end; end $$;
reset role;
select set_config('request.jwt.claims','{"sub":"30000000-0000-4000-8000-000000000003","session_id":"30000000-0000-4000-8000-000000000003","aal":"aal1","role":"authenticated"}',true);
set local role authenticated;
do $$ begin begin insert into public.portal_published_grades(offering_id,student_id,score,letter) values ('40000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',72,'C'); raise exception 'MFA bypass accepted'; exception when insufficient_privilege then null; end; end $$;
reset role;
select set_config('request.jwt.claims','{"sub":"30000000-0000-4000-8000-000000000004","session_id":"30000000-0000-4000-8000-000000000004","aal":"aal2","role":"authenticated"}',true);
set local role authenticated;
do $$ begin begin insert into public.portal_published_grades(offering_id,student_id,score,letter) values ('40000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',72,'C'); raise exception 'Unassigned professor accepted'; exception when insufficient_privilege then null; end; end $$;
reset role;
select set_config('request.jwt.claims','{"sub":"30000000-0000-4000-8000-000000000003","session_id":"30000000-0000-4000-8000-000000000003","aal":"aal2","role":"authenticated"}',true);
set local role authenticated;
insert into public.portal_published_grades(offering_id,student_id,score,letter) values ('40000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',72,'C');
insert into public.portal_published_grades(offering_id,student_id,score,letter) values ('40000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',72,'C') on conflict(offering_id,student_id) do update set score=excluded.score;
reset role;
select set_config('request.jwt.claims','{"sub":"30000000-0000-4000-8000-000000000002","session_id":"30000000-0000-4000-8000-000000000002","aal":"aal2","role":"authenticated"}',true);
set local role authenticated;
do $$ begin if exists(select 1 from public.portal_published_grades where offering_id='40000000-0000-4000-8000-000000000001') then raise exception 'Cross-student read accepted'; end if; end $$;
reset role;
select set_config('request.jwt.claims','{"sub":"30000000-0000-4000-8000-000000000001","session_id":"30000000-0000-4000-8000-000000000001","aal":"aal2","role":"authenticated"}',true);
set local role authenticated;
do $$ begin if (select count(*) from public.portal_published_grades where offering_id='40000000-0000-4000-8000-000000000001')<>1 then raise exception 'Own read or idempotency failed'; end if; end $$;
do $$ declare changed integer; begin update public.portal_published_grades set score=100 where offering_id='40000000-0000-4000-8000-000000000001'; get diagnostics changed = row_count; if changed<>0 then raise exception 'Student write accepted'; end if; end $$;
reset role;
select set_config('request.jwt.claims','{"sub":"30000000-0000-4000-8000-000000000001","session_id":"30000000-0000-4000-8000-000000000001","aal":"aal1","role":"authenticated"}',true);
set local role authenticated;
do $$ begin if exists(select 1 from public.portal_published_grades) then raise exception 'Password-only read accepted'; end if; end $$;
reset role;
rollback;
