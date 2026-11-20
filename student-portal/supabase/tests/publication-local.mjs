// Isolated PostgreSQL/WASM authorization test. Auth tables/functions are stand-ins;
// this does not verify hosted Supabase Auth or PostgREST relationship discovery.
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';
const { PGlite } = await import(pathToFileURL(resolve(process.argv[2] ?? '.tools/publication-sql/node_modules/@electric-sql/pglite/dist/index.js')));
const db = new PGlite();
try {
    await db.exec(`create role anon; create role authenticated; create role service_role;
      create schema auth; grant usage on schema auth, public to anon, authenticated;
      create table auth.users(id uuid primary key);
      create table auth.sessions(id uuid primary key,user_id uuid,aal text,not_after timestamptz);
      create function auth.jwt() returns jsonb language sql stable as $$ select current_setting('request.jwt.claims',true)::jsonb $$;
      create function auth.uid() returns uuid language sql stable as $$ select (auth.jwt()->>'sub')::uuid $$;`);
    for (const name of ['202611130001_portal_access.sql','202611200001_published_grades.sql'])
        await db.exec(await readFile(`student-portal/supabase/migrations/${name}`, 'utf8'));
    await db.exec(await readFile('student-portal/supabase/tests/authorization.sql', 'utf8'));
    await db.exec(await readFile('student-portal/supabase/tests/publication.sql', 'utf8'));
    const student='10000000-0000-4000-8000-000000000001';
    const other='10000000-0000-4000-8000-000000000002';
    const professor='10000000-0000-4000-8000-000000000003';
    const outsider='10000000-0000-4000-8000-000000000004';
    const offering='20000000-0000-4000-8000-000000000001';
    for (const [id, role] of [[student,'student'],[other,'student'],[professor,'professor'],[outsider,'professor']]) {
        await db.query('insert into auth.users values ($1)',[id]);
        await db.query('insert into auth.sessions(id,user_id,aal) values ($1,$1,$2)',[id,'aal2']);
        await db.query('insert into public.portal_roles values ($1,$2)',[id,role]);
    }
    await db.query('insert into public.portal_offerings values ($1,$2,$3)',[offering,'Fictional publication course',professor]);
    await db.query('insert into public.portal_enrollments values ($1,$2),($1,$3)',[offering,student,other]);
    async function as(id,aal,action) {
        await db.query("select set_config('request.jwt.claims',$1,false)",[JSON.stringify({sub:id,role:'authenticated',aal,session_id:id})]);
        await db.exec('set role authenticated');
        try { return await action(); } finally { await db.exec('reset role'); }
    }
    const read=()=>db.query('select * from public.portal_published_grades');
    const publish=()=>db.query(`insert into public.portal_published_grades(offering_id,student_id,score,letter)
      values ($1,$2,72,'C') on conflict(offering_id,student_id) do update set score=excluded.score,letter=excluded.letter returning *`,[offering,student]);
    assert.equal((await as(student,'aal2',read)).rows.length,0,'Unpublished data is absent');
    await assert.rejects(as(professor,'aal1',publish),/row-level security/);
    await assert.rejects(as(student,'aal2',publish),/row-level security/);
    await assert.rejects(as(outsider,'aal2',publish),/row-level security/);
    await as(professor,'aal2',publish);await as(professor,'aal2',publish);
    assert.equal((await as(student,'aal2',read)).rows.length,1,'Duplicate publication updates one snapshot');
    assert.equal((await as(other,'aal2',read)).rows.length,0,'Other student cannot read');
    assert.equal((await as(student,'aal1',read)).rows.length,0,'Password-only read denied');
    await db.query('delete from auth.sessions where user_id=$1',[student]);
    assert.equal((await as(student,'aal2',read)).rows.length,0,'Revoked session cannot replay');
    console.log('PASS: original RLS regression; unpublished privacy, professor ownership, MFA, student isolation, idempotent publication and revoked-session denial.');
} finally { await db.close(); }
