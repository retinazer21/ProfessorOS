/** Offline-only administrator tool. No network client or Supabase keys. */
import { DatabaseSync, backup as sqliteBackup } from 'node:sqlite';
import { createHash } from 'node:crypto';
import { closeSync, openSync, readFileSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

const uuidPattern = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
const namespace = Buffer.from('9e3b1d5ec39d47b18cb59b3da1d3ef66', 'hex');
export function mappedId(table, key) {
    assert.ok(Number.isSafeInteger(key) && key > 0, 'Invalid local database ID');
    const bytes = createHash('sha1')
        .update(namespace)
        .update(table + '/' + key)
        .digest()
        .subarray(0, 16);
    bytes[6] = (bytes[6] & 15) | 80;
    bytes[8] = (bytes[8] & 63) | 128;
    return bytes.toString('hex').replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, '$1-$2-$3-$4-$5');
}

export async function backupDatabase(source, destination) {
    const src = new DatabaseSync(source, { readOnly: true });
    try {
        src.exec('begin');
        const schema = 'select type,name,tbl_name,sql from sqlite_schema order by type,name';
        const expected = src.prepare(schema).all(); // pin a single read snapshot
        closeSync(openSync(destination, 'wx', 0o600));
        await sqliteBackup(src, destination);
        const copy = new DatabaseSync(destination, { readOnly: true });
        try {
            assert.deepEqual(copy.prepare(schema).all(), expected);
            for (const { name, type } of expected) {
                if (type !== 'table') continue;
                const q = 'select * from "' + name.replaceAll('"', '""') + '" order by rowid';
                assert.deepEqual(copy.prepare(q).all(), src.prepare(q).all());
            }
            assert.equal(copy.prepare('pragma integrity_check').get().integrity_check, 'ok');
            assert.equal(copy.prepare('pragma foreign_key_check').all().length, 0);
        } finally {
            copy.close();
        }
        return createHash('sha256').update(readFileSync(destination)).digest('hex');
    } finally {
        src.close();
    }
}

export function extract(file, config) {
    assert.equal(config.fictional, true, 'A fictional-data declaration is required');
    assert.match(config.professor, uuidPattern);
    assert.ok(Array.isArray(config.students), 'Supply explicit student bindings or an empty list');
    const db = new DatabaseSync(file, { readOnly: true });
    try {
        db.exec('begin');
        assert.equal(db.prepare('pragma integrity_check').get().integrity_check, 'ok');
        assert.equal(db.prepare('pragma foreign_key_check').all().length, 0);
        const select = (sql) => db.prepare(sql).all();
        const entity = (name, sql) =>
            select(sql).map(({ local_id, ...fields }) => ({
                id: mappedId(name, local_id),
                local_id,
                ...fields,
            }));
        const course = entity(
            'course',
            'select id local_id,course_code code,course_name title,department,credit_hours credits from courses order by id',
        );
        const term = entity(
            'term',
            'select id local_id,name,academic_year,start_date starts,end_date ends from academic_terms order by id',
        );
        const student = entity(
            'student',
            'select id local_id,student_number number,first_name,last_name from students order by id',
        );
        const offering = entity(
            'offering',
            'select id local_id,course_id,academic_term_id term_id,section,status from course_offerings order by id',
        ).map((row) => ({
            ...row,
            course_id: mappedId('course', row.course_id),
            term_id: mappedId('term', row.term_id),
        }));
        const enrollment = entity(
            'enrollment',
            'select id local_id,course_offering_id offering_id,student_id,status from enrollments order by id',
        ).map((row) => ({
            ...row,
            offering_id: mappedId('offering', row.offering_id),
            student_id: mappedId('student', row.student_id),
        }));
        const lecture = entity(
            'lecture',
            'select id local_id,course_offering_id offering_id,lecture_number number,title,planned_date day,status from lectures order by id',
        ).map((row) => ({ ...row, offering_id: mappedId('offering', row.offering_id) }));
        const attendance = select(
            'select enrollment_id,lecture_id,course_offering_id offering_id,status from attendance order by enrollment_id,lecture_id',
        ).map((row) => ({
            ...row,
            enrollment_id: mappedId('enrollment', row.enrollment_id),
            lecture_id: mappedId('lecture', row.lecture_id),
            offering_id: mappedId('offering', row.offering_id),
        }));
        const account_link = config.students.map(({ number, user_id }) => {
            assert.match(user_id, uuidPattern);
            const match = student.find((row) => row.number === number);
            assert.ok(match, 'Unknown desktop student number in bindings');
            return { student_id: match.id, user_id };
        });
        return { course, term, student, offering, enrollment, lecture, attendance, account_link };
    } finally {
        db.close();
    }
}

