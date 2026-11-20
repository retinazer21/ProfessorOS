import { useState } from 'react';
import { ArrowLeft, Search } from 'lucide-react';
import { AcademicRows, courseMeta, type Course, type Lecture, type Mark } from './academic';
import { PublishedGrades } from './PublishedGrades';
import { CourseArt, CourseCard, PageIntro, Panel, SectionHeading } from './ui';

export function Courses() {
    const [query, setQuery] = useState('');
    const [department, setDepartment] = useState('');
    return (
        <>
            <PageIntro
                eyebrow="YOUR LEARNING"
                title="My courses"
                description="Browse your course offerings and lecture records."
            />
            <AcademicRows<Course> resource="portal_course_catalog">
                {(courses) => {
                    const visible = courses.filter(
                        (c) =>
                            (!department || department === c.department) &&
                            [c.title, c.code, c.term, c.section]
                                .join(' ')
                                .toLowerCase()
                                .includes(query.trim().toLowerCase()),
                    );
                    return (
                        <>
                            <div className="toolbar">
                                <label className="search-box">
                                    <Search size={18} />
                                    <input
                                        aria-label="Search courses"
                                        type="search"
                                        placeholder="Search courses"
                                        value={query}
                                        onChange={(e) => setQuery(e.target.value)}
                                    />
                                </label>
                                <label className="select-wrap">
                                    <select
                                        aria-label="Filter by department"
                                        value={department}
                                        onChange={(e) => setDepartment(e.target.value)}
                                    >
                                        <option value="">All departments</option>
                                        {Array.from(new Set(courses.map((c) => c.department)))
                                            .filter(Boolean)
                                            .map((d) => (
                                                <option key={d} value={d!}>
                                                    {d}
                                                </option>
                                            ))}
                                    </select>
                                </label>
                            </div>
                            {visible.length ? (
                                <div className="courses-grid listing-grid">
                                    {visible.map((c, i) => (
                                        <CourseCard key={c.id} course={c} order={i} />
                                    ))}
                                </div>
                            ) : (
                                <p role="status">
                                    {courses.length
                                        ? 'No courses match these filters.'
                                        : 'No courses are assigned to your account.'}
                                </p>
                            )}
                        </>
                    );
                }}
            </AcademicRows>
        </>
    );
}

export function CourseDetail({ id }: { id: string }) {
    return (
        <>
            <a className="back-link" href="#/courses">
                <ArrowLeft size={16} />
                All courses
            </a>
            <AcademicRows<Course> resource="portal_course_catalog">
                {(courses) => {
                    const course = courses.find((c) => c.id === id);
                    if (!course)
                        return (
                            <Panel>
                                <h1>Course unavailable</h1>
                            </Panel>
                        );
                    return (
                        <>
                            <div className="detail-hero">
                                <div>
                                    <span className="eyebrow">{course.code}</span>
                                    <h1>{course.title}</h1>
                                    <p>{courseMeta(course)}</p>
                                </div>
                                <CourseArt course={course} />
                            </div>
                            <Panel>
                                <SectionHeading title="Lecture record" />
                                <AcademicRows<Lecture> resource="portal_course_lectures" target={id}>
                                    {(lectures) =>
                                        lectures.length ? (
                                            <div className="result-table-wrap">
                                                <table className="result-table">
                                                    <thead>
                                                        <tr>
                                                            <th>Lecture</th>
                                                            <th>Date</th>
                                                            <th>Status</th>
                                                        </tr>
                                                    </thead>
                                                    <tbody>
                                                        {lectures.map((l) => (
                                                            <tr key={l.id}>
                                                                <td>
                                                                    {l.number}. {l.title}
                                                                </td>
                                                                <td>{l.day}</td>
                                                                <td>
                                                                    {l.status
                                                                        .toLowerCase()
                                                                        .replaceAll('_', ' ')}
                                                                </td>
                                                            </tr>
                                                        ))}
                                                    </tbody>
                                                </table>
                                            </div>
                                        ) : (
                                            <p>No completed lectures are available.</p>
                                        )
                                    }
                                </AcademicRows>
                            </Panel>
                        </>
                    );
                }}
            </AcademicRows>
        </>
    );
}

export function Grades() {
    return (
        <>
            <PageIntro
                eyebrow="ACADEMIC RECORD"
                title="Grades"
                description="Final results published by your professor."
            />
            <PublishedGrades />
        </>
    );
}

export function Attendance() {
    const [page, setPage] = useState(0);
    return (
        <>
            <PageIntro
                eyebrow="ACADEMIC RECORD"
                title="Attendance"
                description="Attendance recorded for completed lectures."
            />
            <AcademicRows<Mark> resource="portal_attendance_register">
                {(marks) => {
                    const current = Math.min(page, Math.max(0, Math.ceil(marks.length / 40) - 1));
                    return (
                        <Panel>
                            <SectionHeading title="Lecture attendance" />
                            {marks.length ? (
                                <>
                                    <div className="result-table-wrap">
                                        <table className="result-table">
                                            <thead>
                                                <tr>
                                                    <th>Date</th>
                                                    <th>Course</th>
                                                    <th>Lecture</th>
                                                    <th>Student</th>
                                                    <th>Status</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {marks.slice(current * 40, (current + 1) * 40).map((m) => (
                                                    <tr key={m.enrollment_id + ':' + m.lecture_id}>
                                                        <td>{m.day}</td>
                                                        <td>{m.course}</td>
                                                        <td>{m.lecture}</td>
                                                        <td>{m.student}</td>
                                                        <td>{m.status.toLowerCase()}</td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>
                                    {marks.length > 40 && (
                                        <nav aria-label="Attendance pages">
                                            <button
                                                className="button-secondary"
                                                disabled={!current}
                                                onClick={() => setPage(current - 1)}
                                            >
                                                Previous
                                            </button>{' '}
                                            Page {current + 1} of {Math.ceil(marks.length / 40)}{' '}
                                            <button
                                                className="button-secondary"
                                                disabled={(current + 1) * 40 >= marks.length}
                                                onClick={() => setPage(current + 1)}
                                            >
                                                Next
                                            </button>
                                        </nav>
                                    )}
                                </>
                            ) : (
                                <p>No attendance has been recorded for your account.</p>
                            )}
                        </Panel>
                    );
                }}
            </AcademicRows>
        </>
    );
}
