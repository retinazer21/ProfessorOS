# Approved migration history text repair — 2026-11-13

Status: committed and verified. The user explicitly approved a one-time exception limited to restoring damaged SQL text, with no changes to applied migration files, checksums, schema or application data.

## Exact database change

One row in `supabase_migrations.schema_migrations`, version `202611130001`: restored only `statements[1]` from the immutable source file, using base64 decoding to avoid replacement-string and clipboard corruption. Restored six `$$` delimiters and 91 original LF line endings. The stored text changed from 5644 to 5559 UTF-8 bytes.

- Previous stored-text SHA-256: `0ccdc2802d28c7d67e69b503d3b8ab134e8edcc0780197d70f3596f6daf7bdbb`.
- Restored stored-text/source SHA-256: `ef617d25c8837e4d8747432e2d9caf0dde80b00e005d339535bd23b8008d4a11`.
- Recorded checksum in the history name, version, other fields and applied source file: unchanged.
- History row count: 1 before and after. No new migration or audit table was created.

## Backup and transaction

The affected history entry was backed up to [20261113-history-before-repair.json](20261113-history-before-repair.json). Its SHA-256 was verified before execution and again afterward: `3d22418d9d1853ab53d5515f291f29c431220e0dd9b1fd20ad4f4ec0c3413167`.

The approved script is [20261113-approved-history-text-repair.sql](20261113-approved-history-text-repair.sql). It uses BEGIN/COMMIT, locks the target row, compares the full row with the backup, rejects unexpected triggers/rules, validates the canonical source against the unchanged recorded checksum, updates exactly one array element, and verifies the row and SHA-256 before COMMIT. Any failed assertion raises an exception and aborts the transaction. All assertions passed; no unexpected discrepancy occurred.

An earlier proposal was superseded without execution and archived locally.

## Independent verification

An independent post-commit SELECT returned `integrity_verified = true`. [Before](20261113-integrity-before.json) and [after](20261113-integrity-after.json) snapshots confirm identical catalog fingerprints (portal/history columns, constraints, policies, function definitions, privileges and relation/RLS flags), identical portal application-data fingerprints, identical other-history fingerprints and identical non-statement metadata. The immutable source and backup were hashed again locally and are unchanged.

The executed repair contained no DDL or application-data writes. This resolves the history integrity blocker only; the account-provisioning and full MFA/logout acceptance-testing prerequisites from AUTH_SETUP.md remain.
