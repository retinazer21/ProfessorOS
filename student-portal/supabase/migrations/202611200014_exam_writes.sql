begin;

create table desktop_private.exam_images (
    digest text primary key check(digest ~ '^[a-f0-9]{64}$'),
    image bytea not null check(octet_length(image) between 1 and 10485760)
);
alter table desktop_private.exam_images enable row level security;
revoke all on desktop_private.exam_images from public, anon, authenticated;
alter table desktop_private.questions add column image_key text references desktop_private.exam_images;
alter table desktop_private.exam_questions add column image_key text references desktop_private.exam_images;
alter table desktop_private.exam_questions drop constraint exam_questions_marks_hundredths_check;
alter table desktop_private.exam_questions add check(marks_hundredths between 1 and 10000000);

create sequence desktop_private.exam_local_id as bigint;
select setval('desktop_private.exam_local_id',greatest(
    coalesce((select max(id) from desktop_private.questions),0),
    coalesce((select max(id) from desktop_private.exams),0),1
));
revoke all on sequence desktop_private.exam_local_id from public, anon, authenticated;

alter function public.desktop_read_workspace() set schema desktop_private;
revoke all on function desktop_private.desktop_read_workspace() from public, anon, authenticated;

create function public.desktop_read_workspace() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
    snapshot jsonb := desktop_private.desktop_read_workspace();
begin
    -- The original result includes the new nullable columns from to_jsonb.
    -- Send only asset references. No local paths or large binary payloads in the workspace.
    snapshot := jsonb_set(snapshot,'{questions}',coalesce((
        select jsonb_agg((value-'image_key') || jsonb_build_object('image_path',coalesce(value->>'image_key','')) order by ord)
        from jsonb_array_elements(snapshot->'questions') with ordinality x(value,ord)
    ),'[]'::jsonb));
    return jsonb_set(snapshot,'{exam_questions}',coalesce((
        select jsonb_agg((value-'image_key') || jsonb_build_object('image_path',coalesce(value->>'image_key','')) order by ord)
        from jsonb_array_elements(snapshot->'exam_questions') with ordinality x(value,ord)
    ),'[]'::jsonb));
end;
$$;
revoke all on function public.desktop_read_workspace() from public, anon, authenticated;
grant execute on function public.desktop_read_workspace() to authenticated;

create function public.desktop_read_exam_image(image_digest text) returns text
language plpgsql stable security definer set search_path = '' as $$
begin
    perform public.desktop_read_workspace();
    return (select encode(image,'base64') from desktop_private.exam_images where digest=image_digest
      and (exists(select 1 from desktop_private.questions where image_key=image_digest)
        or exists(select 1 from desktop_private.exam_questions where image_key=image_digest)));
end;
$$;

create function desktop_private.prune_exam_images() returns void
language sql security invoker set search_path = '' as $$
    delete from desktop_private.exam_images i
    where not exists(select 1 from desktop_private.questions q where q.image_key=i.digest)
      and not exists(select 1 from desktop_private.exam_questions q where q.image_key=i.digest)
$$;
revoke all on function desktop_private.prune_exam_images() from public, anon, authenticated;

