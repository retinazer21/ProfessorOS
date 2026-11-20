import assert from 'node:assert/strict';

export async function verifyRosterWrites(db, { professor, student }) {
    async function signIn(account, aal='aal2') {
        await db.exec('reset role');
        await db.query("select set_config('request.jwt.claims',$1,false)", [
            JSON.stringify({sub:account,session_id:account,aal}),
        ]);
        await db.exec('set role authenticated');
    }
    const write = async (catalog, enrollments) => (await db.query('select public.desktop_write_roster($1,$2) as w',
        [JSON.stringify(catalog),JSON.stringify(enrollments)])).rows[0].w;
    const read = async () => (await db.query('select public.desktop_read_workspace() as w')).rows[0].w;
    const edit = (previous, desired, student_number) => ({previous,desired,student_number});
    const portal = async () => (await db.query('select * from public.portal_course_catalog()')).rows;
    await signIn(student);
    const courses = await portal();
    const imported = courses.filter(course=>course.code!==null);
    assert.equal(imported.length,1);
    const target = imported[0].id;
    const marks = (await db.query('select * from public.portal_attendance_register()')).rows;
    assert.ok(marks.length>0);
    await signIn(professor);
    const before = await read();
    await db.exec('reset role');
    const refs=(await db.query(
        'select e.local_id id, s.number from academic_import.enrollment e join academic_import.account_link k on k.student_id=e.student_id join academic_import.student s on s.id=e.student_id where k.user_id=$1 and e.offering_id=$2',
        [student,target],
    )).rows[0];
    const existing = before.enrollments.find(row=>row.id===refs.id);
    assert.equal(existing.status,'ACTIVE');
    const withdraw = edit(existing,{...existing,status:'WITHDRAWN'},refs.number);
    await signIn(professor,'aal1');
    await assert.rejects(write([],[withdraw]),{code:'42501'});
    await signIn(student);
    await assert.rejects(write([],[withdraw]),{code:'42501'});
    await signIn(professor);
    const pending = {entity:'students',previous:null,desired:{
        student_number:'TEST-012',first_name:'Roster',last_name:'Fixture',email:'',notes:'',
    }};
    await assert.rejects(write([pending],[edit(null,{course_offering_id:-1,status:'ACTIVE'},'TEST-012')]),{code:'42501'});
    assert.deepEqual(await read(),before,'Failed enrollment must roll back new student');
    const changed = await write([],[withdraw]);
    assert.deepEqual(changed.attendance,before.attendance);
    assert.deepEqual(changed.student_grades,before.student_grades);
    await assert.rejects(write([],[withdraw]),{code:'PT409'});
    await signIn(student);
    assert.deepEqual(await portal(),courses.filter(course=>course.id!==target));
    assert.equal((await db.query('select * from public.portal_attendance_register()')).rows.length,0);
    assert.equal((await db.query('select * from public.portal_published_grades where offering_id=$1',[target])).rows.length,0);
    await signIn(professor);
    const withdrawn = changed.enrollments.find(row=>row.id===existing.id);
    const restored = await write([],[edit(withdrawn,existing,refs.number)]);
    assert.deepEqual(restored,before);
    await signIn(student);
    assert.deepEqual(await portal(),courses);
    assert.deepEqual((await db.query('select * from public.portal_attendance_register()')).rows,marks);
    console.log('PASS withdrawal, historical marks, restored access, stale roster edit and failed Excel batch rollback.');
}
