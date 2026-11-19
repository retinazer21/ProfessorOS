import { useEffect, useState, type FormEvent } from 'react';
import { ArrowRight } from 'lucide-react';
import { supabase } from './client';
import { useAuth } from './AuthProvider';

export function AuthForm({ mfa }: { mfa: boolean }) {
    const { refresh, logout } = useAuth();
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    const [factors, setFactors] = useState<{ id: string; friendly_name?: string }[]>([]);
    const [factorId, setFactorId] = useState('');
    const [qr, setQr] = useState('');
    useEffect(() => {
        if (!mfa || !supabase) return;
        let active = true;
        void supabase.auth.mfa.listFactors().then(({ data, error }) => {
            if (!active) return;
            if (error) { console.error('Factor lookup failed', error.code); setError('Unable to load authenticators. Retry after checking your connection.'); return; }
            setFactors(data.totp); setFactorId(data.totp[0]?.id ?? '');
        }).catch((e) => { console.error('Factor lookup failed', e); if (active) setError('Unable to load authenticators.'); });
        return () => { active = false; };
    }, [mfa]);
    async function enroll() {
        if (!supabase || busy) return;
        setBusy(true); setError('');
        try {
            // Only clean up unfinished portal enrollments; never remove a verified factor.
            const { data: existing, error: listError } = await supabase.auth.mfa.listFactors();
            if (listError) throw listError;
            for (const factor of existing.all.filter(f => f.factor_type === 'totp' && f.status === 'unverified' && f.friendly_name === 'Professor OS')) {
                const { error } = await supabase.auth.mfa.unenroll({ factorId: factor.id });
                if (error) throw error;
            }
            const { data, error } = await supabase.auth.mfa.enroll({ factorType: 'totp', friendlyName: 'Professor OS', issuer: 'Professor OS' });
            if (error) throw error;
            setFactorId(data.id); setQr(data.totp.qr_code);
        } catch (e) { console.error('MFA enrollment failed', e); setError('Unable to set up an authenticator. Retry or contact your administrator.'); }
        finally { setBusy(false); }
    }
    async function submit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (!supabase || busy) return;
        const form = new FormData(event.currentTarget);
        setBusy(true); setError('');
        try {
            if (mfa) {
                const code = String(form.get('code') ?? '').trim();
                if (!/^\d{6}$/.test(code) || !factorId) { setError('Enter the six-digit code from your authenticator.'); return; }
                const { error } = await supabase.auth.mfa.challengeAndVerify({ factorId, code });
                if (error) throw error;
                setQr('');
            } else {
                const { error } = await supabase.auth.signInWithPassword({ email: String(form.get('email')).trim(), password: String(form.get('password')) });
                if (error) throw error;
            }
            await refresh();
        } catch (e) {
            console.error('Authentication request failed', e instanceof Error ? e.name : 'Request failed');
            setError(mfa ? 'Verification failed. Use a fresh code and check your device time.' : 'Sign in failed. Check your credentials and connection, or contact your administrator.');
        } finally { setBusy(false); }
    }
    return <form className="auth-card" onSubmit={submit} aria-busy={busy}>
        {mfa ? <>
            {factors.length > 1 && <><label htmlFor="authenticator">Authenticator</label><select id="authenticator" value={factorId} onChange={e => setFactorId(e.target.value)}>{factors.map(f => <option key={f.id} value={f.id}>{f.friendly_name ?? 'Authenticator'}</option>)}</select></>}
            {!factorId && <button type="button" className="button-secondary" disabled={busy} onClick={() => void enroll()}>Set up authenticator</button>}
            {qr && <><p>Scan this QR code with your authenticator, then enter its six-digit code. Keep the QR code private.</p><img className="mfa-qr" src={qr.startsWith('data:') ? qr : `data:image/svg+xml;charset=utf-8,${encodeURIComponent(qr)}`} alt="Authenticator setup QR code" /></>}
            <label htmlFor="mfa-code">Verification code</label>
            <input id="mfa-code" name="code" inputMode="numeric" pattern="[0-9]{6}" maxLength={6} autoComplete="one-time-code" required placeholder="000000" disabled={!factorId || busy} />
        </> : <>
            <label htmlFor="email">University email</label><input id="email" name="email" type="email" autoComplete="username" required disabled={busy} />
            <label htmlFor="password">Password</label><input id="password" name="password" type="password" autoComplete="current-password" required disabled={busy} />
            <p>Use the account issued by your university. Public registration is unavailable.</p>
        </>}
        {error && <p role="alert">{error}</p>}
        <button type="submit" className="button-primary auth-button" disabled={busy || !supabase || (mfa && !factorId)}>{busy ? 'Please wait…' : mfa ? 'Verify and continue' : 'Sign in'} <ArrowRight size={17} /></button>
        {mfa && <button type="button" className="auth-back" disabled={busy} onClick={() => void logout()}>Sign out</button>}
    </form>;
}
