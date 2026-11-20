# Import a fictional desktop workspace

The desktop remains the source for offline work. An administrator imports a verified snapshot. Signing in, saving a grade or opening the portal never starts an import.

## Source and destination

Use this importer for the same Professor OS SQLite installation on repeat runs. UUIDv5 uses a fixed namespace, entity type and original integer key. A different installation can reuse those integers and needs its own namespace. The SQL refuses existing rows that disagree with the supplied snapshot. It can add new records and approved links. Changes to an already imported record, a withdrawal, a removed binding or a deleted source row require explicit reconciliation. Restrict access immediately through the existing administrator process if a student's authorization changes.

The private schema stores course codes and titles, departments and credits, term dates, offering sections, roster numbers and names, enrollment status, lectures and per-enrollment attendance. Only assigned professors or linked, enrolled students can read the portal projections after MFA and a live session check. Students see only their own attendance. The public catalog includes previously provisioned offerings. Only completed or partially completed lectures appear in the portal.

Student roster IDs are independent of Auth. An explicit number-to-user-UUID binding connects an existing student role. Only active bound enrollments receive a portal enrollment. Unbound students have no sign-in or portal access through this import. A professor binding identifies the assigned professor for all offerings in this single-professor workspace. Verify the target accounts and roles in the intended project before preparing the transaction.

Question banks, exams and keys, local files, contact details, free-text notes, assessment definitions, grading scales and raw scores are outside the transfer. Grades come exclusively from the existing explicit publication workflow. For publishing, use the mapped offering UUID and the bound student's **Auth UUID**. The private roster UUID identifies a desktop student independently of account provisioning.

## Run locally

Use Node 24 with the built-in SQLite module. Choose new filenames under a private ignored directory. Keep the verified backup securely and retain the binding manifest with it. The backup command uses SQLite's online backup API and checks schema, every row, integrity and foreign keys against a pinned read transaction.

~~~powershell
node tools/academic-import.mjs backup "$env:LOCALAPPDATA/ProfessorOS/data/professor-os.db" .tools/academic-import/workspace.sqlite
node tools/academic-import.mjs prepare .tools/academic-import/workspace.sqlite .tools/academic-import/bindings.json .tools/academic-import/import.sql
~~~

The folder must already exist. The prepare command checks the backup SHA-256 and writes a new SQL file exclusively. Bindings have this shape. Substitute administrator-verified UUIDs and the checksum from the backup command.

~~~json
{
  "fictional": true,
  "backup_sha256": "<64 hex digits>",
  "professor": "<assigned professor Auth UUID>",
  "students": [
    {"number": "<exact student number including leading zeros>", "user_id": "<student Auth UUID>"}
  ]
}
~~~

Apply the new versioned migration through the project's migration process, after verifying the destination, current history, existing policies and a recoverable backup. Execute the generated import as a database administrator in one transaction. It compares rows in both directions, checks role bindings and enrollment projections, and rejects conflicts. Never put admin credentials or the generated SQL into the portal, repository or Pages artifacts. Keep the private schema out of Supabase's exposed schemas.

## Verification

With an isolated PGlite installation, run the original publishing regressions and the snapshot import checks from the repository root. The last argument overrides the path to the installed PGlite entry point. Use a fictional manifest with students in different courses for the isolation test.

~~~powershell
node student-portal/supabase/tests/publication-local.mjs
node student-portal/supabase/tests/academic-import-local.mjs .tools/academic-import/workspace.sqlite .tools/academic-import/bindings.json
~~~

Compare source and target counts, account bindings and original application rows. In the locally served portal, sign in with each bound account and complete its own MFA challenge. Inspect My courses, course lectures, Attendance and Grades. A student enrolled only in CS201 must not see CS202 or another student's attendance. A course with no explicitly published final must have no grade. Sign out between accounts.

## Finding the desktop publishing identifiers

For a linked student, the administrator can look up the exact offering and Auth UUIDs to enter in the existing desktop publish dialog. Run this read-only query in the intended project. Verify the student number, course, term and section against the desktop before publishing.

~~~sql
select s.number, c.code, t.name, t.academic_year, o.section,
       o.id as portal_offering_id, k.user_id as portal_student_id
from academic_import.enrollment e
join academic_import.student s on s.id = e.student_id
join academic_import.account_link k on k.student_id = s.id
join academic_import.offering o on o.id = e.offering_id
join academic_import.course c on c.id = o.course_id
join academic_import.term t on t.id = o.term_id
join public.portal_enrollments p on p.offering_id = o.id and p.student_id = k.user_id
where e.status = 'ACTIVE'
order by s.number, c.code, t.academic_year, o.section;
~~~

## Verified November 20, 2026 import

The LocalAppData backup SHA-256 was `72d6ea6dcca931ff02a3bb5fab53146bd038d61c4e201f67da7f661cf06c1d1b`. Hosted migration `202611200002` has SHA-256 `ad733387bbaa5a8d8aa2081800b2a10c18db1eab949e197e9365ceac10ae8d1c`. Git preserves this applied file byte-for-byte, including its original final line ending.

| Imported entity | Verified rows |
| --- | ---: |
| Courses / offerings | 2 / 2 |
| Academic terms | 1 |
| Fictional roster students | 90 |
| Active enrollments | 70 |
| Completed lectures | 24 |
| Attendance marks | 840 |
| Explicit account links / new portal enrollments | 2 / 2 |
| New Auth identities / new published grades | 0 / 0 |

CS201 and CS202 each have 35 active enrollments and 12 completed lectures. Twenty roster students have no enrollment. The two demo accounts each have one new course and continue to have their original shared acceptance offering. The other 88 roster identities have no portal account link. The original two migrations, 15 public policies, three managed role assignments, existing offering, two enrollment links, two legacy records and single explicitly published grade passed the transaction guards.

The importer excluded all 280 local grade rows, eight assessments and eight categories, two grading scales, seven exams, 41 question-bank items and their choices, 85 exam-question snapshots and their choices, notes and email addresses. Only allowlisted columns were transferred. The SQL transport contains roster data and belongs in the private ignored directory with the backup and receipts.

### Browser acceptance

The locally served portal used the live Supabase project in Brave. Each student signed in through managed Auth and completed their existing TOTP challenge. Every displayed date, course, lecture, student name and attendance mark was compared with the verified SQLite snapshot.

| Account | Imported course and attendance | Grade boundary |
| --- | --- | --- |
| Student A | CS201, 12 rows: 11 present, 1 late. Its course detail showed 12 lectures. | Its original 72% C snapshot appeared for the separate legacy offering. |
| Student B | CS202, 12 rows: 8 present, 2 excused, 1 late, 1 absent. The ordinary CS201 detail URL showed “Course unavailable.” | Its Grades page showed zero published grades. |

Both sessions signed out. The isolated PostgreSQL suites exercised a repeat import, conflict rollback, foreign keys, professor assignment, password-only sessions, student isolation, anonymous denial, revoked sessions and grade-publication restrictions. These SQL role tests used an isolated Auth fixture. The two browser demonstrations used genuine managed sessions. The production build was checked locally; the deployed Pages site was outside this acceptance run.
