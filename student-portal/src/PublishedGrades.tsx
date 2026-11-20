import { useEffect, useState } from 'react';
import { supabase } from './auth/client';
import { Panel, SectionHeading } from './ui';

type PublishedGrade = { offering_id: string; student_id: string; score: number; letter: string; published_at: string; portal_offerings: { title: string } | null };
export function PublishedGrades() {
    const [rows, setRows] = useState<PublishedGrade[]>([]);
    const [state, setState] = useState('loading');
    const [revision, setRevision] = useState(0);
    useEffect(() => {
        let active = true;
        setRows([]); setState('loading');
        if (!supabase) { setState('error'); return; }
        void supabase.from('portal_published_grades')
            .select('offering_id,student_id,score,letter,published_at,portal_offerings(title)')
            .order('published_at', { ascending: false }).then(({ data, error }) => {
                if (!active) return;
                if (error) { console.error('Published grade read failed', error.code); setState('error'); return; }
                setRows(data as unknown as PublishedGrade[]); setState('ready');
            }, () => { if (active) setState('error'); });
        return () => { active = false; };
    }, [revision]);
    return <Panel className="result-panel">
        <SectionHeading label="PUBLISHED BY YOUR PROFESSOR" title="Published grades" />
        {state === 'loading' && <p role="status">Loading published grades…</p>}
        {state === 'error' && <p role="alert">Published grades could not be loaded. Check your connection or contact your university.</p>}
        {state === 'ready' && rows.length === 0 && <p>No grades have been published for your account.</p>}
        {rows.length > 0 && <div className="result-table-wrap"><table className="result-table">
            <thead><tr><th>Course</th><th>Score</th><th>Result</th><th>Published</th></tr></thead>
            <tbody>{rows.map(row => <tr key={`${row.offering_id}:${row.student_id}`}>
                <td>{row.portal_offerings?.title ?? 'Course'}<small> · {row.student_id}</small></td>
                <td>{row.score}%</td><td>{row.letter}</td><td>{new Date(row.published_at).toLocaleString()}</td>
            </tr>)}</tbody>
        </table></div>}
        <button className="button-secondary" disabled={state === 'loading'} onClick={() => setRevision(n => n + 1)}>Refresh published grades</button>
        <p>Only explicitly published snapshots appear here. Unsaved and unpublished desktop changes are private.</p>
    </Panel>;
}
