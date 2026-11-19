// VITE values are public browser configuration, never administrative credentials.
const url = process.env.VITE_SUPABASE_URL;
const key = process.env.VITE_SUPABASE_PUBLISHABLE_KEY;
if (!url || !URL.canParse(url) || new URL(url).protocol !== 'https:' ||
    !key?.startsWith('sb_publishable_') || key.includes('replace_with')) {
    throw new Error('Set VITE_SUPABASE_URL (HTTPS) and VITE_SUPABASE_PUBLISHABLE_KEY (sb_publishable_) repository variables. Never supply an admin key.');
}
console.log('Public Pages authentication configuration is valid.');
