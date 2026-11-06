/*
 * Dark mode, applied before the page paints so there's no flash. On the website it's chosen in Settings (or, signed
 * out, in the footer) and kept in this browser (localStorage "mz_prefs"). Inside the Android app it comes from the
 * app's own settings, together with text size and reduced motion (window.MediZonApp).
 */
(() => {
    const KEY = 'mz_prefs';
    const root = document.documentElement;
    const app = window.MediZonApp;

    const read = () => {
        let saved = {};
        try { saved = JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch (e) { saved = {}; }
        return { theme: saved.theme === 'dark' ? 'dark' : 'light' };
    };
    const fromApp = (fn) => {
        try { return app && typeof app[fn] === 'function' ? app[fn]() : null; } catch (e) { return null; }
    };

    const apply = () => {
        const dark = (fromApp('theme') || read().theme) === 'dark';
        root.dataset.theme = dark ? 'dark' : 'light';
        if (dark) root.setAttribute('data-bs-theme', 'dark'); else root.removeAttribute('data-bs-theme');
        // App only: text size and reduced motion
        const text = fromApp('textSize');
        if (text === 'sm' || text === 'lg' || text === 'xl') root.dataset.text = text; else delete root.dataset.text;
        if (fromApp('reduceMotion') === true) root.dataset.motion = 'reduce'; else delete root.dataset.motion;
    };

    const save = (changes) => {
        const next = Object.assign(read(), changes);
        try { localStorage.setItem(KEY, JSON.stringify(next)); } catch (e) { /* private mode: this page only */ }
        apply();
        return next;
    };

    apply();
    // App only: a page that refreshed itself (app.js) skips the fade-in, so the refresh is hardly noticed
    if (app) {
        try {
            if (sessionStorage.getItem('mz_soft') === location.href) root.classList.add('mz-soft');
        } catch (e) { /* no storage: a normal fade */ }
    }
    window.addEventListener('storage', (e) => { if (e.key === KEY) apply(); });   // other tabs
    // Paper is white: print (invoices) in light colours, then go back to dark
    window.addEventListener('beforeprint', () => {
        root.dataset.theme = 'light';
        root.removeAttribute('data-bs-theme');
    });
    window.addEventListener('afterprint', apply);
    window.mzApplyTheme = apply;
    window.mzPrefs = { read, save };
})();
