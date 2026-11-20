# GitHub Pages configuration and manual publication

Live site: [Professor OS Student Portal](https://retinazer21.github.io/ProfessorOS/).

## Configuration before publication

1. In GitHub repository Settings → Pages, select **GitHub Actions** as the publishing source. Do not select a source branch containing project files.
2. In Settings → Secrets and variables → Actions → Variables, set repository variables `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY` from the intended project. The key must start with `sb_publishable_`. These values are public client configuration embedded in JavaScript. Never use a service-role, secret, database password or personal access token here. The workflow rejects missing/placeholder configuration.
3. Review the `github-pages` environment and restrict deployment branches to `main`; enable required reviewers when your GitHub plan supports them.
4. In Supabase Authentication → URL Configuration, set the production **Site URL** to `https://retinazer21.github.io/ProfessorOS/`. Keep localhost development URLs only if needed, and prefer exact production redirect allowlist entries over broad wildcards. These dashboard changes are manual prerequisites, not changes performed by this task.
5. Confirm public signup remains disabled, TOTP enabled, university accounts provisioned and reviewed RLS applied. Hosting a frontend does not deploy SQL or provision accounts. Never reapply or edit an already-applied migration.

## Authentication and URL behavior

The current implementation signs in with email/password and verifies TOTP through the Supabase SDK. It does **not** send users through OAuth, email-link or password-reset redirects. `detectSessionInUrl: false` prevents the SDK from consuming the application hash as an Auth callback. Navigating to `#/login`, `#/mfa` or `#/grades` preserves `/ProfessorOS/`; logout changes only the fragment to `/login`.

Do not point invite/reset/OAuth links at these hash routes and expect them to work. Recovery and callback screens remain unimplemented; adding a redirect allowlist entry does not implement them. A future PKCE callback must deliberately exchange its code, restore a validated internal destination under the base path and be tested separately. Managed sessions are origin-scoped; localhost sessions will not carry over to the Pages origin.

## Build-only rehearsal

From `student-portal/`, with Node 24 and dependencies installed:

```sh
npm test
npm run typecheck
npm run build
npm run check:pages
npm run preview
```

Open the preview at `http://localhost:4173/ProfessorOS/`. Local `.env.local` stays ignored. In Actions, select **Student Portal Pages → Run workflow → main**, leaving **deploy unchecked**. That explicit run builds, checks and uploads only `dist`; it does not deploy. No push or pull-request trigger exists.

The artifact contains the application HTML, compiled assets and public favicon. Sources, SQL, local test runners, audit backups and environment files are not copied. Automated checks reject unexpected sensitive file types, private-key markers and embedded literal session tokens. They are a guard, not a replacement for reviewing public assets and frontend content before publication.

## Publication — requires separate approval

After approval, choose **Run workflow**, select `main`, and check **deploy**. Before publishing, the deployment job compares the build SHA with the current `main` SHA using read-only repository access. It publishes through the `github-pages` environment with Pages/OIDC permissions. Historical runs retain their original commit and workflow definition. Rerunning a deployment from before the SHA check can restore an obsolete portal.

After publication, verify assets at the target URL, direct opening/reload of hash routes, password-only record denial, TOTP success and rejection, both student identities, refresh, global logout, and replay denial. Use fictional accounts and enter passwords/codes privately. Local build checks do not prove hosted-origin Auth behavior or Supabase dashboard settings. Keep private caches, screenshots containing enrollment QR codes and credentials out of the repository.

To roll back after approval, revert the frontend change through normal Git history and manually deploy the reviewed revision. No database rollback is implied. Disabling Pages is a separate repository setting change.

References: [Vite project Pages base](https://vite.dev/guide/static-deploy.html#github-pages), [GitHub Pages workflows](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages), [Supabase redirect configuration](https://supabase.com/docs/guides/auth/redirect-urls).
