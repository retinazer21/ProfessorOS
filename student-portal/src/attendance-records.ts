import type { Mark } from './academic';

export function groupAttendance(marks: Mark[]) {
    const courses = new Map<string, Map<string, Mark[]>>();
    for (const mark of marks) {
        if (!courses.has(mark.course)) courses.set(mark.course, new Map());
        const lectures = courses.get(mark.course)!;
        if (!lectures.has(mark.lecture_id)) lectures.set(mark.lecture_id, []);
        lectures.get(mark.lecture_id)!.push(mark);
    }
    return [...courses].map(([course, lectures]) => ({ course, lectures: [...lectures.values()] }));
}
