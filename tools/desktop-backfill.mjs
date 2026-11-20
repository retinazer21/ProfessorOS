import { DatabaseSync } from 'node:sqlite';
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';
import { mappedId } from './academic-import.mjs';

// Omit Windows image paths. Refuse populated references until portable assets are available.
const tables = [
    ['course_detail','courses','id:course_id description created_at updated_at'],
    ['term_detail','academic_terms','id:term_id status created_at updated_at'],
    ['offering_detail','course_offerings','id:offering_id notes created_at updated_at'],
    ['student_detail','students','id:student_id email notes created_at updated_at'],
    ['enrollment_detail','enrollments','id:enrollment_id enrolled_at'],
    ['lecture_detail','lectures','id:lecture_id topics description duration_minutes professor_notes created_at updated_at'],
    ['attendance_detail','attendance','enrollment_id lecture_id created_at updated_at'],
    ['questions','questions','id course_id question_type question_text topic difficulty marks_hundredths answer explanation correct_choice_position usage_count last_used_date created_at updated_at'],
    ['question_choices','question_choices','question_id position choice_text'],
    ['exams','exams','id course_offering_id title exam_date duration_minutes intended_marks_hundredths instructions status created_at updated_at'],
    ['exam_questions','exam_questions','exam_id question_id position question_type question_text topic difficulty marks_hundredths answer explanation'],
    ['exam_question_choices','exam_question_choices','exam_id question_id position choice_text is_correct'],
    ['assessment_categories','assessment_categories','id course_offering_id name weight_units created_at updated_at'],
    ['assessments','assessments','id course_offering_id category_id title maximum_units assessment_date created_at updated_at'],
    ['grading_scales','grading_scales','course_offering_id a_units b_units c_units d_units pass_units'],
    ['student_grades','student_grades','enrollment_id assessment_id course_offering_id score_units created_at updated_at']
];
const mappings = {course_id:'course',term_id:'term',offering_id:'offering',course_offering_id:'offering',
    student_id:'student',enrollment_id:'enrollment',lecture_id:'lecture'};

export function preparePrivateBackfill(file,bindings) {
    assert.equal(bindings.fictional,true);
    assert.match(bindings.professor,/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/);
    assert.equal(createHash('sha256').update(readFileSync(file)).digest('hex'),bindings.backup_sha256);
    const db=new DatabaseSync(file,{readOnly:true});
    try {
        db.exec('begin');
        for(const t of ['questions','exam_questions'])
            assert.equal(db.prepare('select count(*) n from '+t+" where image_path<>''").get().n,0,'Unmigrated image references');
        const owner="'"+bindings.professor+"'::uuid";
        const sql=['begin;', "set local lock_timeout='5s';", 'lock table desktop_private.workspace in exclusive mode;',
            'do $$ begin if not exists(select 1 from public.portal_roles where role=\'professor\' and user_id='+owner+
            ') or exists(select 1 from academic_import.offering o join public.portal_offerings p using(id) where p.professor_id<>'+owner+
            ') or exists(select 1 from desktop_private.workspace where professor_id<>'+owner+
            ") then raise exception 'Workspace ownership mismatch'; end if;end $$;",
            'insert into desktop_private.workspace(singleton,professor_id) values(true,'+owner+') on conflict do nothing;'];
        const counts={};
        for (const [target,source,fields] of tables) {
            const columns=fields.split(' ').map(f=>f.split(':').join(' as ')).join(',');
            const rows=db.prepare('select '+columns+' from '+source+' order by rowid').all();
            for(const row of rows) for(const key of Object.keys(row)) if(mappings[key]) row[key]=mappedId(mappings[key],row[key]);
            counts[target]=rows.length;
            const payload=Buffer.from(JSON.stringify(rows)).toString('base64');
            const dst='desktop_private.'+target, stage='stage_'+target;
            sql.push('lock table '+dst+' in share row exclusive mode;',
                'create temp table '+stage+' (like '+dst+' including all) on commit drop;',
                'insert into '+stage+' select * from jsonb_populate_recordset(null::'+dst+",convert_from(decode('"+payload+"','base64'),'UTF8')::jsonb);",
                'insert into '+dst+' table '+stage+' on conflict do nothing;',
                'do $$ begin if exists(table '+stage+' except table '+dst+') or exists(table '+dst+' except table '+stage+
                ") then raise exception 'Conflicting "+target+" data';end if;end $$;");
        }
        return {sql:[...sql,'commit;'].join('\n'),counts};
    } finally {db.close();}
}
if (process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href) {
    const [file,config,out]=process.argv.slice(2);
    const result=preparePrivateBackfill(file,JSON.parse(readFileSync(config,'utf8')));
    writeFileSync(out,result.sql,{flag:'wx',mode:0o600});
    console.table(result.counts);
}
