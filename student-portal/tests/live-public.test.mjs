// Explicitly opt in: node --env-file=.env.local --test tests/live-public.test.mjs
// Requires a project with registration already disabled; never creates test accounts.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
const url = process.env.VITE_SUPABASE_URL;
const key = process.env.VITE_SUPABASE_PUBLISHABLE_KEY;
const headers = { apikey: key, 'Content-Type': 'application/json' };
test('live server disables signup and rejects a direct signup bypass', { skip: !url || !key }, async () => {
    const settings = await fetch(`${url}/auth/v1/settings`, { headers });
    assert.equal(settings.status, 200);
    assert.equal((await settings.json()).disable_signup, true, 'Do not submit signup unless already disabled');
    const response = await fetch(`${url}/auth/v1/signup`, { method: 'POST', headers,
        body: JSON.stringify({ email: `portal-test-${randomUUID()}@example.invalid`, password: randomUUID() + 'aA1!' }) });
    assert.equal(response.status, 422);
    assert.equal((await response.json()).error_code, 'signup_disabled');
});
test('live anonymous REST cannot read records or call authorization RPC', { skip: !url || !key }, async () => {
    for (const path of ['/rest/v1/portal_records?select=*', '/rest/v1/rpc/portal_access']) {
        const response = await fetch(url + path, { method: path.includes('/rpc/') ? 'POST' : 'GET', headers });
        assert.ok([401, 403].includes(response.status), `${path}: ${response.status}`);
    }
});
test('live Auth rejects fabricated access tokens', { skip: !url || !key }, async () => {
    const response = await fetch(`${url}/auth/v1/user`, { headers: { ...headers, Authorization: 'Bearer fabricated.access.token' } });
    assert.ok([401, 403].includes(response.status));
});