function guard(condition, message) {
    return 'do $$ begin if ' + condition + " then raise exception '" + message + "'; end if; end $$;";
}
export function transaction(data, professor) {
    assert.match(professor, uuidPattern);
    const sql = [
        'begin;',
        "set local lock_timeout = '5s';",
        'lock table public.portal_roles, public.portal_offerings, public.portal_enrollments in share row exclusive mode;',
        guard(
            "not exists(select 1 from public.portal_roles where role='professor' and user_id='" +
                professor +
                "')",
            'Professor binding failed',
        ),
    ];
    for (const [name, rows] of Object.entries(data)) {
        const dst = 'academic_import.' + name,
            src = 'stage_' + name;
        const encoded = Buffer.from(JSON.stringify(rows)).toString('base64');
        sql.push(
            'lock table ' + dst + ' in share row exclusive mode;',
            'create temporary table ' + src + ' (like ' + dst + ' including all) on commit drop;',
            'insert into ' +
                src +
                ' select * from jsonb_populate_recordset(null::' +
                dst +
                ",convert_from(decode('" +
                encoded +
                "','base64'),'UTF8')::jsonb);",
            guard(
                'exists(table ' + dst + ' except table ' + src + ')',
                name + ' changed or missing from source',
            ),
        );
        if (name === 'offering') {
            sql.push(
                guard(
                    'exists(select 1 from stage_offering s join public.portal_offerings p using(id) left join academic_import.offering a using(id) where a.id is null)',
                    'Existing offering UUID collision',
                ),
                "create temporary table wanted_offerings on commit drop as select s.id,c.title,'" +
                    professor +
                    "'::uuid professor_id from stage_offering s join stage_course c on s.course_id=c.id;",
                guard(
                    'exists(select p.id,p.title,p.professor_id from public.portal_offerings p join stage_offering s using(id) except table wanted_offerings)',
                    'Offering ownership or title differs',
                ),
                'insert into public.portal_offerings(id,title,professor_id) select * from wanted_offerings on conflict(id) do nothing;',
            );
        }
        if (name === 'account_link')
            sql.push(
                guard(
                    "exists(select 1 from stage_account_link k left join public.portal_roles r on r.user_id=k.user_id where r.role is distinct from 'student')",
                    'Student binding failed',
                ),
            );
        sql.push(
            'insert into ' + dst + ' select * from ' + src + ' on conflict do nothing;',
            guard('exists(table ' + src + ' except table ' + dst + ')', 'Incomplete ' + name + ' import'),
        );
    }
    sql.push(
        "create temporary table wanted_enrollments on commit drop as select e.offering_id,k.user_id student_id from stage_enrollment e join stage_account_link k using(student_id) where e.status='ACTIVE';",
        guard(
            'exists(select p.offering_id,p.student_id from public.portal_enrollments p join stage_offering s on s.id=p.offering_id except table wanted_enrollments)',
            'Unexpected portal enrollment in imported offering',
        ),
        'insert into public.portal_enrollments(offering_id,student_id) table wanted_enrollments on conflict do nothing;',
        guard(
            'exists(table wanted_enrollments except select offering_id,student_id from public.portal_enrollments)',
            'Missing portal enrollment',
        ),
        'commit;',
    );
    return sql.join('\n');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const [action, file, arg, out] = process.argv.slice(2);
    if (action === 'backup') console.log('Verified backup SHA-256', await backupDatabase(file, arg));
    else if (action === 'prepare') {
        const config = JSON.parse(readFileSync(arg, 'utf8').replace(/^\uFEFF/, ''));
        const checksum = createHash('sha256').update(readFileSync(file)).digest('hex');
        assert.equal(checksum, config.backup_sha256, 'Backup checksum differs');
        const data = extract(file, config);
        writeFileSync(out, transaction(data, config.professor), { flag: 'wx', mode: 0o600 });
        console.table(Object.fromEntries(Object.entries(data).map(([table, rows]) => [table, rows.length])));
    } else throw Error('Usage: backup SOURCE NEW_BACKUP | prepare VERIFIED_BACKUP CONFIG NEW_PRIVATE_SQL');
}
