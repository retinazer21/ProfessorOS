import assert from 'node:assert/strict';

export async function verifyGradebookWrites(db, { professor, student }) {
    const session = async (account, aal='aal2') => {
        await db.exec('reset role');
        await db.query("select set_config('request.jwt.claims',$1,false)",[JSON.stringify({sub:account,session_id:account,aal})]);
        await db.exec('set role authenticated');
    };
    const read = async () => (await db.query('select public.desktop_read_workspace() as w')).rows[0].w;
    const write = async changes => (await db.query('select public.desktop_write_gradebook($1) as w',[JSON.stringify(changes)])).rows[0].w;
    const edit = (entity,previous,desired) => ({entity,previous,desired});
    const publications = () => db.query('select * from public.portal_published_grades order by offering_id,student_id').then(r=>r.rows);
    await session(professor);
    const before = await read();
    const published = await publications();
    const sample = before.student_grades[0];
    const assessment = before.assessments.find(a=>a.id===sample.assessment_id);
    const other = before.student_grades.find(g=>g.course_offering_id===sample.course_offering_id && g.assessment_id!==sample.assessment_id);
    const desired = {...sample,score_units:sample.score_units===0?1:0};
    const change = edit('student_grades',sample,desired);
    await session(student);
    await assert.rejects(write([change]),{code:'42501'});
    await session(professor,'aal1');
    await assert.rejects(write([change]),{code:'42501'});
    await session(professor);
    await assert.rejects(write([change,edit('student_grades',{...other,updated_at:'stale'},{...other,score_units:0})]),{code:'PT409'});
    assert.deepEqual(await read(),before);
    const updated = await write([change]);
    assert.equal(updated.student_grades.find(g=>g.enrollment_id===sample.enrollment_id && g.assessment_id===sample.assessment_id).score_units,desired.score_units);
    await assert.rejects(write([change]),{code:'PT409'});
    await assert.rejects(write([edit('student_grades',null,{...sample,score_units:assessment.maximum_units+1})]),{code:'22023'});
    const offering = sample.course_offering_id;
    const category = {course_offering_id:offering,name:'Test 013',weight_units:0};
    const created = await write([edit('assessment_categories',null,category)]);
    const newCategory = created.assessment_categories.find(c=>c.name===category.name);
    const work = {course_offering_id:offering,category_id:newCategory.id,title:'Test 013',maximum_units:1000,assessment_date:'2040-01-01'};
    const withAssessment = await write([edit('assessments',null,work)]);
    const newAssessment = withAssessment.assessments.find(a=>a.title===work.title);
    const score = {enrollment_id:sample.enrollment_id,assessment_id:newAssessment.id,course_offering_id:offering,score_units:500};
    const withGrade = await write([edit('student_grades',null,score)]);
    const newGrade = withGrade.student_grades.find(g=>g.assessment_id===newAssessment.id);
    await assert.rejects(write([edit('assessments',newAssessment,null)]),{code:'23503'});
    await write([edit('assessments',newAssessment,null),edit('student_grades',newGrade,null)]);
    await write([edit('assessment_categories',newCategory,null)]);
    const scale = before.grading_scales.find(s=>s.course_offering_id===offering);
    await write([edit('grading_scales',scale,{...scale,pass_units:scale.pass_units===0?1:0})]);
    assert.deepEqual(await publications(),published);
    console.log('PASS gradebook creation, bulk rollback, CAS, grading scales, dependent deletion and explicit publication boundary.');
}
