# Professor OS Student Portal

The [live Student Portal](https://retinazer21.github.io/ProfessorOS/) shows authorized courses, lectures, attendance, and published final grades. Sign in with a provisioned account and complete TOTP with an authenticator app. Supabase Auth manages sessions, and PostgreSQL row-level security scopes access to academic records.

## Local development

Use Node.js 24. In `student-portal`, copy `.env.example` to `.env.local` and set the Supabase project URL and public publishable key.

```sh
npm ci
npm run dev
npm test
npm run build
```

Use a publishable key in client configuration. Keep privileged keys and account credentials outside the frontend and Git.

The portal uses hash routing. GitHub Pages publishes under `/ProfessorOS/` through the manual workflow. See [Pages configuration](PAGES_SETUP.md), [authentication setup](AUTH_SETUP.md), and [grade publication](../docs/GRADE_PUBLISHING.md).
