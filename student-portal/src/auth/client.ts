import { createClient } from '@supabase/supabase-js';

const url = import.meta.env.VITE_SUPABASE_URL;
const key = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY;
// Only a publishable key belongs in a browser bundle. Never use service_role.
export const supabase = url && URL.canParse(url) && new URL(url).protocol === 'https:' && key?.startsWith('sb_publishable_')
    ? createClient(url, key, { auth: { flowType: 'pkce', detectSessionInUrl: false,
        persistSession: true, autoRefreshToken: true } }) : null;
