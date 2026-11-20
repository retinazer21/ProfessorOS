import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { DatabaseSync } from 'node:sqlite';
import { preparePrivateBackfill } from '../../../tools/desktop-backfill.mjs';
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
    await db.exec(await readFile('student-portal/supabase/migrations/202611200003_desktop_attendance.sql','utf8'));
    // Reproduce the hosted order: the original attendance rollout predates the private tables.
    await db.exec(await readFile('student-portal/supabase/migrations/202611200006_attendance_sheet.sql','utf8'));
    await db.exec(await readFile('student-portal/supabase/migrations/202611200007_attendance_conflict.sql','utf8'));
    await db.exec(await readFile('student-portal/supabase/migrations/202611200004_private_desktop_domains.sql','utf8'));
    const backfill=preparePrivateBackfill(snapshot,bindings);
    await db.exec(backfill.sql);
    await db.exec(backfill.sql);
    for(const [table,count] of Object.entries(backfill.counts))
        assert.equal((await db.query('select count(*)::int n from desktop_private.'+table)).rows[0].n,count);
    await assert.rejects(as(data.account_link[0].user_id,'aal2',()=>db.exec('table desktop_private.student_grades')),/permission denied/);
    await assert.rejects(as(outsider,'aal2',()=>db.exec('table desktop_private.questions')),/permission denied/);
    console.log('PASS: private domain backfill, stable mappings, identical rerun and draft/answer isolation');
    await db.exec(await readFile('student-portal/supabase/migrations/202611200005_desktop_workspace_read.sql','utf8'));
    await db.exec(await readFile('student-portal/supabase/migrations/202611200008_attendance_detail_lifecycle.sql','utf8'));
    await db.exec(await readFile('student-portal/supabase/migrations/202611200009_attendance_eligibility.sql','utf8'));
    const workspace = () => db.query('select public.desktop_read_workspace() as records');
    await db.exec('set role anon');
    await assert.rejects(workspace(), /permission denied/);
    await db.exec('reset role');
    await assert.rejects(as(bindings.professor,'aal1',workspace), /Authorized workspace owner/);
    await assert.rejects(as(outsider,'aal2',workspace), /Authorized workspace owner/);
    await assert.rejects(as(bindings.students[0].user_id,'aal2',workspace), /Authorized workspace owner/);
    const records = (await as(bindings.professor,'aal2',workspace)).rows[0].records;
    const original = new DatabaseSync(snapshot,{readOnly:true});
    try {
        const tables = original.prepare("select name from sqlite_schema where type='table' and name not in ('schema_version','sqlite_sequence')").all();
        assert.deepEqual(Object.keys(records).sort(),tables.map(t=>t.name).sort());
        const canonical = row => JSON.stringify(row,Object.keys(row).sort());
        for(const {name} of tables) {
            const expected = original.prepare('select * from "'+name.replaceAll('"','""')+'"').all();
            assert.deepEqual(records[name].map(canonical).sort(),expected.map(canonical).sort(),name);
        }
    } finally { original.close(); }
    await db.query('update public.portal_offerings set professor_id=$1 where id=$2',[outsider,data.offering[0].id]);
    await assert.rejects(as(bindings.professor,'aal2',workspace), /Authorized workspace owner/);
    await db.query('update public.portal_offerings set professor_id=$1 where id=$2',[bindings.professor,data.offering[0].id]);
    console.log('PASS: professor-only workspace projection matches every source academic row and field');
    const sample = data.attendance.find(m => data.enrollment.some(e=>e.id===m.enrollment_id && e.student_id===data.account_link[0].student_id));
    const updated = sample.status === 'LATE' ? 'PRESENT' : 'LATE';
    const sheet = () => db.query('select public.desktop_attendance_sheet($1) as sheet',[sample.offering_id]);
    const edit = (mark,expected,desired)=>({enrollment:mark.enrollment_id,lecture:mark.lecture_id,expected,desired});
    const save = edits => db.query('select public.desktop_save_attendance_sheet($1,$2) as count',
        [sample.offering_id,JSON.stringify(edits)]);
    const clear = edit(sample,sample.status,null);
    const professor = fn => as(bindings.professor,'aal2',fn);
    for(const [id,aal] of [[bindings.professor,'aal1'],[outsider,'aal2'],[bindings.students[0].user_id,'aal2']]) {
        await assert.rejects(as(id,aal,sheet),/assignment required/);
        await assert.rejects(as(id,aal,()=>save([clear])),/assignment required/);
    }
    const readSheet = async () => (await professor(sheet)).rows[0].sheet;
    const restored = await readSheet();
    // PostgreSQL processes the cells in UUID order. The first update must roll back
    // when the second expected mark is stale, even if the caller lists them in reverse.
    const [first, second] = data.attendance.filter(m => m.enrollment_id === sample.enrollment_id)
        .sort((a,b) => a.lecture_id.localeCompare(b.lecture_id));
    const alternate = status => status === 'EXCUSED' ? 'LATE' : 'EXCUSED';
    const stale = ['ABSENT', 'PRESENT', 'LATE'].find(s => s !== second.status && s !== alternate(second.status));
    await assert.rejects(professor(() => save([
        edit(second, stale, alternate(second.status)), edit(first, first.status, alternate(first.status))
    ])), {code:'PT409'});
    const byCell = marks => marks.toSorted((a,b) =>
        a.enrollment.localeCompare(b.enrollment) || a.lecture.localeCompare(b.lecture));
    assert.deepEqual(byCell((await readSheet()).marks), byCell(restored.marks));
    assert.equal(restored.students.length,data.enrollment.filter(e=>e.offering_id===sample.offering_id).length);
    assert.equal((await professor(()=>save([clear]))).rows[0].count,1);
    const blank = await readSheet();
    assert.deepEqual(blank.students,restored.students);
    assert.equal(blank.marks.length,restored.marks.length-1);
    assert.ok(!blank.marks.some(m=>m.enrollment===clear.enrollment && m.lecture===clear.lecture));
    const otherCell = data.attendance.find(m=>m.offering_id===sample.offering_id && m.enrollment_id!==sample.enrollment_id);
    const otherEdit = edit(otherCell,otherCell.status,otherCell.status==='LATE'?'PRESENT':'LATE');
    await assert.rejects(professor(()=>save([otherEdit,clear])),/Attendance changed/);
    assert.deepEqual((await readSheet()).marks,blank.marks);
    await professor(()=>save([edit(sample,null,sample.status)]));
    const detail = async () => (await db.query(
        'select created_at,updated_at from desktop_private.attendance_detail where enrollment_id=$1 and lecture_id=$2',
        [sample.enrollment_id,sample.lecture_id])).rows[0];
    const inserted = await detail();
    assert.match(inserted.created_at,/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/);
    assert.equal(inserted.created_at,inserted.updated_at);
    await professor(()=>save([edit(sample,sample.status,updated)]));
    const changed = await detail();
    assert.equal(changed.created_at,inserted.created_at);
    assert.ok(changed.updated_at > inserted.updated_at);
    await professor(()=>save([edit(sample,updated,sample.status)]));
    await assert.rejects(professor(()=>save([edit(sample,null,updated)])),/Attendance changed/);
    const outside=data.attendance.find(m=>m.offering_id!==sample.offering_id);
    await assert.rejects(professor(()=>save([edit(outside,outside.status,null)])),/Cell unavailable/);
    assert.equal((await readSheet()).marks.length,restored.marks.length);
    console.log('PASS: scoped roster, explicit clears, unmarked insert, MFA, ownership, conflict and atomic batch rollback');
    const change = (expected,next)=>db.query('select public.desktop_change_attendance($1,$2,$3,$4) as status',
        [sample.enrollment_id,sample.lecture_id,expected,next]);
    const write = ()=>change(sample.status,updated);
    // Include both writers and every lecture state in the eligibility contract.
    // Roll each fixture back, including timestamps and the enrollment status.
    for (const [enrollmentStatus,lectureStatus,allowed] of [
        ['WITHDRAWN','COMPLETED',false],['ACTIVE','PLANNED',false],['ACTIVE','CANCELLED',false],
        ['ACTIVE','COMPLETED',true],['ACTIVE','PARTIALLY_COMPLETED',true],
    ]) {
        await db.transaction(async tx => {
            await tx.query('update academic_import.enrollment set status=$2 where id=$1',[sample.enrollment_id,enrollmentStatus]);
            await tx.query('update academic_import.lecture set status=$2 where id=$1',[sample.lecture_id,lectureStatus]);
            await tx.query("select set_config('request.jwt.claims',$1,true)",[JSON.stringify({sub:bindings.professor,session_id:bindings.professor,aal:'aal2'})]);
            await tx.exec('set local role authenticated');
            const legacy = () => tx.query('select public.desktop_change_attendance($1,$2,$3,$4) as mark',[sample.enrollment_id,sample.lecture_id,sample.status,updated]);
            const batch = () => tx.query('select public.desktop_save_attendance_sheet($1,$2) as count',[sample.offering_id,JSON.stringify([edit(sample,allowed?updated:sample.status,allowed?sample.status:updated)])]);
            if(allowed){
                assert.equal((await legacy()).rows[0].mark,updated);
                assert.equal((await batch()).rows[0].count,1);
            } else {
                for(const call of [legacy,batch]){
                    await tx.exec('savepoint rejected_cell');
                    await assert.rejects(call(),{code:'42501'});
                    await tx.exec('rollback to rejected_cell');
                }
            }
            await tx.rollback();
        });
    }
    await assert.rejects(as(bindings.professor,'aal1',write),/verification required/);
    await assert.rejects(as(data.account_link[0].user_id,'aal2',write),/verification required/);
    await assert.rejects(as(outsider,'aal2',write),/unavailable or changed/);
    assert.equal((await as(bindings.professor,'aal2',write)).rows[0].status,updated);
    await assert.rejects(as(bindings.professor,'aal2',write),/unavailable or changed/);
    assert.equal((await as(data.account_link[0].user_id,'aal2',()=>db.query(
        'select status from public.portal_attendance_register() where enrollment_id=$1 and lecture_id=$2',
        [sample.enrollment_id,sample.lecture_id]))).rows[0].status,updated);
    await as(bindings.professor,'aal2',()=>change(updated,sample.status));
    console.log('PASS: scoped desktop attendance update, concurrent edit conflict and portal visibility');
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
