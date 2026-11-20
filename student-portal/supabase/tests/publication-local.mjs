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
    await db.exec(await readFile('student-portal/supabase/migrations/202611200010_grade_publication_revision.sql', 'utf8'));
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
    const publish=()=>db.query("select public.desktop_publish_grade($1,$2,null,72,'C')",[offering,student]);
    assert.equal((await as(student,'aal2',read)).rows.length,0,'Unpublished data is absent');
    await assert.rejects(as(professor,'aal1',publish),{code:'42501'});
    await assert.rejects(as(student,'aal2',publish),{code:'42501'});
    await assert.rejects(as(outsider,'aal2',publish),{code:'42501'});
    await db.exec('set role anon');
    await assert.rejects(publish(),{code:'42501'});
    await db.exec('reset role');
    await as(professor,'aal2',publish);
    await assert.rejects(as(professor,'aal2',publish),{code:'PT409'});
    assert.equal((await as(student,'aal2',read)).rows.length,1,'Duplicate publication leaves one snapshot');
    assert.equal((await as(other,'aal2',read)).rows.length,0,'Other student cannot read');
    assert.equal((await as(student,'aal1',read)).rows.length,0,'Password-only read denied');
    await db.query('delete from auth.sessions where user_id=$1',[student]);
    assert.equal((await as(student,'aal2',read)).rows.length,0,'Revoked session cannot replay');
    await db.query('delete from auth.sessions where user_id=$1',[professor]);
    await assert.rejects(as(professor,'aal2',publish),{code:'42501'});
    console.log('PASS: publication RLS, MFA, student isolation, duplicate creation and revoked sessions.');
} finally { await db.close(); }
