import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { preview } from 'vite';

const base = '/Uni-Project/';
const artifactPaths = [];
const html = await readFile('dist/index.html', 'utf8');
const refs = [...html.matchAll(/(?:src|href)="([^"]+)"/g)].map(m => m[1]);
assert(refs.length >= 3, 'Expected built script, stylesheet and favicon');
for (const ref of refs) assert(ref.startsWith(base), `Asset outside Pages base: ${ref}`);
async function inspect(dir) {
    for (const entry of await readdir(dir, { withFileTypes: true })) {
        const path = join(dir, entry.name);
        assert(!entry.isSymbolicLink(), 'No links in Pages artifact');
        assert(!/(^\.env|credentials|secrets|backup|\.map$|\.(db|sqlite3?|bak|sql|md|ts|tsx)$)/i.test(entry.name), `Unexpected artifact file: ${path}`);
        if (entry.isDirectory()) await inspect(path);
        else {
            artifactPaths.push(base + path.replaceAll('\\', '/').replace(/^dist\//, ''));
            if (!/\.(html|js|css|json)$/.test(path)) continue;
            const text = await readFile(path, 'utf8');
            // The SDK includes the literal prefix in validation messages; require key material after it.
            assert(!/sb_secret_[A-Za-z0-9_-]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|"(?:access_token|refresh_token)"\s*:\s*"[^"\s]+"/.test(text), `Credential material in ${path}`);
            for (const token of text.matchAll(/eyJ[A-Za-z0-9_-]+\.([A-Za-z0-9_-]+)\.[A-Za-z0-9_-]+/g)) {
                const payload = JSON.parse(Buffer.from(token[1], 'base64url').toString('utf8'));
                assert.notEqual(payload.role, 'service_role', 'Legacy service-role JWT in artifact');
            }
        }
    }
}
await inspect('dist');
const server = await preview({ preview: { host: '127.0.0.1', port: 0, open: false } });
try {
    const address = server.httpServer.address();
    const origin = `http://127.0.0.1:${address.port}`;
    for (const path of [base, ...new Set([...refs, ...artifactPaths]), `${base}#/login`, `${base}#/mfa`, `${base}#/grades`]) {
        const response = await fetch(origin + path);
        assert.equal(response.status, 200, `Not served: ${path}`);
        if (path.includes('#')) assert.equal(await response.text(), html, 'Hash routes must load the same entry');
    }
    assert.match(await readFile('src/auth/client.ts', 'utf8'), /detectSessionInUrl:\s*false/);
    assert.match(await readFile('src/auth/AuthProvider.tsx', 'utf8'), /window\.location\.hash\s*=\s*['"]\/login['"]/);
    console.log('PASS: Pages base, asset responses, hash-route entry, artifact hygiene, and hash logout destination.');
} finally {
    await new Promise((resolve, reject) => server.httpServer.close(error => error ? reject(error) : resolve()));
}
