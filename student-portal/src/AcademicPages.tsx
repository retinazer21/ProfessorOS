import { useState } from 'react';
import { ArrowLeft, Search } from 'lucide-react';
import { AcademicRows, courseMeta, type Course, type Lecture, type Mark } from './academic';
import { PublishedGrades } from './PublishedGrades';
import { AttendanceRecords, formatRecordDate, RecordStatus } from './RecordViews';
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
                                            <ul className="record-list">
                                                {lectures.map((lecture) => (
                                                    <li key={lecture.id}>
                                                        <div><strong>{lecture.number}. {lecture.title}</strong><small>{formatRecordDate(lecture.day)}</small></div>
                                                        <RecordStatus status={lecture.status} />
                                                    </li>
                                                ))}
                                            </ul>
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
    return (
        <>
            <PageIntro eyebrow="ACADEMIC RECORD" title="Attendance" description="Review recorded marks by course and lecture." />
            <AcademicRows<Mark> resource="portal_attendance_register">
                {(marks) => <AttendanceRecords marks={marks} />}
            </AcademicRows>
        </>
    );
}
