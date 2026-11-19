# Professor OS authentication acceptance — 2026-11-13

Result: PASS for the requested authentication/security scenarios covered below. No authentication bypass was found. Manual password and authenticator interactions are complete.

Project: `bsvrqsqftmaoehlappbv`. Tested the existing Studio Window UI at `http://127.0.0.1:5175`, with Supabase managed Auth and ordinary authenticated REST/SDK requests. Database inspection was used separately to verify provisioning and final state; admin access was not used to prove user authorization.

| Check | Result | Evidence/scope |
| --- | --- | --- |
| Password login and TOTP enrollment | PASS | Two fictional students and one professor; each has exactly one verified TOTP factor. |
| No records before MFA | PASS | All three accounts remained AAL1; four portal tables and direct record-ID requests returned no accessible records. Protected routes displayed MFA. |
| Incorrect TOTP and repeat login | PASS | Professor reused its existing factor. `000000` displayed a verification error; records remained blocked. Fresh valid code restored AAL2 and the assigned professor role. |
| Student isolation and RBAC | PASS | A could read only A's record; B only B's. Cross-student direct-ID access, self-promotion and grade changes were denied. |
| Professor authorization | PASS | Read both assigned records; updated a fixture grade and restored it. Authorization helper denied an unknown/unassigned offering. Earlier rollback SQL regression separately covered real cross-offering insert/update denial. |
| Sessions | PASS | Managed refresh preserved identity, MFA level and role for all three. Student A retained access after reload and in a second tab. |
| Logout and replay | PASS | All accounts' captured access/refresh tokens could not restore protected access after global logout. Student A's second tab returned to login; Back/reload stayed blocked. Final database check: zero test sessions. |
| Anonymous access, fabricated tokens, public signup | PASS | Earlier live HTTP negative suite passed all three tests. Public registration is disabled. |
| Data and migration preservation | PASS | Original fictional grades remain `72` and `84`; roles unchanged. Single applied migration source, history text and recorded checksum match. No desktop code or data changes. |

## Saved evidence

- `e2e-auth-results.json`: 76 PASS entries, zero FAIL entries (72 assertions plus four capture setup entries). Includes timestamps and fictional identifiers, never tokens or passwords.
- `student-a-verified.png`, `student-b-verified.png`, `professor-verified.png`: verified account/role UI.
- `student-b-before-mfa.png`, `professor-before-mfa.png`: protected screens blocked.
- `incorrect-totp-rejected.png`: observed error for the known invalid test value only.
- `student-a-logged-out.png`, `final-signed-out.png`: signed-out UI.
- `../e2e-fixture.json`: retained fictional accounts, offering, enrollments and record IDs. These records are explicitly test-only and were not imported from the UI demo.

## Limits and remaining prerequisites

- Academic pages still show labeled demo content. Real academic publishing, desktop synchronization and the UI data adapter are not implemented. Database security was tested directly against the protected fixtures.
- University production account provisioning, password delivery/reset and lost-authenticator recovery need an approved process; recovery callback screens are not implemented. Fictional `.invalid` accounts do not exercise email delivery.
- Mobile MFA layout, long-duration natural token expiry, network interruption and a live second-professor cross-offering browser scenario were not exercised. Explicit refresh and earlier transactional revoked/expired-session and cross-offering SQL checks passed.
- Earlier validation passed eight access tests, three live HTTP negative tests, TypeScript and the production build. Maven/Compose checks were attempted but blocked by missing JDK 25/JAVA_HOME. Graphify is unavailable and this checkout has no graph. The frontend build has a non-fatal chunk-size warning.
- No new authentication defect requiring a product-code change was found during this interactive run. The existing UI and desktop application were preserved. All test sessions were signed out; test accounts/factors/records were retained for repeat testing.

Migration SHA-256: `ef617d25c8837e4d8747432e2d9caf0dde80b00e005d339535bd23b8008d4a11`. The previously approved history-text-only repair remains intact; no applied file, checksum, schema or migration version was changed during acceptance testing.
