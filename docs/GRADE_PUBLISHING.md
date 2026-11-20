# Explicit grade publishing — first vertical slice

Status: migration `202611200001` was executed once on 2026-11-20. Lossless integrity checks, live rollback-only database security tests and the actual desktop → Supabase → Student Portal workflow passed with fictional records and ordinary MFA sessions. One intended 72% C publication remains; both students are signed out. See [acceptance results](PUBLISHING_ACCEPTANCE_REPORT.md) and [exact migration outcome](PUBLICATION_MIGRATION_STATUS.md). Do not rerun or edit the migration.

The application snapshot is saved in two locations and recovery-tested, SHA-256 `36ca37af017b973e3c690bab4e6ad33ba6d5aca38724c10e7ebd6204f4e987ea`. It covers all four original application tables/data, 13 columns, 11 constraints, 11 policies, 3 authorization helpers and the original migration/history. It is not a managed Auth password/session backup. The exact CRLF editor transaction passed offline rehearsal and byte comparison before its single authorized execution. Its SHA-256 is `5c2c188c7a30ff4a7666ef58af1255a5640fa97e95c58aa7517c2bd8087df1d5`.

## Behavior and boundaries

The desktop Gradebook adds **Publish final grade** alongside existing local actions. Select one active local enrollment, enter the administrator-provided portal course/student UUID mapping, and review the saved final result. Enter the professor's email, password and current authenticator code privately, then choose **Confirm and publish**. An unsaved draft cannot be published; the Java service reloads saved grades and uses the existing `GradeCalculator`. Incomplete finals are rejected. Nothing uploads on save, startup or refresh.

The optional Java HTTP client calls Supabase password Auth, retrieves an existing verified TOTP factor, challenges/verifies it, and checks the existing `portal_access` professor role. The REST write uses that user's MFA session, a browser-safe publishable key and RLS; there is no service-role key. HTTPS, bounded timeouts and no redirect following are enforced. The client does not persist passwords, codes, access tokens or refresh tokens. It revokes its own session afterward; existing browser sessions are not globally logged out. Authenticate anew for each publish. Enroll MFA in the existing portal first. If multiple verified TOTP factors exist, this initial desktop client uses the first one.

`portal_published_grades` stores only the explicitly published final score/letter, course/student UUIDs and server timestamp. A composite primary key makes repeat publication an upsert rather than a duplicate. A later local edit remains private until another explicit publication. Other local grades, names, roster identifiers and draft data are not uploaded. Offline grade editing/saving remains unchanged. There is no background sync or automatic retry queue; an uncertain request can safely be repeated for the same mapping.

Existing MFA/session/ownership/enrollment helpers protect reads and writes. Students can read only their own enrolled publications; only the assigned professor can insert/update them. There is no browser delete grant. The portal Grades screen adds a **Published grades** section using authenticated REST and a course-title join; existing preview panels remain labeled fictional. Empty/loading/error states never substitute demo rows for published data. Use **Refresh published grades** after publishing.

## Configuration and fictional acceptance fixture

Keep the existing Supabase project and the previously provisioned fictional accounts. Confirm their current UUIDs before use; the historical manifest is `student-portal/tests/e2e-fixture.json`. The initial intended mapping is:

| Entity | Fictional fixture UUID |
| --- | --- |
| Portal offering | `b5ad5efc-2093-4a2d-80d9-206e5082d4d1` |
| Student A | `894659e5-c2ed-429d-a7fe-952fa29a82d8` |
| Professor | `ac064ccb-3d34-4aa1-b7d9-342da2ff7ee2` |

The administrator must verify the selected local identity corresponds to the remote student and course. This initial slice deliberately uses explicit mapping rather than guessed email matches or automatic roster creation. RLS verifies remote assignment/enrollment, not correspondence with a professor's local SQLite IDs. Do not use real student data for this acceptance test.

Before launching the desktop, set these process-local variables:

```powershell
$env:PROFESSOR_OS_SUPABASE_URL = 'https://bsvrqsqftmaoehlappbv.supabase.co'
$env:PROFESSOR_OS_SUPABASE_PUBLISHABLE_KEY = '<project sb_publishable_ key>'
```

Use the same public project configuration as the portal. Never use an admin key or place passwords/codes in environment variables, source files or chat.

