import assert from 'node:assert/strict';
import test from 'node:test';
import { groupAttendance } from '../src/attendance-records.ts';

test('repeated lecture titles remain separate and course groups retain every mark', () => {
    const mark = {course:'Design', lecture:'Introduction', day:'2026-10-01', student:'Student A', status:'PRESENT'};
    const rows = [
        {...mark, lecture_id:'first', enrollment_id:'first-student'},
        {...mark, lecture_id:'second', enrollment_id:'first-student', day:'2026-10-08'},
        {...mark, course:'Systems', lecture_id:'third', enrollment_id:'first-student'},
        {...mark, lecture_id:'first', enrollment_id:'second-student', student:'Student B'},
    ];
    const grouped = groupAttendance(rows);
    assert.deepEqual(grouped.map((item) => item.course), ['Design', 'Systems']);
    assert.deepEqual(grouped[0].lectures.map((group) => group.length), [2, 1]);
    assert.deepEqual(grouped[1].lectures[0], [rows[2]]);
});
