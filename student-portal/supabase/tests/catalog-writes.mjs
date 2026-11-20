import assert from 'node:assert/strict';

export async function verifyCatalogWrites(db, { professor, student }) {
    const session = async (account, aal='aal2') => {
        await db.exec('reset role');
        await db.query("select set_config('request.jwt.claims',$1,false)", [
            JSON.stringify({ sub:account, session_id:account, aal }),
        ]);
        await db.exec('set role authenticated');
    };
    const submit = async changes => (await db.query('select public.desktop_write_catalog($1) as workspace',
        [JSON.stringify(changes)])).rows[0].workspace;
    const edit = (entity, previous, desired) => ({ entity, previous, desired });
    const read = async () => (await db.query('select public.desktop_read_workspace() as w')).rows[0].w;
    await session(professor);
    const before = await read();
    const course = {course_code:'TEST-011',course_name:'Catalog writer test',department:'Fictional',credit_hours:3,description:''};
    const term = {name:'Catalog writer term',academic_year:'2040',start_date:'2040-01-01',end_date:'2040-06-01',status:'PLANNED'};
    const roster = {student_number:'TEST-011',first_name:'Test',last_name:'Student',email:'',notes:'Private note'};
    const initial = [edit('courses',null,course),edit('academic_terms',null,term),edit('students',null,roster)];
    await session(professor,'aal1');
    await assert.rejects(submit(initial),{code:'42501'});
    await session(student);
    await assert.rejects(submit(initial),{code:'42501'});
    await session(professor);
    const started = await submit(initial);
    const newCourse = started.courses.find(item=>item.course_code===course.course_code);
    const newTerm = started.academic_terms.find(item=>item.name===term.name);
    const newStudent = started.students.find(item=>item.student_number===roster.student_number);
    assert.ok(newCourse && newTerm && newStudent);
    assert.deepEqual(started.attendance,before.attendance);
    assert.deepEqual(started.student_grades,before.student_grades);
    assert.deepEqual(started.exams,before.exams);
    // A stale second row must roll back the first row too.
    const staleStudent = {...newStudent,notes:'Wrong baseline'};
    await assert.rejects(submit([edit('courses',newCourse,{...newCourse,course_name:'Rolled back rename'}),
        edit('students',staleStudent,{...newStudent,notes:'Not saved'})]),{code:'PT409'});
    assert.deepEqual(await read(),started);
    const renamed = (await submit([edit('courses',newCourse,{...newCourse,course_name:'Renamed'})]))
        .courses.find(item=>item.id===newCourse.id);
    await assert.rejects(submit([edit('courses',newCourse,{...newCourse,course_name:'Stale'})]),{code:'PT409'});
    const offering = {course_id:newCourse.id,academic_term_id:newTerm.id,section:'01',status:'ACTIVE',notes:''};
    const next = await submit([edit('course_offerings',null,offering)]);
    const newOffering = next.course_offerings.find(item=>item.course_id===newCourse.id);
    assert.ok(newOffering);
    const lecture = {course_offering_id:newOffering.id,lecture_number:1,title:'First lecture',planned_date:'2040-01-10',
        status:'COMPLETED',topics:'',description:'',duration_minutes:50,professor_notes:'Private note'};
    const planned = await submit([edit('lectures',null,lecture)]);
    const newLecture = planned.lectures.find(item=>item.course_offering_id===newOffering.id);
    assert.ok(newLecture);
    await assert.rejects(submit([edit('courses',renamed,null)]),{code:'23503'});
    const updated = await submit([edit('lectures',newLecture,{...newLecture,duration_minutes:55}),
        edit('academic_terms',newTerm,{...newTerm,status:'ACTIVE'}),
        edit('students',newStudent,{...newStudent,notes:'Updated'})]);
    assert.equal(updated.lectures.find(item=>item.id===newLecture.id).duration_minutes,55);
    assert.equal(updated.academic_terms.find(item=>item.id===newTerm.id).status,'ACTIVE');
    assert.equal(updated.students.find(item=>item.id===newStudent.id).notes,'Updated');
    const cleaned = await submit([
        edit('lectures',updated.lectures.find(item=>item.id===newLecture.id),null),
        edit('course_offerings',newOffering,null),edit('courses',renamed,null),
        edit('academic_terms',updated.academic_terms.find(item=>item.id===newTerm.id),null),
        edit('students',updated.students.find(item=>item.id===newStudent.id),null),
    ]);
    assert.deepEqual(cleaned,before);
    console.log('PASS course, term, student, offering and lecture lifecycle, stale batch rollback, MFA and preserved private records.');
}
