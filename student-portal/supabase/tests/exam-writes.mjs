import assert from 'node:assert/strict';

export async function verifyExamWrites(db, { professor, student }) {
    const session = async (account, aal='aal2') => {
        await db.exec('reset role');
        await db.query("select set_config('request.jwt.claims',$1,false)",[JSON.stringify({sub:account,session_id:account,aal})]);
        await db.exec('set role authenticated');
    };
    const json = value => value===null ? null : JSON.stringify(value);
    const question = async (previous,desired,choices,asset=null) => (await db.query(
        'select public.desktop_write_question($1,$2,$3,$4) as w',[json(previous),json(desired),json(choices),asset])).rows[0].w;
    const exam = async (previous,desired,selection) => (await db.query(
        'select public.desktop_write_exam($1,$2,$3) as w',[json(previous),json(desired),json(selection)])).rows[0].w;
    const asset = async key => (await db.query('select public.desktop_read_exam_image($1) as image',[key])).rows[0].image;
    const png='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jVh0AAAAASUVORK5CYII=';
    await db.exec('begin');
    try {
        await session(professor);
        const original = (await db.query('select public.desktop_read_workspace() as w')).rows[0].w;
        const offering = original.course_offerings[0];
        const draft = {course_id:offering.course_id,question_type:'MCQ',question_text:'Test 014 private question',
            topic:'',difficulty:'EASY',marks_hundredths:100,answer:'',explanation:'Private note',
            correct_choice_position:2,image_path:''};
        await session(professor,'aal1');
        await db.exec('savepoint low_assurance');
        await assert.rejects(question(null,draft,['One','Two'],png),{code:'42501'});
        await db.exec('rollback to low_assurance');
        await session(professor);
        const book = await question(null,draft,['One','Two'],png);
        const savedQuestion = book.questions.find(q=>q.question_text===draft.question_text);
        assert.match(savedQuestion.image_path,/^[a-f0-9]{64}$/);
        assert.equal((await asset(savedQuestion.image_path)).replace(/\s/g,''),png);
        const definition = {course_offering_id:offering.id,title:'Test 014 exam',exam_date:'2040-01-30',
            duration_minutes:60,intended_marks_hundredths:150000,instructions:'',status:'DRAFT'};
        const made = await exam(null,definition,[{question_id:savedQuestion.id,marks_hundredths:150000,source:savedQuestion}]);
        const draftExam = made.exams.find(e=>e.title===definition.title);
        const snapshot = made.exam_questions.find(e=>e.exam_id===draftExam.id);
        assert.equal(snapshot.image_path,savedQuestion.image_path);
        assert.equal(snapshot.marks_hundredths,150000);
        assert.deepEqual(made.exam_question_choices.filter(c=>c.exam_id===draftExam.id).map(c=>c.is_correct),[0,1]);
        const changed = await question(savedQuestion,{...savedQuestion,question_text:'Revised bank question',image_path:''},['One','Three']);
        const modifiedQuestion = changed.questions.find(q=>q.id===savedQuestion.id);
        assert.equal(modifiedQuestion.image_path,'');
        const edited = await exam(draftExam,{...draftExam,title:'Test 014 revised'},
            [{question_id:savedQuestion.id,marks_hundredths:150001,source:modifiedQuestion}]);
        const revisedExam = edited.exams.find(e=>e.id===draftExam.id);
        const kept = edited.exam_questions.find(e=>e.exam_id===draftExam.id);
        assert.equal(kept.question_text,savedQuestion.question_text);
        assert.equal(kept.image_path,savedQuestion.image_path);
        assert.deepEqual(edited.exam_question_choices.filter(c=>c.exam_id===draftExam.id).map(c=>c.choice_text),['One','Two']);
        const finalized = await exam(revisedExam,{...revisedExam,status:'FINALIZED'},[]);
        const locked = finalized.exams.find(e=>e.id===draftExam.id);
        assert.equal(finalized.questions.find(q=>q.id===savedQuestion.id).usage_count,1);
        await db.exec('savepoint stale');
        await assert.rejects(exam(revisedExam,{...revisedExam,status:'FINALIZED'},[]),{code:'PT409'});
        await db.exec('rollback to stale');
        const archived = await exam(locked,{...locked,status:'ARCHIVED'},[]);
        assert.equal(archived.exams.find(e=>e.id===draftExam.id).status,'ARCHIVED');
        await session(student);
        await db.exec('savepoint student_denied');
        await assert.rejects(asset(savedQuestion.image_path),{code:'42501'});
        await db.exec('rollback to student_denied');
        console.log('PASS private question images, preserved exam snapshots, larger exam marks, finalization and archive.');
    } finally {
        await db.exec('rollback');
        await db.exec('reset role');
    }
}
