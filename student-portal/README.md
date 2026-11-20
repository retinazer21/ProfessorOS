# Professor OS Student Portal

The Student Portal retains its Studio Window UI and fictional academic previews, with real Supabase password authentication, sessions, required TOTP MFA, and database-level RBAC/RLS. See [Auth setup and verification](AUTH_SETUP.md) for configuration, provisioning, applied migration and remaining prerequisites.

## Run locally

Requires Node.js 24. Configure `.env.local` from `.env.example` before starting.

```powershell
cd student-portal
npm ci
npm run dev
```

Open `/ProfessorOS/` at the local origin printed by Vite. Protected routes show login until password authentication, MFA, and administrator-assigned access are verified.

## Verify

```powershell
npm run typecheck
npm run build
npm test
npm run check:pages
```

The production files are written to `dist/`. Inspect them with `npm run preview` at `http://localhost:4173/ProfessorOS/`. Vite uses base `/ProfessorOS/`; application routes remain hash-based.

## GitHub Pages

Intended URL: [retinazer21.github.io/ProfessorOS](https://retinazer21.github.io/ProfessorOS/). Hosting is prepared, not deployed by this change. The manual-only **Student Portal Pages** workflow builds and checks by default; publication requires explicitly selecting its deploy option. Only `dist/` is uploaded. Configure the public Supabase repository variables and dashboard URL settings using the [Pages runbook](PAGES_SETUP.md). Password/TOTP login has no redirect callback; email recovery, invite and OAuth callback screens are not implemented. Desktop build and installer workflows are outside this deployment.

## Scope

React, TypeScript, Vite, Tailwind CSS and Lucide icons power this self-contained frontend. Fictional data lives in `src/demo.ts`; routes use URL hashes so every prototype screen can be opened directly without a router dependency. Desktop code and SQLite data remain separate. Supabase manages authentication and the new portal access tables. Academic displays remain fictional.

The Grades page has a separate authenticated Published grades section. The additive migration, lossless integrity checks, live rollback-only RLS tests and actual desktop-to-student publishing acceptance passed using fictional accounts. Student A sees its 72% C snapshot; Student B cannot read it. Local desktop saves do not publish automatically. See [acceptance results](../docs/PUBLISHING_ACCEPTANCE_REPORT.md) and [publishing setup](../docs/GRADE_PUBLISHING.md). Other academic views remain fictional previews.

## Design and motion

The [editable Figma redesign](https://www.figma.com/design/T99xrfBLmJDWl3L0WGYVMB) includes desktop and mobile concepts for every screen, shared color styles and components, and an interaction specification. `src/redesign.css` supplies the dark editorial palette and responsive layouts. Motion for React handles page and progress entrances; CSS handles shorter hover, sidebar, chart, and skeleton effects. Optional movement respects `prefers-reduced-motion`.

The dashboard uses the approved Studio Window direction in `src/Dashboard.tsx` and `src/dashboard.css`. The academic pages carry the same typography, dark surfaces, and fine dividers through scoped rules in `src/studio-pages.css`. Courses and results reuse the dashboard artwork through `src/CourseArtwork.tsx`. The four subject images come from one locally bundled contact sheet; its generation prompt is in `src/assets/studio-courses.prompt.txt`. Course entrances, score and attendance fills, chart reveals, and security controls use the existing Motion and CSS patterns.

Browser captures include [Studio Window on desktop](screenshots/studio-window-desktop.jpg), [Studio Window on mobile](screenshots/studio-window-mobile.jpg), [courses](screenshots/courses-desktop.jpg), and [mobile grades](screenshots/grades-mobile.jpg). The login and MFA screens now use managed Supabase authentication.
