select jsonb_build_object(
'history_name',m.name,
'history_statement_sha256',encode(sha256(convert_to(m.statements[1],'UTF8')),'hex'),
'history_other_fields',to_jsonb(m)-'statements',
'history_rows',(select count(*) from supabase_migrations.schema_migrations),
'other_history_sha256',encode(sha256(convert_to((select coalesce(jsonb_agg(to_jsonb(h) order by h.version),'[]'::jsonb)::text from supabase_migrations.schema_migrations h where version<>'202611130001'),'UTF8')),'hex'),
'application_data_sha256',encode(sha256(convert_to(jsonb_build_object('portal_roles', (select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb) from public.portal_roles t),
'portal_offerings', (select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb) from public.portal_offerings t),
'portal_enrollments', (select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb) from public.portal_enrollments t),
'portal_records', (select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb) from public.portal_records t))::text,'UTF8')),'hex'),
'catalog_sha256',encode(sha256(convert_to(jsonb_build_object(
'columns',(select jsonb_agg(to_jsonb(c) order by c.table_schema,c.table_name,c.ordinal_position) from information_schema.columns c where c.table_schema in ('public','supabase_migrations')),
'policies',(select jsonb_agg(to_jsonb(p) order by p.schemaname,p.tablename,p.policyname) from pg_policies p where p.schemaname in ('public','supabase_migrations')),
'functions',(select jsonb_agg(pg_get_functiondef(p.oid) order by p.proname) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname like 'portal_%'),
'constraints',(select jsonb_agg(pg_get_constraintdef(c.oid) order by c.conname) from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname in ('public','supabase_migrations')),
'privileges',(select jsonb_agg(to_jsonb(g) order by g.table_schema,g.table_name,g.grantee,g.privilege_type) from information_schema.table_privileges g where g.table_schema in ('public','supabase_migrations')),
'relations',(select jsonb_agg(jsonb_build_object('name',c.relname,'rls',c.relrowsecurity,'force_rls',c.relforcerowsecurity,'kind',c.relkind) order by c.relname) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname in ('public','supabase_migrations'))
)::text,'UTF8')),'hex'),
'history_custom_triggers',(select count(*) from pg_trigger where tgrelid='supabase_migrations.schema_migrations'::regclass and not tgisinternal),
'history_custom_rules',(select count(*) from pg_rewrite where ev_class='supabase_migrations.schema_migrations'::regclass and rulename<>'_RETURN')
) as repair_snapshot from supabase_migrations.schema_migrations m where version='202611130001';
