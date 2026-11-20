# Desktop online-first migration

## Audited baseline (November 20, 2026)

The installed SQLite workspace had ten applied migrations at the start of the cutover. Its backup passed a schema and row comparison, integrity_check and foreign_key_check. The verified SHA-256 is 72d6ea6dcca931ff02a3bb5fab53146bd038d61c4e201f67da7f661cf06c1d1b. Keep the backup outside Git.

| Domain | SQLite rows | Supabase representation before cutover |
| --- | ---: | --- |
| Courses, terms, offerings | 2, 1, 2 | Imported UUIDs and catalog metadata. Desktop descriptions, timestamps, term status and offering notes are missing. |
| Students and enrollments | 90, 70 | Private roster and stable mappings. Two explicit Auth links. Contact fields and notes remain local. |
| Lectures and attendance | 24, 840 | Imported lecture metadata and attendance marks. Detailed planning, notes, duration and edit revisions are missing. |
| Categories, assessments, grade entries, scales | 8, 8, 280, 2 | Local draft gradebook. The distinct public table contains one explicitly published legacy snapshot. |
| Questions and choices | 41, 32 | Local question bank including answers and usage. |
| Exams, question snapshots, snapshot choices | 7, 85, 64 | Local exam content, order, keys and draft/finalized/archived state. |

Hosted inventory is based on the preceding verified import. Recheck the live schema, account ownership and migration history before applying a new migration.

## Cutover sequence

1. Gate the native window with managed password/TOTP and the existing server-side professor role check. Use only the configured HTTPS project and publishable key. Keep session credentials in memory and clear them on logout. Never use browser sessions or local academic data as authentication.
2. Prove an online vertical slice on an existing imported offering. Reads use its existing UUID. Writes enforce assignment and MFA, compare an edit revision, and return the committed value. Open a second authorized session and check the student catalog after the commit. Retain text on network failure or revision conflict.
3. Bind the original installation to its administrator-confirmed professor. For records without offering ownership, establish explicit workspace ownership before transferring private fields. Extend the existing mapped tables in additive migrations. Preserve primary keys, enrollment bindings and publication foreign keys. Provision new identities only as a separate administrative operation.
4. Add normalized private question/choice, exam/snapshot/choice and gradebook tables. Use composite offering foreign keys, exact integer score and weight units, uniqueness and score bounds. Enforce finalized exam immutability and single application of usage counts in the server transaction. Preserve snapshot content separately from the mutable question bank. Grade saves have no write path to published snapshots.
5. Backfill only missing rows and columns from the verified backup, compare counts, keys and values in both directions, then run a second import in a rollback transaction. Resolve differences before enabling each writer. Treat notes, answer keys, contacts and any image references as professor-only.
6. Replace each Java repository boundary behind the existing services and Compose controllers. Make reads, writes and bulk imports scoped server operations. Use optimistic revisions and atomic batches; implement idempotent operation receipts for retries following ambiguous commits. Preserve open form drafts until server acknowledgement or explicit discard. Refresh views after writes and on session activation.
7. Render PDF/Excel locally using the authorized cloud models. Keep OS paths, theme preferences and chosen export destinations on the device. Migrate embedded images only with explicit access-controlled storage and portable references.
8. Verify login, logout, expiration, MFA, other professors, student isolation, offline edits, conflicting writes, bulk rollback, exam finalization, explicit publication, document output and cross-session visibility. Compare the complete cloud workspace with the recovery snapshot before retiring SQLite as the primary workspace. Release and installer packaging are separate operations.

## Current implementation

The standard Gradle `run` task and the `runOnline` alias open the native professor sign-in screen. Supply the project origin and publishable key through `PROFESSOR_OS_SUPABASE_URL` and `PROFESSOR_OS_SUPABASE_PUBLISHABLE_KEY`, or reuse the corresponding `VITE_` entries in the portal’s local development configuration. Enroll the professor’s authenticator through the portal first. The desktop keeps the managed session in memory for the lifetime of the window. Choose `runRecovery` to open the local SQLite workspace explicitly.

Migration `202611200003` is installed in the hosted project. Lecture view and Matrix view share pending edits by enrollment and lecture ID. Select the teaching context, search for a student, then click a mark or type P, A, L, E or U while focused on the cell. Select a matrix column heading to open that lecture. Save attendance (Ctrl+S) saves the pending edits in the current offering, including edits hidden by a filter. Normal window close and sign-out ask before discarding edits. Unexpected process termination loses in-memory drafts.

Hosted migration `202611200006_attendance_sheet.sql` supplies the full enrollment roster, all lecture columns and one atomic save per offering. The professor can fill visible blanks or explicitly clear an individual mark. Withdrawn enrollments and lectures outside the completed or partially completed states are read-only. The database checks MFA and the teaching assignment. If another session has changed a selected mark, the batch rolls back. A missing mark remains distinct from an absence. When connecting to an older server that lacks the sheet function, the desktop can still edit previously recorded marks individually.

