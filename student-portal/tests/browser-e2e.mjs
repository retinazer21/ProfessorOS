import { supabase } from '../src/auth/client.ts';

if (!import.meta.env.DEV || !['127.0.0.1','localhost'].includes(location.hostname)) throw new Error('Local development tests only');
if (!supabase) throw new Error('Configure the project publishable key');
const report = document.querySelector('#report');
const status = document.querySelector('#status');
const results = JSON.parse(sessionStorage.getItem('professor-os-e2e-results') || '[]');
let captured = null;
function record(test, pass, detail = '') {
    results.push({ test, result: pass ? 'PASS' : 'FAIL', detail, at: new Date().toISOString() });
    sessionStorage.setItem('professor-os-e2e-results', JSON.stringify(results));
    report.textContent = JSON.stringify(results, null, 2);
    if (!pass) throw new Error(test);
}
async function session() {
    const { data, error } = await supabase.auth.getSession();
    if (error) throw error;
    if (!data.session) throw new Error('Sign in using the portal first');
    if (!/^professor-os-e2e-(student-[ab]|professor)@example\.invalid$/.test(data.session.user.email ?? '')) throw new Error('This runner accepts only the three fictional test accounts');
    return data.session;
}
async function fixture() {
    const response = await fetch('./e2e-fixture.json', { cache: 'no-store' });
    if (!response.ok) throw new Error('Fixture provisioning is pending');
    const data = await response.json();
    if (data.project !== 'bsvrqsqftmaoehlappbv' || data.run !== 'professor-os-e2e-20261113') throw new Error('Unexpected fixture');
    return data;
}
async function identity() {
    const s = await session();
    const { data, error } = await supabase.auth.getUser();
    if (error || data.user?.id !== s.user.id) throw new Error('Server user validation failed');
    const { data: aal, error: ae } = await supabase.auth.mfa.getAuthenticatorAssuranceLevel();
    if (ae) throw ae;
    const { data: role, error: re } = await supabase.rpc('portal_access');
    if (re) throw re;
    status.textContent = `${s.user.email} | ${aal.currentLevel} | role: ${role ?? 'not available before MFA'}`;
    return { s, aal: aal.currentLevel, role };
}
async function raw(path, token, method = 'GET', body) {
    const response = await fetch(import.meta.env.VITE_SUPABASE_URL + path, {
        method, headers: { apikey: import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY, ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': 'application/json' },
        ...(body ? { body: JSON.stringify(body) } : {}),
    });
    return { status: response.status, body: await response.json() };
}
const readsDenied = r => [401,403].includes(r.status) || (r.status === 200 && Array.isArray(r.body) && r.body.length === 0);
const actions = {
    unpublished: async () => {
        const { aal } = await identity();
        record('Real user has MFA before publication checks', aal === 'aal2');
        const result = await supabase.from('portal_published_grades').select('offering_id,student_id');
        record('No unpublished grade is visible', !result.error && result.data?.length === 0);
    },
    publication: async () => {
        const { s, aal, role } = await identity(); const f = await fixture();
        record('Published grade read requires verified student session', aal === 'aal2' && role === 'student', s.user.email);
        const result = await supabase.from('portal_published_grades')
            .select('offering_id,student_id,score,letter,published_at,portal_offerings(title)');
        record('Published grade query and course-title join succeed', !result.error);
        const rows = result.data ?? [];
        const targetStudent = f.accounts.find(a => a.email === 'professor-os-e2e-student-a@example.invalid');
        record('Published rows belong only to signed-in student', rows.every(r => r.student_id === s.user.id));
        record('Signed-in student sees expected publication count', rows.length === (s.user.id === targetStudent.id ? 1 : 0), `${s.user.email}: ${rows.length}`);
        if (rows.length) {
            const expected = Number(document.getElementById('expected-publication-score').value);
            record('Published score matches desktop snapshot', rows.every(r => Number(r.score) === expected));
            record('Published course and server timestamp are present', rows.every(r => r.portal_offerings?.title && !Number.isNaN(Date.parse(r.published_at))));
        }
        const other = f.accounts.find(a => a.role === 'student' && a.id !== s.user.id);
        const response = await raw(`/rest/v1/portal_published_grades?student_id=eq.${other.id}&select=*`, s.access_token);
        record('Real student session cannot request another student publications', readsDenied(response));
        const write = await supabase.from('portal_published_grades').upsert({offering_id:f.offering,student_id:s.user.id,score:100,letter:'A'}).select();
        record('Real student session cannot publish or overwrite a grade', !!write.error && write.error.code === '42501');
    },
    inspect: async () => { const v = await identity(); record('Managed session validated by Auth', true, `${v.s.user.email}: ${v.aal}`); },
    pre: async () => {
        const { s, aal, role } = await identity();
        record('Password sign-in remains AAL1', aal === 'aal1', s.user.email);
        record('Role unavailable before MFA', role === null);
        for (const table of ['portal_records','portal_roles','portal_offerings','portal_enrollments','portal_published_grades']) {
            const response = await raw(`/rest/v1/${table}?select=*`, s.access_token);
            record(`AAL1 cannot read ${table}`, readsDenied(response));
        }
        const f = await fixture();
        const target = f.records[0];
        const response = await raw(`/rest/v1/portal_records?id=eq.${target.id}&select=*`, s.access_token);
        record('Direct record-ID request cannot bypass MFA', readsDenied(response));
    },
    verified: async () => {
        const { s, aal, role } = await identity(); const f = await fixture();
        const account = f.accounts.find(a => a.id === s.user.id);
        if (!account) throw new Error('User is not in the fixture manifest');
        record('TOTP produces AAL2 session', aal === 'aal2', s.user.email);
        record('Database returns assigned RBAC role', role === account.role);
        const { data: rows, error } = await supabase.from('portal_records').select('*');
        record('Authenticated academic read succeeds', !error);
        const expected = f.records.filter(r => role === 'professor' || r.student_id === s.user.id);
        record('Only permitted fictional records are visible', JSON.stringify(rows.map(r=>r.id).sort()) === JSON.stringify(expected.map(r=>r.id).sort()));
        if (role === 'student') {
            const other = f.records.find(r => r.student_id !== s.user.id);
            const response = await raw(`/rest/v1/portal_records?id=eq.${other.id}&select=*`, s.access_token);
            record('Other student direct-ID read denied', readsDenied(response));
            const roleWrite = await supabase.from('portal_roles').update({role:'professor'}).eq('user_id',s.user.id).select();
            record('Student cannot promote own role', !!roleWrite.error && roleWrite.error.code === '42501');
            const edit = await supabase.from('portal_records').update({value:'E2E-UNAUTHORIZED'}).eq('id',expected[0].id).select();
            record('Student cannot change grades', !edit.data?.length && (!edit.error || edit.error.code === '42501'));
        } else {
            const target = expected[0];
            const edit = await supabase.from('portal_records').update({value:'E2E-verified-edit'}).eq('id',target.id).select();
            // Restore only this fixture record before asserting the mutation test.
            const restore = await supabase.from('portal_records').update({value:target.value}).eq('id',target.id).select();
            record('Professor can update assigned offering record', !edit.error && edit.data?.length === 1);
            record('Fictional record restored after write test', !restore.error && restore.data?.[0]?.value === target.value);
            const access = await supabase.rpc('portal_teaches',{offering:'00000000-0000-4000-8000-000000000000'});
            record('Professor cannot claim an unassigned offering', !access.error && access.data === false);
        }
    },
    refresh: async () => {
        const before = await identity();
        const refreshed = await supabase.auth.refreshSession();
        const after = await identity();
        record('Managed refresh retains identity and AAL', !refreshed.error && before.s.user.id === after.s.user.id && before.aal === after.aal);
        record('Managed refresh retains database authorization', before.role === after.role);
    },
    capture: async () => { captured = await session(); record('Session captured in memory for logout check', true, captured.user.email); },
    replay: async () => {
        if (!captured) throw new Error('Capture a session before signing out');
        const current = await supabase.auth.getSession();
        record('Portal logout clears the session shared by these browser tabs', !current.error && current.data.session === null);
        const response = await raw('/rest/v1/portal_records?select=*', captured.access_token);
        record('Signed-out access token cannot read records', readsDenied(response));
        const publications = await raw('/rest/v1/portal_published_grades?select=*', captured.access_token);
        record('Signed-out access token cannot read published grades', readsDenied(publications));
        const role = await raw('/rest/v1/rpc/portal_access', captured.access_token,'POST',{});
        record('Signed-out access token cannot authorize role', [401,403].includes(role.status) || (role.status===200 && role.body===null));
        const refresh = await raw('/auth/v1/token?grant_type=refresh_token',null,'POST',{refresh_token:captured.refresh_token});
        record('Signed-out refresh token is rejected', [400,401,403].includes(refresh.status));
        captured = null;
    },
};
for (const [id, action] of Object.entries(actions)) document.getElementById(id).onclick = async () => {
    for (const button of document.querySelectorAll('button')) button.disabled = true;
    try { await action(); } catch (e) { status.textContent = `Stopped: ${e.message}. Inspect the report before continuing.`; }
    finally { for (const button of document.querySelectorAll('button')) button.disabled = false; }
};
supabase.auth.onAuthStateChange(event => { status.textContent = `Auth event: ${event}. Click Inspect session to validate.`; });
report.textContent = JSON.stringify(results,null,2);
