import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { supabase } from './client';
import { checkAccess, type Access } from './access';

const Context = createContext<null | { access: Access; error: string; refresh: () => Promise<void>; logout: () => Promise<void> }>(null);
export function AuthProvider({ children }: { children: ReactNode }) {
    const [access, setAccess] = useState<Access>({ stage: 'loading' });
    const [error, setError] = useState('');
    const generation = useRef(0);
    const loggingOut = useRef(false);
    const refresh = useCallback(async () => {
        if (loggingOut.current) return;
        const current = ++generation.current;
        setAccess(previous => previous.stage === 'mfa' ? previous : { stage: 'loading' });
        if (!supabase) { setError('Portal connection is not configured. Contact your administrator.'); setAccess({ stage: 'login' }); return; }
        try {
            const next = await checkAccess(supabase);
            if (current !== generation.current || loggingOut.current) return;
            setError(''); setAccess(next);
        } catch (e) {
            console.error('Portal access check failed', e instanceof Error ? e.message : 'Request failed');
            if (current !== generation.current) return;
            setAccess({ stage: 'denied' });
            setError('Unable to verify access. Check your connection and retry, or contact your administrator.');
        }
    }, []);
    const logout = useCallback(async () => {
        if (!supabase) return;
        loggingOut.current = true; ++generation.current;
        setAccess({ stage: 'loading' }); setError('');
        try {
            const { error } = await supabase.auth.signOut({ scope: 'local' });
            if (error) throw error;
            loggingOut.current = false;
            setAccess({ stage: 'login' }); window.location.hash = '/login';
        } catch (e) {
            console.error('Sign out failed', e instanceof Error ? e.message : 'Request failed');
            setAccess({ stage: 'denied' });
            setError('Sign out could not be confirmed. Reconnect and retry sign out.');
        }
    }, []);
    useEffect(() => {
        if (!supabase) { void refresh(); return; }
        // Defer SDK calls outside onAuthStateChange's internal auth lock.
        const { data } = supabase.auth.onAuthStateChange(() => {
            ++generation.current; setAccess({ stage: 'loading' });
            window.setTimeout(() => { if (!loggingOut.current) void refresh(); }, 0);
        });
        const onFocus = () => { if (!loggingOut.current) void refresh(); };
        window.addEventListener('focus', onFocus);
        const timer = window.setInterval(onFocus, 60_000);
        void refresh();
        return () => { ++generation.current; data.subscription.unsubscribe(); window.clearInterval(timer); window.removeEventListener('focus', onFocus); };
    }, [refresh]);
    return <Context.Provider value={{ access, error, refresh, logout }}>{children}</Context.Provider>;
}
export function useAuth() {
    const value = useContext(Context);
    if (!value) throw new Error('AuthProvider is required');
    return value;
}
