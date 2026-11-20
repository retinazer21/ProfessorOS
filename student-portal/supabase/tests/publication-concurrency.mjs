import assert from 'node:assert/strict';

export async function verifyPublicationConcurrency(db, { signIn, sessions, offering, student }) {
    const read = async () => {
        const result = await db.query(
            'select score, letter, published_at, revision from public.portal_published_grades where offering_id=$1 and student_id=$2',
            [offering, student],
        );
        assert.equal(result.rows.length, 1);
        return result.rows[0];
    };
    const publish = (revision, score) => db.query(
        'select public.desktop_publish_grade($1,$2,$3,$4,$5) as revision',
        [offering, student, revision, score, 'C'],
    );

    await signIn(sessions[0]);
    const original = await read();
    assert.equal(Number(original.score), 72);
    await signIn(sessions[1]);
    const stale = await read();
    assert.deepEqual(stale, original);
    await signIn(sessions[0]);
    const fresh = (await publish(original.revision, 78)).rows[0].revision;
    assert.notEqual(fresh, original.revision);
    await signIn(sessions[1]);
    await assert.rejects(publish(stale.revision, 74), { code: 'PT409' });
    await assert.rejects(publish(null, 74), { code: 'PT409' });
    await assert.rejects(db.query(
        'update public.portal_published_grades set score=74 where offering_id=$1 and student_id=$2',
        [offering, student],
    ), { code: '42501' });
    await assert.rejects(db.query(
        `insert into public.portal_published_grades(offering_id,student_id,score,letter)
         values($1,$2,74,'C') on conflict(offering_id,student_id) do update set score=excluded.score`,
        [offering, student],
    ), { code: '42501' });
    const current = await read();
    assert.equal(Number(current.score), 78);
    assert.equal(current.revision, fresh);
    const reviewed = (await publish(current.revision, 74)).rows[0].revision;
    assert.notEqual(reviewed, fresh);
    assert.equal(Number((await read()).score), 74);
    console.log('PASS 72→78, stale 74 rejected (PT409), legacy writes denied; reviewed 74 succeeds.');
}
