import type { SupabaseClient } from '@supabase/supabase-js';

export type Access = { stage: 'loading' | 'login' | 'mfa' | 'denied' | 'ready'; role?: 'student' | 'professor'; email?: string };

export async function checkAccess(client: SupabaseClient): Promise<Access> {
    const { data: session, error: sessionError } = await client.auth.getSession();
    if (sessionError) throw sessionError;
    if (!session.session) return { stage: 'login' };
    // Validate with Auth, never trust locally restored user metadata as authority.
    const { data: user, error: userError } = await client.auth.getUser();
    if (userError) throw userError;
    if (!user.user) return { stage: 'login' };
    const { data: aal, error: aalError } = await client.auth.mfa.getAuthenticatorAssuranceLevel();
    if (aalError) throw aalError;
    if (aal.currentLevel !== 'aal2') return { stage: 'mfa', email: user.user.email };
    // The database checks AAL, live session and administrator-assigned role.
    const { data: role, error } = await client.rpc('portal_access');
    if (error) throw error;
    return role === 'student' || role === 'professor'
        ? { stage: 'ready', role, email: user.user.email } : { stage: 'denied' };
}
