# Desktop → Supabase → Student Portal acceptance

Completed 2026-11-20 using only the three existing fictional test accounts and one isolated local student/course. The desktop used its Java Auth/REST client with the project publishable key and an ordinary professor MFA session. Portal checks used ordinary managed student sessions. No service-role key or authentication bypass was used.

| Check | Result and evidence scope |
| --- | --- |
| Explicit saved-grade publication | PASS — native desktop published 72% C; real Supabase returned success and the portal showed the matching course and server timestamp |
| Pre-MFA protection | PASS — fresh Student A and B password-only sessions could not read any of the five protected application tables or request a record by ID |
| Student A own publication | PASS — AAL2 student session returned exactly one 72% C snapshot; course-title join and timestamp loaded in the existing Grades UI |
| Student B isolation | PASS — AAL2 student session returned zero publications; direct Student A UUID query also returned no rows |
| Unauthorized student publishing | PASS — both students' attempted upserts returned PostgreSQL permission error 42501 |
| Professor assignment and MFA enforcement | PASS — live rollback-only policy tests denied AAL1 and unassigned-professor writes and allowed the assigned AAL2 professor |
| Duplicate prevention | PASS — repeated upsert produced one row in live rollback-only SQL tests and the Java HTTP contract; the desktop acceptance published once |
| Unpublished local edit privacy | PASS — changed and saved local 72 → 73 without publishing; Student A UI/API remained at 72. Restored and saved local 72 afterward |
| Managed sessions | PASS — both student sessions refreshed with the same identity/AAL/role; Student A browser reload restored its protected grade view |
| Logout and token replay | PASS after completed sign-out — both students' old access tokens could not read records/publications or authorize a role; refresh tokens were rejected |
| Data and migration integrity | PASS — final lossless Base64/server-SHA comparison preserved original application rows, Auth identities, both history entries/hashes, helpers, all 15 policies and RLS flags |

The browser report retains 56 passing entries (including setup and repeated checks) and one premature Student B logout failure: the assertion ran while the portal still displayed “Checking secure access…”, before SIGNED_OUT. After the sign-in form and SIGNED_OUT event appeared, all five logout/replay assertions passed. The original failure was retained, not erased. The runner instructions now explicitly require both completion signals before checking logout.

Local verification passed: Maven clean verify (230 tests), full Compose suite (43 tests), subsequent Gradebook suite (6 tests), portal access suite (8 tests), TypeScript/production build and Pages asset/artifact checks. The nonfatal frontend chunk-size warning remains. Official checksum-verified Temurin JDK 25.0.5+8 runs from ignored local tooling; no system-wide JDK setting or installer was changed. Graphify is unavailable in this checkout.

Exactly one intended fictional publication remains for Student A and the existing test offering, with server timestamp `2026-11-20T10:40:16.613213+00:00` (13:40:16 Baghdad). Original fixture records remain 72 and 84. Final inspection found zero active sessions for the test accounts. The isolated desktop database remains at its original saved 72% C and the test app was closed. No unrelated local database was opened or overwritten.

Private ignored evidence is under `.tools/publication-backup/`: `browser-end-to-end.json`, `end-to-end-state.json`, `end-to-end-integrity.json`, `student-a-published.jpg` and `student-b-isolated.jpg`, alongside the recovery-tested migration backup and rollback receipts. No password, authenticator secret, real code or user session token is included in these reports/screenshots. Backups and local databases remain outside Git and the Pages artifact.

No manual authentication step remains for this acceptance run. The updated publishing UI has not been deployed to GitHub Pages; no commit/push or desktop installer build was performed. Verified real-user provisioning, UUID mapping, account/authenticator recovery and a reviewed production operations/backup process remain prerequisites before real academic use. Network-outage behavior was not exercised against the live service; the HTTPS client uses bounded requests, reports unconfirmed publication as failure and offers safe explicit retry rather than a background sync queue.
