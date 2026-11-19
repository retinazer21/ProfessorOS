# Supabase Auth and authorization

The Student Portal uses the official `@supabase/supabase-js` SDK (pinned at 2.119.0) for password authentication, persisted/refreshing sessions, TOTP enrollment and verification, and global sign-out. The existing Studio Window login/MFA layout is retained. Desktop Compose/Java/SQLite are unchanged.

## Configuration

Use Node 24. Copy `.env.example` to `.env.local` and supply the project publishable key. Never put a secret/service-role key in a `VITE_` variable. Missing configuration disables sign-in. The current machine has `.env.local` configured for `bsvrqsqftmaoehlappbv`; this file is ignored by Git.

Run `npm ci`, `npm run dev`. For validation: `npm test`, `npm run typecheck`, `npm run build`. Explicit live negative tests: `node --env-file=.env.local --test tests/live-public.test.mjs`.

## Live state inspected and changed on 2026-11-13

Before changes, the project contained no application tables and zero Auth users. TOTP was enabled. Public signup was enabled and anonymous sign-in was disabled. Public signup is now disabled (verified after reload and by a direct `/auth/v1/signup` request). TOTP remains enabled.

Applied migration `202611130001_portal_access.sql` adds four empty tables with RLS: `portal_roles`, `portal_offerings`, `portal_enrollments`, and `portal_records`. It does not modify existing managed Supabase tables or desktop data. The migration is recorded in `supabase_migrations.schema_migrations`, with SHA-256 in its name. The stored `statements` copy was restored under an explicit user-approved exception; its integrity check now passes (see the repair record below). SHA-256: `ef617d25c8837e4d8747432e2d9caf0dde80b00e005d339535bd23b8008d4a11`. Never edit this applied migration; add a new version for changes. The history schema was created because none existed, its access revoked from browser roles, and RLS enabled on its history table. Future deployment must compare existing stored history to local migration contents before applying new versions.

## Access model

- Every route is gated by a server-validated Auth user, AAL2, and a role returned by the database. URL fragments and editable `user_metadata` cannot grant access.
- Roles are assigned only by a trusted administrator using SQL or a server-side service-role connection. Browser users cannot insert/update/delete roles, offerings or enrollment assignments.
- Students can read their own enrolled records. Professors can read/write records only for offerings assigned to them. An enrollment foreign key prevents records for non-enrolled students.
- RLS checks AAL2 and the JWT session ID against `auth.sessions`, including its owner and `not_after`. Revoked sessions therefore lose portal database access even while their access JWT has not expired. These guarantees apply to the new portal tables; any future tables, views, storage or functions need their own reviewed policies.
- Global logout calls managed Auth revocation and clears the SDK session on success. The UI hides the workspace immediately. If revocation fails, it remains locked and requests retry; it never reports successful logout offline. Other tabs receive SDK auth events. Focus and periodic checks revalidate access.
- TOTP setup uses Supabase enrollment/challenge/verify. QR material stays in component memory and disappears on completion or leaving the flow. Unfinished factors created by this portal can be replaced; verified factors are never removed by setup.

## Provisioning prerequisites

Three fictional acceptance-test Auth users now exist with confirmed emails and two student roles plus one professor role. Their non-secret IDs and isolated test records are recorded in `tests/e2e-fixture.json`. A university administrator must verify real identities, provision password-capable accounts through Supabase's trusted admin facilities, and insert each user UUID into `portal_roles` with `student` or `professor`. Assign offering owners and enrollments with the correct roles. Do not derive privileges from an email suffix or user-editable metadata. Never use the frontend for provisioning. Account creation and password delivery/recovery need an approved university process. Invite-link/password-reset callback screens and lost-authenticator recovery are not implemented in this task; the portal currently accepts existing email/password accounts. Do not direct invite/recovery links to these hash routes.

The first password sign-in requires TOTP setup if there is no verified factor. Only successful verification plus an administrator-assigned role opens the workspace. A professor uses the same authenticated shell; no new professor management UI was introduced.

Academic pages still display explicitly labeled fictional data from `src/demo.ts`. The new database tables contain only the isolated acceptance fixtures: three roles, one offering, two enrollments and two records. They are a protected publishing boundary, not a desktop synchronization implementation. A real desktop-to-portal mapping, publishing workflow and UI data adapter remain prerequisites before showing real academic records. Do not import the demo as production data.

