import { ArrowRight } from 'lucide-react';
import { useAuth } from './auth/AuthProvider';
import { AcademicRows, courseMeta, type Course } from './academic';
import { CourseArtwork } from './CourseArtwork';
import { CourseCard } from './ui';
import './dashboard.css';

export function Dashboard() {
    const { access } = useAuth();
    return (
        <div className="studio-dashboard">
            <header className="studio-heading">
                <div>
                    <h1>Study dashboard</h1>
                    <p>{access.email}</p>
                </div>
            </header>
            <AcademicRows<Course> resource="portal_course_catalog">
                {(courses) => {
                    const featured = courses[0];
                    if (!featured) return <p>No courses are assigned to your account.</p>;
                    return (
                        <div className="studio-grid">
                            <section className="studio-feature">
                                <figure className="studio-feature-figure">
                                    <CourseArtwork course={featured} />
                                </figure>
                                <div className="studio-feature-copy">
                                    <p className="studio-code">{featured.code}</p>
                                    <h2>{featured.title}</h2>
                                    <p className="studio-feature-description">{courseMeta(featured)}</p>
                                    <a className="studio-action" href={'#/courses/' + featured.id}>
                                        Open course <ArrowRight size={17} />
                                    </a>
                                </div>
                            </section>
                            <section className="studio-agenda">
                                <h2>Academic record</h2>
                                <p>
                                    <a className="studio-text-link" href="#/grades">
                                        Published grades <ArrowRight size={16} />
                                    </a>
                                </p>
                                <p>
                                    <a className="studio-text-link" href="#/attendance">
                                        Lecture attendance <ArrowRight size={16} />
                                    </a>
                                </p>
                            </section>
                            <section className="studio-courses">
                                <div className="studio-section-heading">
                                    <h2>Your courses</h2>
                                    <a className="studio-text-link" href="#/courses">
                                        View all courses <ArrowRight size={16} />
                                    </a>
                                </div>
                                <div className="courses-grid listing-grid">
                                    {courses.map((course, i) => (
                                        <CourseCard key={course.id} course={course} order={i} />
                                    ))}
                                </div>
                            </section>
                        </div>
                    );
                }}
            </AcademicRows>
        </div>
    );
}
