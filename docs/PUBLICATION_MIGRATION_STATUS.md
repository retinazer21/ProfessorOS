# Published-grade migration outcome — 2026-11-20

## Exact execution outcome

Migration `202611200001` executed **once** in the authorized Supabase project `bsvrqsqftmaoehlappbv`. SQL Editor returned:

> Migration 202611200001 applied; prior data/history preserved; RLS enabled

The selected editor transaction was independently verified before execution against the user-approved CRLF SHA-256:

`5c2c188c7a30ff4a7666ef58af1255a5640fa97e95c58aa7517c2bd8087df1d5`

No retry, rollback repair, guard bypass or migration-history overwrite was performed. The guarded transaction only created the new publication objects and appended the new migration row. The immutable new source SHA-256 is:

`d1f858e086e26bb4d1b37e66fa8b207e59611c72c5e4713b099879b1c5589c4a`

## Preflight and backups

Immediately before execution, an independent read-only query confirmed the new table, timestamp function and migration version were absent, exactly one original history entry existed, all 11 original policies existed, and RLS was enabled on all four original tables. The transaction additionally guarded exact existing rows, policies, helper definitions and original migration history. Its pre-commit assertions checked old/new source hashes, the new restrictive RLS policies and an empty new table.

The application snapshot was SHA-256 verified, read back, copied separately and recovery-tested in isolated PostgreSQL/WASM. Both saved copies hash to:

`36ca37af017b973e3c690bab4e6ad33ba6d5aca38724c10e7ebd6204f4e987ea`

Private copies remain at `.tools/publication-backup/pre-migration.json` and `C:/Users/lenovo/Desktop/Professor-OS-preserved-20261119/pre-publication-20261120/pre-migration.json`. The snapshot covers the four existing application tables/data, original schema/history, 13 columns, 11 constraints, 11 policies and 3 authorization helpers. It is not a full managed Auth password/session backup. Neither backup is published or committed.

## Independent post-check

| Check | Outcome |
| --- | --- |
| Original role rows (3), offering (1), enrollments (2), records (2) | Exact match to backup |
| All 11 original policies | Exact match to backup |
| New table RLS | Enabled |
| New policies | `published_read`, `published_insert`, `published_update`, restrictive `published_mfa` present |
| Anonymous SELECT/INSERT | No grants |
| Authenticated SELECT/INSERT/UPDATE | Granted, subject to RLS |
| Authenticated DELETE | No grant |
| New publication rows | Zero |
| Migration history version count | Two |
| New history source hash | Matches immutable migration source |
| Original history/helper exact text from lossless export | Exact match to backup; server-side hashes match |

The initial failed comparison used the SQL Editor grid cell's rendered `innerText`, which collapses multiple spaces in displayed JSON strings. Live operations stopped, and the user subsequently authorized lossless verification and conditional rollback-only tests. A server-side JSON/Base64 export was decoded and independently checked against its server-side SHA-256. It confirmed **exact** equality of the original history row and all three authorization helper definitions against the verified pre-migration snapshot, without whitespace normalization. The server-side original statement hash remains `ef617d25c8837e4d8747432e2d9caf0dde80b00e005d339535bd23b8008d4a11`. The new history statement exactly matches its applied source and expected hash. Existing data and all 11 original policies also match the snapshot. No history, function, policy or existing data was modified to pass verification.

## Approved live rollback-only security tests — PASS

After every lossless comparison passed, the existing suite `student-portal/supabase/tests/publication.sql` ran against the live Supabase database using only newly inserted fictional fixtures inside one explicit `BEGIN … ROLLBACK` transaction. No migration was rerun or modified. The exact editor-executed test script, including its post-rollback read-only receipt, has SHA-256 `282f99c8341369f3a74a0bcda9c42416a5a281fc6e187d2f18d5b7a796b1843d`.

| Database-level security check | Result |
| --- | --- |
| Anonymous read | Denied |
| Professor publishing with password-only/AAL1 claims | Denied |
| MFA-verified unassigned professor publishing | Denied |
| MFA-verified assigned professor publishing and updating | Allowed |
| Repeat publication for the same student/course | One row, no duplicate |
| Other student's read | Denied |
| Enrolled student's own read | Exactly one fixture row |
| Student grade update | No rows changed |
| Student read before MFA | No rows visible |

The post-rollback receipt confirmed zero remaining fixture users, sessions, roles, offerings, enrollments and publications. An independent lossless read-only export then confirmed exact pre-test/post-test equality for all application data, both migration rows and hashes, all 15 policies (11 original plus 4 new), RLS flags, original authorization helpers, Auth user identities and Auth session identifiers/assurance/expiry fields. The publication table is empty. The approved test transaction reached its explicit rollback and left no fixtures or persistent changes. No unexpected error or failed assertion occurred during this resumed verification/test run.

Private evidence is saved under `.tools/publication-backup/`: `lossless-before-tests.json`, `lossless-after-tests.json`, `security-executed.sql`, `security-rollback-result.json` and `final-verification.json`. These local exports are ignored and contain no passwords or session tokens. These tests exercise actual PostgreSQL policies with controlled fictional JWT claim settings; they do **not** constitute a desktop/portal sign-in or TOTP protocol test.

## Subsequent desktop/student acceptance

The ordinary professor account subsequently published one saved fictional 72% C final through the native desktop, using password/TOTP and its own RLS-constrained session. Student A read that snapshot after MFA; Student B could not read it. Both students were denied protected reads before MFA and could not publish grades. Local edits remained private. Completed refresh/logout and access/refresh-token replay checks passed; a premature logout timing assertion is disclosed in the [acceptance report](PUBLISHING_ACCEPTANCE_REPORT.md).

An independent lossless export after acceptance confirmed exact equality of existing role, offering, enrollment and record rows, Auth identities, both migration history rows/hashes, helpers, all 15 policies and RLS flags against the verified post-migration baseline. Exactly one intended fictional publication remains, with score 72, letter C and server timestamp `2026-11-20T10:40:16.613213+00:00`. No active test sessions remain. Private receipts are `end-to-end-state.json`, `end-to-end-integrity.json` and `browser-end-to-end.json` under ignored `.tools/publication-backup/`. The original migration and its checksum were not modified or rerun. Any future schema change requires a new version.
