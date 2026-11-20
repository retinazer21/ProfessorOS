import { createContext, useContext, useEffect, useLayoutEffect, useState, type CSSProperties, type ReactNode } from 'react';
import { MotionConfig } from 'motion/react';
import catalog from '../../src/main/resources/appearance.tsv?raw';

const [header, ...rows] = catalog.trim().split(/\r?\n/).map(line => line.split('\t'));
export const themes = rows.map(row => Object.fromEntries(header.map((key, i) => [key, row[i]])));
const key = 'professoros.portal.appearance.v1';
type Preference = { theme: string; accent: string; reduced: boolean };
const defaults: Preference = { theme: 'SYSTEM', accent: 'accent', reduced: false };
function load(): Preference {
    try {
        const value = JSON.parse(localStorage.getItem(key) ?? '{}');
        return {
            theme: themes.some(t => t.id === value?.theme) ? value.theme : 'SYSTEM',
            accent: ['info', 'positive'].includes(value?.accent) ? value.accent : 'accent',
            reduced: value?.reduced === true,
        };
    } catch { return defaults; }
}
function useMedia(query: string) {
    const [matches, setMatches] = useState(() => matchMedia(query).matches);
    useEffect(() => {
        const media = matchMedia(query);
        const update = () => setMatches(media.matches);
        update(); media.addEventListener('change', update);
        return () => media.removeEventListener('change', update);
    }, [query]);
    return matches;
}
const Context = createContext<{ pref: Preference; update: (value: Partial<Preference>) => void; reduced: boolean; error: boolean } | null>(null);
function useAppearance() {
    const context = useContext(Context);
    if (!context) throw new Error('AppearanceProvider is required');
    return context;
}
export function useMotionPreference() { return useAppearance().reduced; }

export function AppearanceProvider({ children }: { children: ReactNode }) {
    const [pref, setPref] = useState(load);
    const [error, setError] = useState(false);
    const dark = useMedia('(prefers-color-scheme: dark)');
    const osReduced = useMedia('(prefers-reduced-motion: reduce)');
    const reduced = osReduced || pref.reduced;
    const selected = themes.find(t => t.id === pref.theme) ?? themes.find(t => t.id === (dark ? 'DARK' : 'LIGHT'))!;
    useLayoutEffect(() => {
        const root = document.documentElement;
        root.dataset.appearance = selected.id;
        root.dataset.motion = reduced ? 'reduced' : 'full';
        root.style.colorScheme = selected.scheme;
        for (const role of header.slice(3)) {
            root.style.setProperty('--' + role, selected[role === 'accent' ? pref.accent : role]);
        }
        root.style.setProperty('--chart-accent', selected.accent);
        root.style.setProperty('--on-accent', selected.scheme === 'dark' ? '#101114' : '#ffffff');
    }, [selected, pref.accent, reduced]);
    useEffect(() => {
        const sync = (e: StorageEvent) => { if (e.key === key || e.key === null) setPref(load()); };
        window.addEventListener('storage', sync);
        return () => window.removeEventListener('storage', sync);
    }, []);
    function update(value: Partial<Preference>) {
        const next = { ...pref, ...value };
        setPref(next);
        try { localStorage.setItem(key, JSON.stringify(next)); setError(false); }
        catch { setError(true); }
    }
    return <Context.Provider value={{ pref, update, reduced, error }}><MotionConfig reducedMotion={reduced ? 'always' : 'never'}>{children}</MotionConfig></Context.Provider>;
}

export function AppearanceGallery({ inProfile = false }: { inProfile?: boolean }) {
    const { pref, update, error } = useAppearance();
    const controls = <>
        <fieldset><legend>Workspace theme</legend>
            <label className="appearance-option"><input type="radio" name="appearance" checked={pref.theme === 'SYSTEM'} onChange={() => update({ theme: 'SYSTEM' })} /> Follow system appearance</label>
            <div className="theme-grid">{themes.map(t => <label className="theme-sample" key={t.id} style={{ '--sample': t.panel, '--ink': t.text, '--stripe': t.accent, '--rule': t.border, '--chart': t.positive } as CSSProperties}>
                <input type="radio" name="appearance" checked={pref.theme === t.id} onChange={() => update({ theme: t.id })} />
                <span aria-hidden="true" className="sample-art"><i/><i/><i/></span><span>{t.name}</span>
            </label>)}</div>
        </fieldset>
        <div className="appearance-options"><label>Accent <select value={pref.accent} onChange={e => update({ accent: e.target.value })}><option value="accent">Theme default</option><option value="info">Blue</option><option value="positive">Green</option></select></label>
        <label><input type="checkbox" checked={pref.reduced} onChange={e => update({ reduced: e.target.checked })}/> Reduce motion</label></div>
        {error && <p role="status">Appearance changed for this visit. Browser storage is unavailable.</p>}
    </>;
    return inProfile ? <section className="appearance-gallery" aria-labelledby="appearance-heading">
        <h2 id="appearance-heading" tabIndex={-1}>Appearance</h2>{controls}
    </section> : <details className="appearance-gallery"><summary>Appearance</summary>{controls}</details>;
}