JDK 25 was downloaded from official Eclipse Adoptium metadata, SHA-256 verified, and extracted under ignored `.tools/jdk25/jdk-25.0.5+8`. Select it for this shell (no global installation required):

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk25/jdk-25.0.5+8').Path
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:MAVEN_USER_HOME = Join-Path (Get-Location) '.tools/maven-home'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.tools/gradle-home'
```

After building backend classes and copying runtime dependencies, prepare a **new** fictional workspace:

```powershell
java -cp 'target/classes;target/compose-libs/*' com.professoros.demo.PublicationDemo .tools/publication-demo
$env:PROFESSOR_OS_DATA_DIR = (Resolve-Path '.tools/publication-demo').Path
.\prototype-compose\gradlew.bat -p prototype-compose run
```

`PublicationDemo` refuses any existing directory, uses existing services to create one fictional student/course and a saved 72% C final, and uploads nothing. Do not point it at an existing workspace or copy its database over user data. Select its offering in teaching context and open Gradebook. No desktop installer needs building.

## Migration and acceptance

Migration `202611200001_published_grades.sql` is already applied to the configured project. Do not rerun or edit it. Fresh environments must apply versioned migrations through the trusted deployment process after backup and schema/policy inspection. Preserve applied source/history/checksums; never bypass integrity checks. This migration has transactional DDL, no backfill, and a schema-cache reload notification. Auth users, roles, offerings and enrollments are not changed.

The live preflight on 2026-11-20 confirmed the original migration SHA-256, three fictional roles, one offering, two enrollments and two records, and that the new publication table was absent. Backup export, recovery rehearsal, migration execution, lossless integrity verification and rollback-only security tests subsequently completed. The migration report records the earlier editor issues and exact final outcome.

After migration, first run the rollback-only authorization tests against the real schema, then use the ordinary professor account in the desktop and ordinary Student A in the local portal:

1. Verify Student A has no published rows initially and cannot write. Verify password-only read/write denial, cross-student denial and an unassigned professor's publishing denial.
2. Publish the fictional 72% C final with professor MFA. Student A's Published grades must show it; Student B must not see it.
3. Publish again with the same mapping; verify there is still exactly one row. Change/save the local grade without publishing; the portal must retain 72% C until a second explicit publication.
4. Verify revoked-session denial and that failed/offline requests do not report success. Restore the fictional local grade if changed. Preserve existing fixtures and data; do not casually delete or reset them.

No GitHub Pages deployment, Git push or installer release is required to test this slice locally. Actual Supabase TOTP, PostgREST course-title joins and the local desktop/student UI path passed. The updated publishing UI has not been deployed or verified at the hosted Pages origin.

## Local verification

Run Maven before Compose, sequentially, with the app closed:

```powershell
.\mvnw.cmd -B -ntp -DforkCount=0 clean verify
.\prototype-compose\gradlew.bat -p prototype-compose test
```

For isolated SQL checks (no live connection), install the pinned test runtime in ignored tooling, then execute the checked-in harness from repository root:

```powershell
node .tools/package/bin/npm-cli.js install --prefix .tools/publication-sql --no-audit --no-fund @electric-sql/pglite@0.3.14
node student-portal/supabase/tests/publication-local.mjs
```

The harness runs both migrations against PostgreSQL/WASM with stand-in Auth helpers, runs the original RLS regression and checks unpublished privacy, MFA bypass, cross-student reads, unauthorized publishing, repeated upsert and session revocation. It does not emulate hosted Auth or PostgREST.

The Java suite passed 230 tests including saved/incomplete grade snapshots, insecure/privileged configuration denial and an isolated HTTP Auth/MFA/upsert/revocation contract. The portal's eight access tests, TypeScript, production build and Pages asset/artifact checks passed. The full Compose suite passed 43 tests after fixing an existing screenshot helper's missing output-directory creation; six Gradebook tests passed again after the final publication-enable guard. The publishing snapshot was visually inspected. Native startup on the isolated one-student/course database succeeded and its saved 72% C final was observed. No installer was built.

The actual desktop publication succeeded using the ordinary professor's password and existing authenticator. Student A's MFA session loaded its fictional 72% C snapshot with course title and server timestamp; Student B's MFA session saw no publications and could not request Student A's directly. Both students' password-only sessions were denied reads from all five protected tables. Student writes were denied. Saving 73% locally without publishing left the remote snapshot at 72%; the local fixture was restored and saved to 72%. Refresh, reload and completed logout/token replay checks passed. A premature Student B logout assertion failed before SIGNED_OUT; all five checks passed after sign-out completed, and the original timing failure remains in private evidence. The final lossless comparison preserved original records, both migration rows, helpers and all 15 policies; exactly one intended publication and zero active test sessions remain.
