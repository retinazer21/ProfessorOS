begin;
-- Correct only the stored SQL evidence, not the applied migration or its checksum.
-- Verified before repair: audit/20261113-history-before-repair.json (SHA-256
-- 3d22418d9d1853ab53d5515f291f29c431220e0dd9b1fd20ad4f4ec0c3413167).
-- The deployment wrapper's JS replacement string collapsed $$ into $, and the
-- Windows clipboard converted LF to CRLF inside the history literal. Executed
-- SQL was unaffected. Live RLS regression tests passed before this repair.
create table supabase_migrations.portal_history_repair_audit (
    version text primary key,
    original_entry jsonb not null,
    reason text not null,
    recorded_at timestamptz not null default now()
);
alter table supabase_migrations.portal_history_repair_audit enable row level security;
revoke all on supabase_migrations.portal_history_repair_audit from public, anon, authenticated;

do $repair$
declare
    original supabase_migrations.schema_migrations%rowtype;
    restored text;
begin
    select * into strict original from supabase_migrations.schema_migrations
    where version = '202611130001' for update;
    if original.name <> 'portal_access_sha256_ef617d25c8837e4d8747432e2d9caf0dde80b00e005d339535bd23b8008d4a11'
       or encode(sha256(convert_to(original.statements[1], 'UTF8')), 'hex') <>
          '0ccdc2802d28c7d67e69b503d3b8ab134e8edcc0780197d70f3596f6daf7bdbb' then
        raise exception 'History changed since verified backup; stop and investigate';
    end if;
    restored := replace(replace(original.statements[1], chr(13), ''), '$', '$$');
    if encode(sha256(convert_to(restored, 'UTF8')), 'hex') <>
       'ef617d25c8837e4d8747432e2d9caf0dde80b00e005d339535bd23b8008d4a11' then
        raise exception 'Restored evidence does not match the immutable source';
    end if;
    insert into supabase_migrations.portal_history_repair_audit(version, original_entry, reason)
    values (original.version, to_jsonb(original),
        'Restore SQL evidence damaged by JS replacement-dollar escaping and clipboard CRLF; source and checksum unchanged.');
    update supabase_migrations.schema_migrations set statements = array[restored]
    where version = original.version;
end
$repair$;
commit;
