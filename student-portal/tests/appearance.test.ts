import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';

const [header, ...rows] = readFileSync(new URL('../../src/main/resources/appearance.tsv', import.meta.url), 'utf8')
    .trim().split(/\r?\n/).map(row => row.split('\t'));
const themes = rows.map(row => Object.fromEntries(header.map((key, i) => [key, row[i]])));
function luminance(hex: string) {
    const [r, g, b] = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255)
        .map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4);
    return .2126 * r + .7152 * g + .0722 * b;
}
test('shared catalog provides ten distinct, complete palettes', () => {
    assert.equal(new Set(themes.map(t => t.id)).size, 10);
    assert.equal(new Set(themes.map(t => t.bg)).size, 10);
    for (const t of themes) {
        assert.ok(['light', 'dark'].includes(t.scheme));
        for (const role of header.slice(3)) assert.match(t[role], /^#[\da-f]{6}$/i);
    }
});
test('text, chart labels and alternate accents meet AA on reading surfaces', () => {
    for (const t of themes) for (const fg of ['text', 'muted', 'accent', 'positive', 'info', 'warning', 'danger']) {
        for (const bg of ['bg', 'panel', 'sidebar', 'raised', 'selected', 'accentSurface']) {
            const [light, dark] = [luminance(t[fg]), luminance(t[bg])].sort((a,b) => b-a);
            assert.ok((light+.05)/(dark+.05) >= 4.5, t.name + ' ' + fg + ' on ' + bg);
        }
    }
});
