# Professor OS

An online academic workspace for university professors, with a separate Student Portal. Supabase stores the desktop workspace and manages sign-in and MFA for both applications.

**Student Portal — planned GitHub Pages address:** [retinazer21.github.io/ProfessorOS](https://retinazer21.github.io/ProfessorOS/). Hosting is prepared but publication has not been performed by this change.

> Portal authentication and explicit final-grade publishing are implemented. A fictional 72% C grade passed the complete desktop → Supabase → Student Portal workflow with professor/student MFA, student isolation and unpublished-edit privacy. The portal reads fictional course and attendance records through the [academic import workflow](docs/ACADEMIC_IMPORT.md). See [acceptance results](docs/PUBLISHING_ACCEPTANCE_REPORT.md), [migration integrity](docs/PUBLICATION_MIGRATION_STATUS.md) and [publishing setup](docs/GRADE_PUBLISHING.md).

## Features

| Desktop workspace | Student Portal |
| --- | --- |
| Courses, academic terms, offerings and enrolled rosters | Responsive Studio Window dashboard and course browsing |
| Lectures and saved attendance with bulk marking | Authorized course details, published final grades, lecture attendance and account settings |
| Gradebook, weighted assessments and configurable scales | Supabase password sign-in and managed sessions |
| Explicit publication of one saved final grade using professor MFA | Published snapshots visible only to authorized users |
| Question bank, exam generation and saved exam snapshots | Required authenticator TOTP enrollment and verification |
| Arabic/English Excel student import and formatted exports | Student/professor roles enforced through database RLS |
| Exam and answer-key PDF preview/export | Session refresh and browser-session sign-out |
| Protected SQLite recovery workspace and local appearance settings | Hash routing compatible with static GitHub Pages hosting |

Desktop Settings and the portal’s Appearance gallery share ten workspace themes. Choose a theme or follow the system, adjust the accent, and reduce motion. Each device or browser keeps its own preference.

The standard desktop opens the MFA-protected workspace. Use `runRecovery` to open the local SQLite recovery workspace explicitly. See the [online desktop setup and migration status](docs/DESKTOP_ONLINE_MIGRATION.md).

## Screenshots

### Student Portal — desktop

![Studio Window student dashboard with fictional academic data](student-portal/screenshots/studio-window-desktop.jpg)

### Course browsing

![Student Portal courses](student-portal/screenshots/courses-desktop.jpg)

### Mobile layout

<img src="student-portal/screenshots/studio-window-mobile.jpg" alt="Student Portal mobile dashboard" width="360" />

These committed images illustrate the portal design and may predate the live authentication integration. See [authentication acceptance results](student-portal/tests/results/AUTH_ACCEPTANCE_REPORT.md) for recorded security checks.

## Architecture

```text
Compose Desktop UI (Kotlin/JVM)
    → Java services → session-local academic view
    → authenticated Supabase PostgreSQL reads and writes

Student Portal (React + TypeScript + Vite)
    → Supabase Auth: password, sessions, TOTP
    → Supabase PostgreSQL: role/ownership policies and RLS

GitHub Pages serves the portal's static build.
Students can read only explicitly published final-grade snapshots.
```

Domain models remain independent of UI and database access. Desktop services own validation and academic calculations; repositories own parameterized SQL. The portal keeps authentication coordination outside page components and checks both MFA assurance and database-assigned access before opening protected routes.

| Area | Location |
| --- | --- |
| Java backend and isolated tests | `src/`, `pom.xml` |
| Compose desktop screens and controllers | `prototype-compose/` |
| Portal source and authentication | `student-portal/src/` |
| Portal RLS migration and regression SQL | `student-portal/supabase/` |
| Local and deployment checks | `student-portal/tests/`, `student-portal/scripts/` |
| Manual Pages workflow | `.github/workflows/student-portal-pages.yml` |
| Academic workflow and detailed desktop setup | [User guide](docs/USER_GUIDE.md) |
| Technical decisions | [Project notes](PROJECT_NOTES.md) |

`secure-academic-demo/` is a separate assignment demo, not part of the Professor OS portal deployment.

## Install and run from source

### Desktop workspace

Requires **JDK 25**, `JAVA_HOME` pointing to that JDK, and a graphical desktop. Maven and Gradle wrappers download their pinned dependencies on first use.

```powershell
git clone https://github.com/retinazer21/ProfessorOS.git
cd ProfessorOS
.\prototype-compose\gradlew.bat -p prototype-compose run
```

On Linux/macOS, use `./prototype-compose/gradlew -p prototype-compose run`. Sign in with a provisioned professor account and its enrolled authenticator. Set `PROFESSOR_OS_SUPABASE_URL` and `PROFESSOR_OS_SUPABASE_PUBLISHABLE_KEY` for the desktop. The Gradle launcher also accepts the matching `VITE_` values from `student-portal/.env.local` when the desktop variables are absent.

The `runRecovery` task opens the local SQLite workspace, which defaults to `data/professor-os.db` in development. Set `PROFESSOR_OS_DATA_DIR` to choose a different recovery directory. See the [user guide](docs/USER_GUIDE.md) for academic workflows and the Windows shell workaround.

### Student Portal

Requires **Node.js 24** and an administrator-configured Supabase project.

```powershell
cd student-portal
npm ci
Copy-Item .env.example .env.local
# Edit .env.local with the project URL and browser-safe publishable key.
npm run dev
```

Open the printed URL at `/ProfessorOS/`. Only a Supabase `sb_publishable_` key belongs in browser configuration. University accounts and student/professor roles must be provisioned by an administrator; public registration is disabled. See [portal setup](student-portal/README.md) and [Auth configuration](student-portal/AUTH_SETUP.md).

## Cybersecurity and data protection

- Supabase manages password authentication, session refresh and TOTP MFA. Academic authorization requires a server-validated user, AAL2 and an assigned database role.
- PostgreSQL RLS restricts students to their enrolled records and professors to their assigned offerings. Browser-editable metadata cannot grant privileges.
- Protected database access checks the live session, so signing out blocks replay of that session’s token. Desktop and Portal sign out independently. Windows Desktop stores the refresh credential with current-user DPAPI and validates the professor role and MFA when it restores a session.
- Role, offering and enrollment provisioning is restricted to trusted administration. Frontend builds never receive an administrative or service-role key.
- Desktop persistence uses parameterized JDBC queries, foreign keys and versioned migrations. Applied migrations and checksums must be preserved.
- Local credentials, databases, backups, caches and build output stay outside Git and the Pages artifact. Pages content and frontend configuration are public; access to protected data depends on Auth and RLS.

The recorded interactive acceptance runs covered two fictional students and one professor, MFA rejection and success, student isolation, role escalation denial, professor updates with restoration, refresh, logout and token replay. The publishing run additionally verified a desktop-authenticated final-grade write, student reads and write denial, and privacy of later local edits. This is scoped test evidence, not a comprehensive security audit. Account recovery, verified university provisioning/mapping and production operations remain prerequisites for real records.

## Verification

Portal, from `student-portal/`:

```powershell
npm test
npm run typecheck
npm run build
npm run check:pages
npm run preview
```

Preview opens the production build under `/ProfessorOS/`. The Pages check validates asset responses, base paths, hash-route entry, logout routing and artifact hygiene. Live Supabase tests require an authorized project and separate configuration; see [Auth setup](student-portal/AUTH_SETUP.md).

For desktop source changes, close the app and run Maven and Compose tests **sequentially**:

```powershell
.\mvnw.cmd clean verify
.\prototype-compose\gradlew.bat -p prototype-compose test
```

Tests use isolated fixtures. See [desktop setup](prototype-compose/README.md), [Excel QA](docs/EXCEL_QA.md) and [feature parity](docs/COMPOSE_PARITY.md).

## GitHub Pages hosting

The portal uses Vite base `/ProfessorOS/` and hash routes such as `/ProfessorOS/#/login`. GitHub Pages publishes only `student-portal/dist`, never the repository, desktop files, SQL, tests or local environment files.

The **Student Portal Pages** workflow runs only when manually requested on `main`. Its **deploy** option defaults to **false**. Building or pushing does not publish anything. Before an approved publication, configure repository variables, Pages and the Supabase production URL as described in the [Pages runbook](student-portal/PAGES_SETUP.md). That runbook includes verification and rollback steps.

## Scope and next steps

The desktop workspace and minimal final-grade publishing path are implemented and passed fictional live acceptance behind real authentication and RLS. Course/student mapping remains an explicit administrator-verified UUID entry. Broader roster/course publishing, university account delivery and recovery, and a reviewed production operations process remain future work, alongside planning, analytics and advanced exam constraints.

Read [AGENTS.md](AGENTS.md) before development. Preserve user data and existing migration history. Desktop packaging is outside the Pages workflow; this hosting preparation does not build, modify or publish an installer.
