import { useMemo, useState } from 'react';
import { Search } from 'lucide-react';
import type { Mark } from './academic';
import { groupAttendance } from './attendance-records';
import { Panel } from './ui';

const statuses = ['PRESENT', 'ABSENT', 'LATE', 'EXCUSED'];
const labels: Record<string, string> = { PRESENT: 'Present', ABSENT: 'Absent', LATE: 'Late', EXCUSED: 'Excused', COMPLETED: 'Completed', PARTIALLY_COMPLETED: 'Partially completed' };
const dayFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: 'UTC' });

export function formatRecordDate(value: string) {
    return dayFormat.format(new Date(value));
}

export function RecordStatus({ status }: { status: string }) {
    return <span className="record-status" data-status={status}>{labels[status] ?? status}</span>;
}

export function AttendanceRecords({ marks }: { marks: Mark[] }) {
    const [query, setQuery] = useState('');
    const [course, setCourse] = useState('');
    const [lecture, setLecture] = useState('');
    const [status, setStatus] = useState('');
    const [order, setOrder] = useState('new');
    const [page, setPage] = useState(0);
    const courses = useMemo(() => [...new Set(marks.map((mark) => mark.course))].sort(), [marks]);
    const lectures = useMemo(() => [...new Map(marks.filter((mark) => !course || mark.course === course)
        .map((mark) => [mark.lecture_id, mark])).values()].sort((a,b) => b.day.localeCompare(a.day)), [marks, course]);
    const visible = useMemo(() => marks.filter((mark) =>
        (!course || mark.course === course) && (!lecture || mark.lecture_id === lecture)
        && (!status || mark.status === status)
        && [mark.course, mark.lecture, mark.student, mark.day].some((field) => field.toLowerCase().includes(query.trim().toLowerCase()))
    ).sort((a,b) => (order === 'old' ? a.day.localeCompare(b.day) : b.day.localeCompare(a.day))
        || a.lecture.localeCompare(b.lecture) || a.student.localeCompare(b.student)), [marks, course, lecture, status, query, order]);
    const groups = groupAttendance(visible);
    const pageSize = 8;
    const flat = groups.flatMap(({ course, lectures: grouped }) => grouped.map((rows) => ({ course, rows })));
    const byCourse = groupAttendance(flat.slice(page*pageSize, (page+1)*pageSize).flatMap(({ rows }) => rows));
    if (!marks.length) return <Panel><p>No attendance has been recorded for your account.</p></Panel>;
    return (
        <Panel className="academic-records">
            <div className="record-filters">
                <label>Search <span className="search-box"><Search size={16} aria-hidden="true" /><input type="search" value={query}
                    placeholder="Lecture or student" onChange={(event) => {setQuery(event.target.value); setPage(0);}} /></span></label>
                <label>Course <span className="select-wrap"><select value={course} onChange={(event) => {setCourse(event.target.value); setLecture(''); setPage(0);}}>
                    <option value="">All courses</option>{courses.map((name) => <option key={name}>{name}</option>)}
                </select></span></label>
                <label>Lecture <span className="select-wrap"><select value={lecture} onChange={(event) => {setLecture(event.target.value); setPage(0);}}>
                    <option value="">All lectures</option>{lectures.map((item) => <option key={item.lecture_id} value={item.lecture_id}>{item.lecture} · {formatRecordDate(item.day)}</option>)}
                </select></span></label>
                <label>Status <span className="select-wrap"><select value={status} onChange={(event) => {setStatus(event.target.value); setPage(0);}}>
                    <option value="">Any status</option>{statuses.map((item) => <option key={item} value={item}>{labels[item]}</option>)}
                </select></span></label>
                <label>Sort by date <span className="select-wrap"><select value={order} onChange={(event) => {setOrder(event.target.value); setPage(0);}}>
                    <option value="new">Newest first</option><option value="old">Oldest first</option>
                </select></span></label>
            </div>
            <ul className="record-summary" aria-label="Filtered attendance totals">
                {statuses.map((item) => <li key={item}><strong>{visible.filter((mark) => mark.status === item).length}</strong> {labels[item]}</li>)}
            </ul>
            <p role="status" className="muted-note">{visible.length ? `${visible.length} of ${marks.length} recorded marks` : 'No marks match the current filters.'}</p>
            {byCourse.map(({course, lectures: grouped}) => <section className="attendance-course" key={course}>
                <h2>{course}</h2>
                {grouped.map((rows) => <details key={rows[0].lecture_id} className="attendance-lecture" open={rows.length === 1 || undefined}>
                    <summary><span><strong>{rows[0].lecture}</strong><small>{formatRecordDate(rows[0].day)}</small></span>
                        {rows.length === 1 ? <RecordStatus status={rows[0].status} /> : <span className="muted-note">{rows.length} marks</span>}</summary>
                    <ul className="record-list">{rows.map((mark) => <li key={mark.enrollment_id}>
                        <strong>{mark.student}</strong><RecordStatus status={mark.status} />
                    </li>)}</ul>
                </details>)}
            </section>)}
            {flat.length > pageSize && <nav className="record-pagination" aria-label="Lecture pages">
                <button className="button-secondary" disabled={page === 0} onClick={() => setPage(page-1)}>Previous</button>
                <span>Page {page+1} of {Math.ceil(flat.length/pageSize)}</span>
                <button className="button-secondary" disabled={(page+1)*pageSize >= flat.length} onClick={() => setPage(page+1)}>Next</button>
            </nav>}
        </Panel>
    );
}