### Read-only hosted attendance check

Set the public project configuration described above. From a private interactive terminal with JDK 25, run the following commands using an assigned fictional professor account. The first prompt hides the password. A separate desktop dialog accepts the authenticator code. The check loads assigned offerings, searches a student in Lecture view, switches to Matrix view, checks the save state and signs out. It requires populated fictional offerings and sends no attendance changes.

```powershell
.\prototype-compose\gradlew.bat -p prototype-compose prepareAttendanceReadCheck
java '@prototype-compose/build/attendance-read-check.args' 'professor01@example.com'
```

The November 20 read-only check rendered both views for CS201 and CS202. Each returned 35 students, 12 lectures and 420 recorded marks. Student A’s 12 rows matched before and after a Brave refresh. Before installing the sheet migration, a restore rehearsal covered explicit clears, batch rollback and stale writes. An independent hosted read after installation verified both function definitions and grants, the four earlier migrations, all 15 existing policies and the original academic data.

### Attendance save conflicts

On November 20, a hosted CS201 save requested Excused for two cells, with a stale expected status on the second. PostgreSQL rolled back both edits. Migration `202611200006` originally used SQLSTATE `40001` for this condition. The hosted transaction runner retried the request until the desktop's 30-second deadline.

Hosted migration `202611200007_attendance_conflict.sql` changes that rejection to `PT409` (HTTP 409). Each write checks the previously read status, including Unmarked, in the same transaction. Intermediate edits that return to the original status are indistinguishable. The desktop keeps the batch pending on conflict and disables Save. Refresh reloads the saved marks so the professor can review and resolve the differences.

In the live check, the MFA-verified professor changed one fictional CS201 mark from Late to Absent. A two-cell desktop batch based on the old mark received one HTTP 409. PostgreSQL logged one rejection, both drafts stayed pending, and refresh identified the changed cell. The hosted Student Portal in Brave showed Absent and then Late after the professor restored the mark. The final comparison matched all 840 original marks, the other academic rows, the 15 existing policies and the earlier migration history. The broader private workspace migrations were outside this rollout.

Migrations `202611200004`, `202611200005` and `202611200008` are installed on the hosted project. The administrator backfill preserved the existing UUIDs and checked both directions against the recovery snapshot. The MFA-verified workspace owner can read all 16 academic tables through `desktop_read_workspace`. All fields matched the SQLite snapshot. Reassigning an imported offering disables this single-owner view. Windows image references require a separate storage migration if populated. Keep generated backfill SQL private because it contains recoverable grade and exam data.

Hosted migration `202611200009_attendance_eligibility.sql` applies the same locked eligibility check to the scalar and batch attendance endpoints. Both preserve marks for withdrawn students and allow changes only for completed or partially completed lectures in the assigned offering. The desktop services can load the 16-table response into a session-local in-memory database. That read model is separate from the protected recovery file.

Migrations `011` through `014` add cloud writers for the catalog, lecture plans, roster, gradebook, question bank and exams. The desktop runs its existing Java services against a temporary in-memory draft and sends the validated changes to the assigned professor’s Supabase workspace. The server checks the prior values, validates academic relationships, and returns the committed workspace. Failed writes preserve the pending desktop edits. A timeout can leave the result unknown, so refresh before retrying.

Roster writes preserve published history when enrollment status changes. Draft grades remain private, and explicit publication uses the linked student identity and the reviewed revision. Private exam images use verified content references and a temporary desktop cache. Finalized exams preserve their saved questions and answers. PDF and Excel continue to run locally through the existing Java services.

## Current ownership

On November 20, 2026, the private desktop workspace and all three portal offerings were transferred to `professor01@example.com`. The new professor signed in with MFA, read all 16 academic tables and 840 attendance marks, and created and removed a fictional test course. The retired test account had no remaining ownership references or Storage files and was deleted through Supabase Auth Admin. A final comparison verified the inherited data, RLS policies, and migration history.

## Windows restart check

1. Launch the online desktop, sign in with professor MFA, and open Attendance without making edits. Close the window normally rather than using Sign out.
2. Restart Windows. Sign in to the same Windows user profile and launch Professor OS with the same Supabase project configured. Confirm the workspace opens without a password or authenticator prompt and that Attendance loads.
3. In a separately authenticated Portal session for the same professor, sign out. Refresh Desktop and check that it still loads Attendance.
4. Sign out explicitly in Desktop. Close and relaunch it and confirm the professor sign-in screen appears.

Use the existing Windows profile and avoid copying or inspecting the protected credential file. Current-user DPAPI reopening and live Supabase session restoration were verified. A physical Windows reboot has not been performed.