create function public.desktop_write_question(previous jsonb, desired jsonb, choices jsonb, encoded_image text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    workspace jsonb;
    question_key bigint;
    course_uuid uuid;
    stamp text := to_char(clock_timestamp() at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
    image_data bytea;
    key_text text;
    kind text;
    selected_choice integer;
begin
    if public.portal_access() is distinct from 'professor' then raise exception 'Verified professor required' using errcode='42501'; end if;
    perform 1 from desktop_private.workspace where professor_id=auth.uid() for update;
    if not found then raise exception 'Authorized workspace owner required' using errcode='42501'; end if;
    workspace := public.desktop_read_workspace();
    if previous is not null then
        question_key := (previous->>'id')::bigint;
        if not exists(select 1 from jsonb_array_elements(workspace->'questions') row_value where row_value=previous) then
            raise exception 'Question changed. Refresh before saving.' using errcode='PT409';
        end if;
    end if;
    if desired is null and previous is not null then
        delete from desktop_private.questions where id=question_key;
        perform desktop_private.prune_exam_images();
        return public.desktop_read_workspace();
    end if;
    if jsonb_typeof(desired) is distinct from 'object' or jsonb_typeof(choices) is distinct from 'array' then
        raise exception 'Invalid question draft' using errcode='22023';
    end if;
    select id into course_uuid from academic_import.course where local_id=(desired->>'course_id')::bigint;
    if course_uuid is null or (previous is not null and previous->'course_id' is distinct from desired->'course_id'
       and exists(select 1 from desktop_private.exam_questions q where q.question_id=question_key)) then
        raise exception 'Question course cannot change after use in an exam' using errcode='22023';
    end if;
    kind := desired->>'question_type';
    selected_choice := (desired->>'correct_choice_position')::integer;
    if length(btrim(desired->>'question_text')) not between 1 and 20000 or length(desired->>'topic')>200
       or length(desired->>'answer')>20000 or length(desired->>'explanation')>20000
       or (kind='TRUE_FALSE' and desired->>'answer' not in ('TRUE','FALSE'))
       or (kind='MCQ' and (desired->>'answer'<>'' or jsonb_array_length(choices) not between 2 and 10
            or selected_choice is null or selected_choice not between 1 and jsonb_array_length(choices)))
       or (kind<>'MCQ' and (selected_choice is not null or jsonb_array_length(choices)<>0))
       or exists(select 1 from jsonb_array_elements_text(choices) c where length(btrim(c)) not between 1 and 2000)
       or exists(select 1 from jsonb_array_elements_text(choices) c group by lower(btrim(c)) having count(*)>1) then
        raise exception 'Invalid question details or choices' using errcode='22023';
    end if;
    if encoded_image is not null then
        if length(encoded_image)>13981016 then raise exception 'Image exceeds 10 MB' using errcode='22023'; end if;
        image_data := decode(encoded_image,'base64');
        if substring(image_data from 1 for 8) <> decode('89504e470d0a1a0a','hex')
           and substring(image_data from 1 for 3) <> decode('ffd8ff','hex') then
            raise exception 'Question image must be PNG or JPEG' using errcode='22023';
        end if;
        key_text := encode(sha256(image_data),'hex');
        insert into desktop_private.exam_images(digest,image) values(key_text,image_data) on conflict do nothing;
    elsif desired->>'image_path' <> '' then
        key_text := previous->>'image_path';
        if key_text is null or key_text is distinct from desired->>'image_path' then
            raise exception 'Load the attached image before saving' using errcode='22023';
        end if;
    end if;
    if previous is null then
        question_key := nextval('desktop_private.exam_local_id');
        insert into desktop_private.questions(id,course_id,question_type,question_text,topic,difficulty,marks_hundredths,
            answer,explanation,correct_choice_position,usage_count,last_used_date,created_at,updated_at,image_key)
        values(question_key,course_uuid,kind,btrim(desired->>'question_text'),desired->>'topic',desired->>'difficulty',
            (desired->>'marks_hundredths')::integer,desired->>'answer',desired->>'explanation',selected_choice,0,null,stamp,stamp,key_text);
    else
        delete from desktop_private.question_choices c where c.question_id=question_key;
        update desktop_private.questions set course_id=course_uuid,question_type=kind,question_text=btrim(desired->>'question_text'),
            topic=desired->>'topic',difficulty=desired->>'difficulty',marks_hundredths=(desired->>'marks_hundredths')::integer,
            answer=desired->>'answer',explanation=desired->>'explanation',correct_choice_position=selected_choice,
            updated_at=stamp,image_key=key_text where id=question_key;
    end if;
    insert into desktop_private.question_choices(question_id,position,choice_text)
    select question_key,ord::integer,btrim(value) from jsonb_array_elements_text(choices) with ordinality x(value,ord);
    perform desktop_private.prune_exam_images();
    return public.desktop_read_workspace();
end;
$$;

create function public.desktop_write_exam(previous jsonb, desired jsonb, selection jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    workspace jsonb;
    exam_key bigint;
    value jsonb := coalesce(previous,desired);
    offering_uuid uuid;
    course_uuid uuid;
    old_snapshots jsonb;
    old_choices jsonb;
    entry jsonb;
    snapshot jsonb;
    question_key bigint;
    question_order integer := 0;
    status_name text;
    stamp text := to_char(clock_timestamp() at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
begin
    if public.portal_access() is distinct from 'professor' then raise exception 'Verified professor required' using errcode='42501'; end if;
    perform 1 from desktop_private.workspace where professor_id=auth.uid() for update;
    if not found then raise exception 'Authorized workspace owner required' using errcode='42501'; end if;
    workspace := public.desktop_read_workspace();
    if jsonb_typeof(value) is distinct from 'object' then raise exception 'Invalid exam draft' using errcode='22023'; end if;
    select id,course_id into offering_uuid,course_uuid from academic_import.offering where local_id=(value->>'course_offering_id')::bigint;
    if public.portal_teaches(offering_uuid) is not true then raise exception 'Verified teaching assignment required' using errcode='42501'; end if;
    if previous is not null then
        exam_key := (previous->>'id')::bigint;
        if not exists(select 1 from jsonb_array_elements(workspace->'exams') row_value where row_value=previous) then
            raise exception 'Exam changed. Refresh before saving.' using errcode='PT409';
        end if;
    end if;
    if desired is null then
        if previous->>'status' is distinct from 'DRAFT' then raise exception 'Only a draft can be deleted' using errcode='22023'; end if;
        delete from desktop_private.exams where id=exam_key;
        perform desktop_private.prune_exam_images();
        return public.desktop_read_workspace();
    end if;
    status_name := desired->>'status';
    if status_name in ('FINALIZED','ARCHIVED') then
        if (previous- 'status'-'updated_at') is distinct from (desired-'status'-'updated_at')
           or (status_name='FINALIZED' and previous->>'status' is distinct from 'DRAFT')
           or (status_name='ARCHIVED' and previous->>'status' is distinct from 'FINALIZED') then
            raise exception 'Invalid exam status transition' using errcode='22023';
        end if;
        if status_name='FINALIZED' then
            update desktop_private.questions q set usage_count=q.usage_count+1,
                last_used_date=greatest(q.last_used_date,current_date),updated_at=stamp
            from desktop_private.exam_questions eq where eq.exam_id=exam_key and eq.question_id=q.id;
            if not found then raise exception 'Add at least one question before finalizing' using errcode='22023'; end if;
        end if;
        update desktop_private.exams set status=status_name,updated_at=stamp where id=exam_key;
        return public.desktop_read_workspace();
    end if;
    if status_name is distinct from 'DRAFT' or (previous is not null and previous->>'status' is distinct from 'DRAFT')
       or (previous is not null and previous->'course_offering_id' is distinct from desired->'course_offering_id')
       or length(btrim(desired->>'title')) not between 1 and 200 or length(desired->>'instructions')>20000
       or desired->>'exam_date' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
       or jsonb_typeof(selection) is distinct from 'array' or jsonb_array_length(selection)>100 then
        raise exception 'Invalid draft exam details' using errcode='22023';
    end if;
    if previous is null then
        exam_key := nextval('desktop_private.exam_local_id');
        insert into desktop_private.exams values(exam_key,offering_uuid,btrim(desired->>'title'),(desired->>'exam_date')::date,
            (desired->>'duration_minutes')::integer,(desired->>'intended_marks_hundredths')::integer,
            desired->>'instructions','DRAFT',stamp,stamp);
    else
        select jsonb_object_agg(q.question_id::text,to_jsonb(q)) into old_snapshots from desktop_private.exam_questions q where q.exam_id=exam_key;
        select jsonb_agg(to_jsonb(c)) into old_choices from desktop_private.exam_question_choices c where c.exam_id=exam_key;
        update desktop_private.exams set title=btrim(desired->>'title'),exam_date=(desired->>'exam_date')::date,
            duration_minutes=(desired->>'duration_minutes')::integer,intended_marks_hundredths=(desired->>'intended_marks_hundredths')::integer,
            instructions=desired->>'instructions',updated_at=stamp where id=exam_key;
        delete from desktop_private.exam_questions where exam_id=exam_key;
    end if;
    for entry in select x.item from jsonb_array_elements(selection) x(item) loop
        question_order := question_order+1;
        question_key := (entry->>'question_id')::bigint;
        if not exists(select 1 from desktop_private.questions q where q.id=question_key and q.course_id=course_uuid) then
            raise exception 'All questions must belong to the exam course' using errcode='22023';
        end if;
        snapshot := old_snapshots->(question_key::text);
        if snapshot is null then
            if not exists(select 1 from jsonb_array_elements(workspace->'questions') row_value where row_value=entry->'source'
                          and (row_value->>'id')::bigint=question_key) then
                raise exception 'Selected question changed. Refresh before saving.' using errcode='PT409';
            end if;
            select to_jsonb(q) into snapshot from desktop_private.questions q where q.id=question_key;
        end if;
        insert into desktop_private.exam_questions(exam_id,question_id,position,question_type,question_text,topic,difficulty,
            marks_hundredths,answer,explanation,image_key)
        values(exam_key,question_key,question_order,snapshot->>'question_type',snapshot->>'question_text',
            snapshot->>'topic',snapshot->>'difficulty',(entry->>'marks_hundredths')::integer,snapshot->>'answer',
            snapshot->>'explanation',snapshot->>'image_key');
        if old_snapshots ? question_key::text then
            insert into desktop_private.exam_question_choices select c.* from jsonb_populate_recordset(null::desktop_private.exam_question_choices,old_choices) c
            where c.question_id=question_key;
        else
            insert into desktop_private.exam_question_choices
            select exam_key,question_key,c.position,c.choice_text,case when c.position=(snapshot->>'correct_choice_position')::integer then 1 else 0 end
            from desktop_private.question_choices c where c.question_id=question_key;
        end if;
    end loop;
    perform desktop_private.prune_exam_images();
    return public.desktop_read_workspace();
end;
$$;

revoke all on function public.desktop_read_exam_image(text),public.desktop_write_question(jsonb,jsonb,jsonb,text),
    public.desktop_write_exam(jsonb,jsonb,jsonb) from public, anon, authenticated;
grant execute on function public.desktop_read_exam_image(text),public.desktop_write_question(jsonb,jsonb,jsonb,text),
    public.desktop_write_exam(jsonb,jsonb,jsonb) to authenticated;
notify pgrst, 'reload schema';
commit;