## Verification and limits

Passed: TypeScript, Vite production build, eight access-state unit tests, three live HTTP negative tests. Live database regression suite (`supabase/tests/authorization.sql`) passed anonymous denial, AAL1 denial for both roles, student isolation, metadata/role escalation, student write denial, professor ownership and cross-offering update/insert denial, unassigned role denial, session-user mismatch, revoked session replay and session expiry. Fixtures were enclosed in a transaction and rolled back; follow-up queries confirmed zero Auth users and zero records, and four RLS-enabled portal tables.

Browser checks: unauthenticated direct `#/grades` and `#/mfa` render login, with the existing layout retained. Invalid credentials produce a generic actionable error. Both students and the professor passed real password/TOTP enrollment, pre-MFA record denial, assigned-role authorization, managed refresh, and signed-out access/refresh token replay checks. Students could read only their own records and could not change grades or promote themselves. The professor read both assigned records and updated a fixture grade, then restored it. Student A also passed reload persistence and cross-tab logout/Back checks. Professor repeat login reused its existing factor: an incorrect code showed an error and left records blocked; a fresh valid code restored AAL2 and workspace access. Final logout left zero test sessions. The 76 saved runner entries all pass (72 assertions and four capture setup entries); no manual authentication step remains. Mobile MFA layout was not covered in this acceptance run. See `tests/results/AUTH_ACCEPTANCE_REPORT.md` and `tests/E2E_RUNBOOK.md`.

Maven and Compose verification were attempted, but JDK 25/JAVA_HOME is missing. Graphify update was attempted, but the command is not installed and no graph exists in this checkout. The production frontend builds with a non-fatal chunk-size warning. No desktop code was changed.

Official guidance: https://supabase.com/docs/guides/auth/auth-mfa and https://supabase.com/docs/guides/auth/general-configuration

## Resolved migration history metadata discrepancy

Final checksum verification detected that the deployment wrapper used a JavaScript replacement string containing the original SQL. JavaScript converted the six `$$` delimiters into `$` in the history copy only, and Windows clipboard transfer converted LF to CRLF. The actual executable migration portion was unaffected. An exact comparison proved that these are the only differences. The immutable local migration source SHA-256 remains `ef617d25c8837e4d8747432e2d9caf0dde80b00e005d339535bd23b8008d4a11`, matching the recorded checksum in the history name. The stored statement SHA-256 is `0ccdc2802d28c7d67e69b503d3b8ab134e8edcc0780197d70f3596f6daf7bdbb`. The approved repair below restored the exact source text, and checksum verification now passes. Future deployments must continue to verify history integrity.

A verified pre-repair metadata backup is in `supabase/audit/20261113-history-before-repair.json`, SHA-256 `3d22418d9d1853ab53d5515f291f29c431220e0dd9b1fd20ad4f4ec0c3413167`. The backup was read back and compared byte-for-byte. The prior empty-user/data state and passing live RLS tests established the application schema state.

`supabase/audit/PROPOSED_202611130002_repair_history_copy.sql` is an unapplied proposal, deliberately outside the migrations directory. It preserves the original entry in an audit table and only restores the evidence copy after checking both observed and restored hashes; it never changes the existing migration source or recorded checksum. Automatic approval review rejected execution because updating applied migration history conflicts with AGENTS.md. That initial proposal was not executed. The user subsequently approved a narrower statement-only repair, excluding any schema change or new migration entry.

### Approved repair completed

The guarded transaction in `supabase/audit/20261113-approved-history-text-repair.sql` restored only `schema_migrations.statements[1]` for version `202611130001`. It did not create the previously proposed audit table or register a new migration. Verified the backup before repair, matched the full original row, checked every postcondition before committing, and independently verified integrity after commit. Applied migration source, recorded checksum, schema and portal data are unchanged; the history contains the same single version. See [exact changes and before/after evidence](supabase/audit/20261113-repair-report.md). History integrity, fictional account provisioning and interactive authentication acceptance testing are complete. Source/history/checksum equality was reverified during the acceptance run.
