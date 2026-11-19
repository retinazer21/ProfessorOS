import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkAccess } from '../src/auth/access.ts';
import type { SupabaseClient } from '@supabase/supabase-js';

function fixture({ session = true, user = true, aal = 'aal2', role = 'student', error = null as null | Error } = {}) {
    const calls: string[] = [];
    const client = {
        auth: {
            getSession: async () => ({ data: { session: session ? {} : null }, error: null }),
            getUser: async () => { calls.push('user'); return { data: { user: user ? { email: 'student@example.edu', user_metadata: { role: 'professor' } } : null }, error }; },
            mfa: { getAuthenticatorAssuranceLevel: async () => { calls.push('aal'); return { data: { currentLevel: aal }, error: null }; } },
        },
        rpc: async () => { calls.push('database'); return { data: role, error: null }; },
    } as unknown as SupabaseClient;
    return { client, calls };
}
test('anonymous sessions cannot reach the database', async () => {
    const { client, calls } = fixture({ session: false });
    assert.deepEqual(await checkAccess(client), { stage: 'login' }); assert.deepEqual(calls, []);
});
test('locally forged session without a validated user cannot pass', async () => {
    const { client, calls } = fixture({ user: false });
    assert.equal((await checkAccess(client)).stage, 'login'); assert.deepEqual(calls, ['user']);
});
test('password-only session never queries academic authorization', async () => {
    const { client, calls } = fixture({ aal: 'aal1' });
    assert.equal((await checkAccess(client)).stage, 'mfa'); assert.ok(!calls.includes('database'));
});
test('unexpected assurance level fails closed', async () => {
    assert.equal((await checkAccess(fixture({ aal: 'unknown' }).client)).stage, 'mfa');
});
test('editable user metadata cannot grant professor access', async () => {
    const result = await checkAccess(fixture().client); assert.equal(result.role, 'student');
});
test('missing or unknown database role is denied', async () => {
    for (const role of ['', 'admin']) assert.equal((await checkAccess(fixture({ role }).client)).stage, 'denied');
});
test('server-assigned professor role is accepted after MFA', async () => {
    assert.equal((await checkAccess(fixture({ role: 'professor' }).client)).role, 'professor');
});
test('network/user validation errors cannot allow access', async () => {
    await assert.rejects(checkAccess(fixture({ error: new Error('offline') }).client), /offline/);
});
