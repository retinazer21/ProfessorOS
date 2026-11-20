import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { extract, transaction } from '../../../tools/academic-import.mjs';

// Supply a verified fictional SQLite snapshot and its explicit account bindings.
const [
    snapshot,
    configPath,
    engine = '.tools/publication-sql/node_modules/@electric-sql/pglite/dist/index.js',
] = process.argv.slice(2);
const { PGlite } = await import(pathToFileURL(resolve(engine)));
const bindings = JSON.parse(await readFile(configPath, 'utf8'));
const data = extract(snapshot, bindings);
const db = new PGlite();
const sql = transaction(data, bindings.professor);
const outsider = 'fcf31fe2-5a92-4f9a-87ca-15d412c4cb71';
try {
    await db.exec(`create role anon; create role authenticated; create role service_role; create schema auth;
        grant usage on schema auth,public to anon,authenticated; create table auth.users(id uuid primary key);
        create table auth.sessions(id uuid primary key,user_id uuid,not_after timestamptz);
        create function auth.jwt() returns jsonb language sql stable as $$select current_setting('request.jwt.claims',true)::jsonb$$;
        create function auth.uid() returns uuid language sql stable as $$ select (auth.jwt()->>'sub')::uuid $$;`);
    for (const file of [
        '202611130001_portal_access',
        '202611200001_published_grades',
        '202611200002_academic_import',
    ])
        await db.exec(await readFile('student-portal/supabase/migrations/' + file + '.sql', 'utf8'));
    for (const [id, role] of [
        [bindings.professor, 'professor'],
        [outsider, 'professor'],
        ...bindings.students.map((s) => [s.user_id, 'student']),
    ]) {
        await db.query('insert into auth.users values($1)', [id]);
        await db.query('insert into public.portal_roles values($1,$2)', [id, role]);
        await db.query('insert into auth.sessions values($1,$1,null)', [id]);
    }
    const legacy = 'e47cb282-d670-4a40-8445-c62119cae2fb';
    await db.query("insert into public.portal_offerings values($1,'Legacy offering',$2)", [legacy, outsider]);
    await db.exec(sql);
    await db.exec(sql);
    for (const [name, rows] of Object.entries(data))
        assert.equal(
            (await db.query('select count(*)::int n from academic_import.' + name)).rows[0].n,
            rows.length,
        );
    assert.equal(
        (await db.query('select * from public.portal_offerings where id=$1', [legacy])).rows.length,
        1,
    );
    assert.equal((await db.query('select * from public.portal_published_grades')).rows.length, 0);
    const drift = structuredClone(data);
    drift.student[0].number += 'changed';
    await assert.rejects(db.exec(transaction(drift, bindings.professor)), /student changed/);
    await db.exec('rollback');
    async function as(id, aal, action) {
        await db.query("select set_config('request.jwt.claims',$1,false)", [
            JSON.stringify({ sub: id, aal, session_id: id }),
        ]);
        await db.exec('set role authenticated');
        try {
            return await action();
        } finally {
            await db.exec('reset role');
        }
    }
    const catalog = () => db.query('select * from public.portal_course_catalog()');
    const register = () => db.query('select * from public.portal_attendance_register()');
    for (const { student_id, user_id } of data.account_link) {
        const mine = data.enrollment.filter((e) => e.student_id === student_id && e.status === 'ACTIVE');
        const expected = data.attendance.filter(
            (m) =>
                mine.some((e) => e.id === m.enrollment_id) &&
                data.lecture.some(
                    (l) => l.id === m.lecture_id && ['COMPLETED', 'PARTIALLY_COMPLETED'].includes(l.status),
                ),
        );
        const courses = (await as(user_id, 'aal2', catalog)).rows;
        assert.deepEqual(courses.map((c) => c.id).sort(), mine.map((e) => e.offering_id).sort());
        const marks = (await as(user_id, 'aal2', register)).rows;
        assert.deepEqual(
            marks.map((m) => [m.enrollment_id, m.lecture_id, m.status].join(':')).sort(),
            expected.map((m) => [m.enrollment_id, m.lecture_id, m.status].join(':')).sort(),
        );
        const other = data.offering.find((o) => !mine.some((e) => e.offering_id === o.id));
        assert.ok(other, 'Use two different courses for the isolation demonstration');
        assert.equal(
            (
                await as(user_id, 'aal2', () =>
                    db.query('select * from public.portal_course_lectures($1)', [other.id]),
                )
            ).rows.length,
            0,
        );
        assert.equal((await as(user_id, 'aal1', register)).rows.length, 0);
        assert.equal((await as(user_id, 'aal1', catalog)).rows.length, 0);
        await assert.rejects(
            as(user_id, 'aal2', () => db.exec('table academic_import.student')),
            /permission denied/,
        );
        await assert.rejects(
            as(user_id, 'aal2', () => db.exec('delete from academic_import.attendance')),
            /permission denied/,
        );
        console.log('Student', mine.length, 'course(s),', marks.length, 'verified attendance marks');
    }
    assert.equal((await as(outsider, 'aal2', register)).rows.length, 0);
    assert.equal((await as(bindings.professor, 'aal1', register)).rows.length, 0);
    assert.equal(
        (await as(bindings.professor, 'aal2', register)).rows.length,
        data.attendance.filter((m) =>
            data.lecture.some(
                (l) => l.id === m.lecture_id && ['COMPLETED', 'PARTIALLY_COMPLETED'].includes(l.status),
            ),
        ).length,
    );
    await db.exec('set role anon');
    await assert.rejects(register(), /permission denied/);
    await db.exec('reset role');
    await db.query('delete from auth.sessions where user_id=$1', [bindings.students[0].user_id]);
    assert.equal((await as(bindings.students[0].user_id, 'aal2', register)).rows.length, 0);
    console.log(
        'PASS: imported snapshot, stable second import, drift rollback, draft privacy, assigned professor, student isolation, AAL1, revoked session and anonymous denial.',
    );
} finally {
    await db.close();
}
