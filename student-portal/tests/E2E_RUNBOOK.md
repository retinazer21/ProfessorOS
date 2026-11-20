# Interactive authentication acceptance test — completed

Use only these fictional accounts in the Supabase Auth dashboard (Auto confirm enabled):

- professor-os-e2e-student-a@example.invalid
- professor-os-e2e-student-b@example.invalid
- professor-os-e2e-professor@example.invalid

Account password entry is a manual step. Choose test-only passwords and keep them locally. Do not put them in chat, source control, screenshots, or the test report. No emails are sent to these reserved `.invalid` addresses. Public signup remains disabled.

## Next steps once accounts exist

1. Confirm creation through Supabase MCP; assign two student roles and one professor role, and seed two clearly labeled fictional records in a dedicated test offering. Preserve every pre-existing row. Save public fixture IDs (no credentials) to `tests/e2e-fixture.json`.
2. Open the existing portal at `http://127.0.0.1:5175/ProfessorOS/#/login` and the isolated test runner at `http://127.0.0.1:5175/ProfessorOS/tests/e2e.html` in another tab. Both must use the same origin so the SDK can share its managed session.
3. Sign in as Student A. Before MFA, run the pre-MFA checks and attempt protected portal URLs. They must remain blocked, and direct REST queries must return no records.
4. In the portal, choose Set up authenticator. In Google Authenticator, Microsoft Authenticator, or your existing TOTP app, choose Add account → Scan QR code. Scan the portal's QR privately; never save it in a committed screenshot. Enter the current six-digit code directly in the portal and choose Verify and continue. The user performs authenticator enrollment and code entry. Also verify an intentionally invalid code is rejected before entering the correct code.
5. After verification, run the verified access checks, reload the portal and verify access is restored, open another portal tab, and run managed-session refresh checks.
6. Capture the session in the runner, use Sign out in the original portal, confirm the portal's sign-in form and the runner's SIGNED_OUT event, then run the logout/replay checks. Starting the check while sign-out is still pending tests the wrong state. Test the browser Back button and reload after logout.
7. Repeat sign-in, MFA and verified access checks for Student B and Professor. Verify repeated password sign-in requires the existing authenticator, rather than creating a new factor.
8. Export the runner's visible report (no secrets), inspect Auth logs and preserve/identify the fictional fixtures for repeat testing. Do not claim pending manual tests passed.

The runner exists only as a local Vite development HTML entry; it is not included in the normal production build. It rejects all accounts except the three test emails. Reads use authenticated/anonymous REST through the actual SDK, never an admin key. Write tests target only the manifest's fictional records. Captured session tokens remain in memory, and report persistence contains only names/results/timestamps.

## Current results (2026-11-13)

- PASS: live direct signup rejected while public signup remains disabled.
- PASS: live anonymous REST record read and authorization RPC rejected.
- PASS: live fabricated Auth token rejected.
- PASS: deployed migration history integrity checked before testing.
- PASS: all three fictional Supabase Auth accounts exist with confirmed emails. Assigned two student roles and one professor role; seeded one dedicated fictional offering, two enrollments and two clearly labeled records. Public IDs are in `e2e-fixture.json`; no passwords are stored.
- PASS: Student A completed real password login and TOTP enrollment; Supabase confirms a verified TOTP factor and AAL2 session. Its ordinary session reads only its own fixture record. Direct access to Student B's record, self-promotion to professor, and grade changes were denied.
- PASS: Student A managed refresh retained identity, AAL2 and database authorization. Browser reload and a second portal tab restored access. Global logout returned both tabs to login; Back and reload did not reopen protected pages. Replayed access and refresh tokens failed to regain protected access.
- PASS: Student B completed password login. All seven pre-MFA checks passed: AAL1, no authorized role, no reads from any portal table, and no direct record-ID bypass. The protected grades route still renders the MFA screen.
- PASS: Student B completed TOTP with an AAL2 student session. Its own-record read succeeded; Student A direct-ID reads, grade changes and role promotion were denied. Refresh preserved its authorization; global logout cleared the shared session and replayed access/refresh tokens could not restore access.
- PASS: Professor password login also passed all seven pre-MFA checks. Its assigned professor role does not grant database access until MFA; direct navigation to grades still shows the MFA screen.
- PASS: Professor TOTP produced AAL2 and the assigned professor role. Both records in its offering were readable; an assigned grade update succeeded and was restored. An unknown/unassigned offering was denied by the authorization helper. Refresh and global logout/access-token/refresh-token replay checks passed.
- PASS: Independent database inspection after the write test confirmed the original record values `72` and `84`, the original three role assignments, and the single migration's source/history SHA-256 `ef617d25c8837e4d8747432e2d9caf0dde80b00e005d339535bd23b8008d4a11`.
- PASS: Professor repeat password login reused its single verified TOTP factor, remained AAL1, and rendered a code challenge without new enrollment. Submitting the known incorrect test value `000000` displayed `Verification failed. Use a fresh code and check your device time.` The seven pre-MFA denial checks also passed after the first incorrect attempt. Evidence: `results/incorrect-totp-rejected.png` (only the known invalid test value, no real code or secret).
- PASS: The existing professor authenticator accepted a fresh valid code after the negative test. Server user validation, AAL2 and the professor role were confirmed, and the workspace opened. Final logout and all four replay checks passed again. A final administrative read confirmed zero remaining sessions for the three test accounts, one verified TOTP factor per account, and unchanged grades `72` and `84`.
- Evidence: `results/e2e-auth-results.json` contains 76 PASS entries and zero FAIL entries, comprising 72 assertions plus four session-capture setup entries. Browser observations and independent database checks are summarized in `results/AUTH_ACCEPTANCE_REPORT.md`. Screenshots contain no real credentials; the incorrect-code screenshot contains only the known invalid test value.
- Manual authentication steps are complete. Fictional accounts, verified factors and test records remain available for repeat testing; all sessions are signed out. The runner tab was closed and the portal was left at login.

MCP lacks an Auth account-provisioning tool. Automatic approval review blocked retrieving admin API keys without explicit authorization. Using the normal Supabase Auth dashboard avoids that credential retrieval. No admin/service-role key was read. Browser-control rules require the user to enter new account passwords. The existing application's UI and the desktop application have not been changed in this testing phase.
