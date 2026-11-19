# Professor OS Student Portal

The Student Portal retains its Studio Window UI and fictional academic previews, with real Supabase password authentication, sessions, required TOTP MFA, and database-level RBAC/RLS. See [Auth setup and verification](AUTH_SETUP.md) for configuration, provisioning, applied migration and remaining prerequisites.

## Run locally

Requires Node.js 24. Configure `.env.local` from `.env.example` before starting.

```powershell
cd student-portal
npm ci
npm run dev
```

Open the local URL printed by Vite. Protected routes show login until password authentication, MFA, and administrator-assigned access are verified.

## Verify

```powershell
npm run typecheck
npm run build
```

The production files are written to `dist/`. You can inspect them with `npm run preview`. No deployment is configured.

## Scope

React, TypeScript, Vite, Tailwind CSS and Lucide icons power this self-contained frontend. Fictional data lives in `src/demo.ts`; routes use URL hashes so every prototype screen can be opened directly without a router dependency. Desktop code and SQLite data remain separate. Supabase manages authentication and the new portal access tables. Academic displays remain fictional.

Next phase: provision university accounts and define the academic publishing and data adapter workflow.

## Design and motion

The [editable Figma redesign](https://www.figma.com/design/T99xrfBLmJDWl3L0WGYVMB) includes desktop and mobile concepts for every screen, shared color styles and components, and an interaction specification. `src/redesign.css` supplies the dark editorial palette and responsive layouts. Motion for React handles page and progress entrances; CSS handles shorter hover, sidebar, chart, and skeleton effects. Optional movement respects `prefers-reduced-motion`.

The dashboard uses the approved Studio Window direction in `src/Dashboard.tsx` and `src/dashboard.css`. The academic pages carry the same typography, dark surfaces, and fine dividers through scoped rules in `src/studio-pages.css`. Courses and results reuse the dashboard artwork through `src/CourseArtwork.tsx`. The four subject images come from one locally bundled contact sheet; its generation prompt is in `src/assets/studio-courses.prompt.txt`. Course entrances, score and attendance fills, chart reveals, and security controls use the existing Motion and CSS patterns.

Browser captures include [Studio Window on desktop](screenshots/studio-window-desktop.jpg), [Studio Window on mobile](screenshots/studio-window-mobile.jpg), [courses](screenshots/courses-desktop.jpg), and [mobile grades](screenshots/grades-mobile.jpg). The login and MFA screens now use managed Supabase authentication.
