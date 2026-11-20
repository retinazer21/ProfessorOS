import { useEffect, useState, type ReactNode } from 'react';
import { supabase } from './auth/client';

export type Course = {
    id: string;
    title: string;
    code: string | null;
    credits: number | null;
    department: string | null;
    term: string | null;
    section: string | null;
};
export type Lecture = { id: string; number: number; title: string; day: string; status: string };
export type Mark = {
    enrollment_id: string;
    lecture_id: string;
    course: string;
    lecture: string;
    day: string;
    student: string;
    status: string;
};

type Resource = 'portal_course_catalog' | 'portal_course_lectures' | 'portal_attendance_register';
export function AcademicRows<T>({
    resource,
    target,
    children,
}: {
    resource: Resource;
    target?: string;
    children: (rows: T[]) => ReactNode;
}) {
    const [rows, setRows] = useState<T[] | null>(null);
    const [error, setError] = useState(false);
    const [attempt, retry] = useState(0);
    useEffect(() => {
        const controller = new AbortController();
        async function load() {
            setRows(null);
            setError(false);
            if (!supabase) throw Error('Portal unavailable');
            const result: T[] = [];
            // Use a unique final ordering key before fetching the next page.
            for (;;) {
                let q = supabase.rpc(resource, target ? { target } : {}).select('*');
                if (resource === 'portal_attendance_register')
                    q = q.order('day', { ascending: false }).order('enrollment_id').order('lecture_id');
                else if (resource === 'portal_course_lectures') q = q.order('number').order('id');
                else q = q.order('title').order('id');
                const { data, error } = await q
                    .range(result.length, result.length + 199)
                    .abortSignal(controller.signal);
                if (error || !data) throw Error('Academic query failed');
                result.push(...(data as T[]));
                if (data.length === 0) break;
            }
            if (!controller.signal.aborted) setRows(result);
        }
        void load().catch(() => {
            if (!controller.signal.aborted) setError(true);
        });
        return () => controller.abort();
    }, [resource, target, attempt]);
    if (error)
        return (
            <div className="state-card" role="alert">
                <p>Academic records could not be loaded.</p>
                <button className="button-secondary" onClick={() => retry((n) => n + 1)}>
                    Try again
                </button>
            </div>
        );
    return rows === null ? <p role="status">Loading academic records…</p> : children(rows);
}

export function courseMeta(course: Course) {
    return [course.term, course.section && 'Section ' + course.section, course.department]
        .filter(Boolean)
        .join(' · ');
}
