import { useEffect, useState } from 'react';
import { supabase } from './auth/client';
import { Panel, SectionHeading } from './ui';
import { formatRecordDate } from './RecordViews';

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
        {rows.length > 0 && <ul className="record-list" aria-label="Published grades">{rows.map(row =>
            <li key={`${row.offering_id}:${row.student_id}`}>
                <div><strong>{row.portal_offerings?.title ?? 'Course'}</strong><small>Published {formatRecordDate(row.published_at)}</small></div>
                <div className="record-score"><strong>{row.score}%</strong><span className="record-status" aria-label={`Grade ${row.letter}`}>{row.letter}</span></div>
            </li>
        )}</ul>}
        <button className="button-secondary" disabled={state === 'loading'} onClick={() => setRevision(n => n + 1)}>Refresh published grades</button>
        <p>Only explicitly published snapshots appear here. Unsaved and unpublished desktop changes are private.</p>
    </Panel>;
}
