/*
 * Only loaded inside the MediZon Android app. Talks to the app through window.MediZonApp:
 * links this phone for dose alarms, keeps them in sync, unlinks on sign-out, prints invoices natively,
 * and shows whether alarms can ring on My medicines and the alert settings page.
 */
(() => {
    const app = window.MediZonApp;
    if (!app) return;

    const meta = (name) => document.querySelector(`meta[name="${name}"]`)?.content || '';
    const base = meta('mz_base') || '/';
    const account = meta('mz_account');
    const post = (path) => fetch(base + path, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { [meta('_csrf_header') || 'X-CSRF-TOKEN']: meta('_csrf'), Accept: 'application/json' }
    });
    const call = (fn, ...args) => { try { return app[fn](...args); } catch (e) { return null; } };

    // Link once per account (a new token each time); afterwards just ask for a sync, which the app throttles.
    // Being signed out of the website (an expired session) doesn't stop alarms; only "Sign out" does.
    if (account && call('linkedAccount') !== account) {
        post('api/app/link')
            .then(r => (r.ok ? r.json() : null))
            .then(j => { if (j && j.token) call('link', j.token, j.account); })
            .catch(() => {});
    } else if (account) {
        call('syncNow');
    }

    // Sign out: the token stops working on the server and the phone forgets its alarms
    document.addEventListener('submit', (e) => {
        const form = e.target;
        if (!(form instanceof HTMLFormElement) || form.dataset.mzUnlinked) return;
        if (!/\/logout$/.test(new URL(form.action, location.href).pathname)) return;
        e.preventDefault();
        form.dataset.mzUnlinked = '1';
        const done = () => { call('unlink'); form.submit(); };
        post('api/app/unlink').then(done, done);
    }, true);

    // Language: chosen in App settings only. Applied through the same Google Translate cookie the website uses;
    // the page reloads once when it changes (only if the cookie took, so it can't loop).
    const cookieLang = () => {
        const m = document.cookie.match(/(?:^|;\s*)googtrans=\/[^/;]*\/([^;]+)/);
        return m ? decodeURIComponent(m[1]) : 'en';
    };
    const syncLanguage = () => {
        const want = call('language') || 'en';
        if (want === cookieLang()) return;
        const host = location.hostname;
        ['', host, '.' + host].forEach(d => { document.cookie = 'googtrans=; path=/; max-age=0' + (d ? '; domain=' + d : ''); });
        if (want !== 'en') document.cookie = 'googtrans=/en/' + want + '; path=/; max-age=31536000; samesite=lax';
        if (cookieLang() === want) location.reload();
    };
    // The app calls mzApplyTheme when coming back from its settings: re-read dark mode, text size and language
    const applyTheme = window.mzApplyTheme;
    window.mzApplyTheme = () => { if (applyTheme) applyTheme(); syncLanguage(); };
    syncLanguage();

    // Keyboard open: hide the tab bar so it stays under the keyboard instead of riding on top of it
    const typing = (el) => el && (el.tagName === 'TEXTAREA' || el.isContentEditable || el.tagName === 'SELECT'
        || (el.tagName === 'INPUT' && !['checkbox', 'radio', 'file', 'button', 'submit', 'reset', 'range', 'color', 'hidden'].includes(el.type)));
    const keyboard = () => document.documentElement.classList.toggle('mz-kb', typing(document.activeElement));
    document.addEventListener('focusin', keyboard);
    document.addEventListener('focusout', () => setTimeout(keyboard, 0));
    // Closing the keyboard with Back keeps focus in the field: show the bar again when the viewport grows back
    if (window.visualViewport) {
        let tallest = window.visualViewport.height;
        window.visualViewport.addEventListener('resize', () => {
            const h = window.visualViewport.height;
            tallest = Math.max(tallest, h);
            if (h > tallest * 0.85) document.documentElement.classList.remove('mz-kb');
            else if (typing(document.activeElement)) document.documentElement.classList.add('mz-kb');
        });
    }

    // ---- Alerts as short pop-ups, like the "Code sent" one: a tick, cross or bell, gone in about two seconds ----
    // Messages after an action (data-mz-popup), new-notification toasts and alert() all show this way in the app.
    const POPUP_MS = 1800;
    const queue = [];
    let showing = false;
    const ICONS = {
        success: '<svg class="mz-pay-tick" viewBox="0 0 52 52" aria-hidden="true"><circle cx="26" cy="26" r="24"/><path d="M15 27 l7 7 l15 -16"/></svg>',
        error: '<svg class="mz-pay-cross" viewBox="0 0 52 52" aria-hidden="true"><circle cx="26" cy="26" r="24"/><path d="M18 18 l16 16 M34 18 l-16 16"/></svg>',
        info: '<span class="mz-pop-ico" aria-hidden="true"><i class="bi bi-bell-fill"></i></span>',
        warning: '<span class="mz-pop-ico is-warn" aria-hidden="true"><i class="bi bi-exclamation-lg"></i></span>'
    };
    const TITLES = { success: 'Done!', error: 'Oops!', info: 'Heads up', warning: 'Heads up' };
    const popup = (kind, message, title, link) => {
        if (!message) return;
        queue.push({ kind, message, title, link });
        if (!showing) next();
    };
    function next() {
        const item = queue.shift();
        if (!item) { showing = false; return; }
        showing = true;
        const overlay = document.createElement('div');
        overlay.className = 'mz-custom-alert-overlay mz-pop';
        overlay.setAttribute('role', item.kind === 'error' ? 'alert' : 'status');
        overlay.innerHTML = '<div class="mz-custom-alert-card">' + (ICONS[item.kind] || ICONS.info)
            + '<h2 class="h4 mt-3 mb-1"></h2><p class="small text-muted mt-2 mb-0"></p></div>';
        overlay.querySelector('h2').textContent = item.title || TITLES[item.kind] || TITLES.info;
        overlay.querySelector('p').textContent = item.message;
        document.body.appendChild(overlay);
        let done = false;
        const close = () => {
            if (done) return;
            done = true;
            overlay.style.transition = 'opacity .2s ease';
            overlay.style.opacity = '0';
            setTimeout(() => { overlay.remove(); next(); }, 200);
        };
        // Long messages stay a little longer so they can be read; a tap closes it (or opens a notification's page)
        setTimeout(close, Math.min(3200, POPUP_MS + Math.max(0, item.message.length - 70) * 25));
        overlay.addEventListener('click', () => { close(); if (item.link) location.href = item.link; });
    }
    const kindOf = (el) => el.classList.contains('alert-success') ? 'success'
        : el.classList.contains('alert-danger') ? 'error'
        : el.classList.contains('alert-warning') ? 'warning' : 'info';
    const textOf = (el) => (el.textContent || '').replace(/\s+/g, ' ').trim();

    // Messages on the page: shown once as a pop-up, then taken off the page
    const takePopups = (root) => root.querySelectorAll('[data-mz-popup]:not([hidden])').forEach(el => {
        if (el.dataset.mzPopped === textOf(el)) return;
        el.dataset.mzPopped = textOf(el);
        popup(kindOf(el), textOf(el));
        el.hidden = true;
    });
    takePopups(document);

    // Shown later by the page: return-form errors, and the toast for a new notification
    new MutationObserver((changes) => {
        for (const c of changes) {
            if (c.type === 'attributes' && c.target.matches('[data-mz-popup]')) {
                if (!c.target.hidden) takePopups(c.target.parentElement || document);
                else delete c.target.dataset.mzPopped;
                continue;
            }
            c.addedNodes.forEach(n => {
                if (!(n instanceof HTMLElement)) return;
                if (n.matches('.mz-toasts .toast')) {
                    const title = n.querySelector('strong')?.textContent || 'New notification';
                    popup('info', n.querySelector('p')?.textContent || '', title, n.querySelector('a')?.getAttribute('href'));
                    n.remove();
                } else if (n.matches('[data-mz-popup]') || n.querySelector('[data-mz-popup]')) {
                    takePopups(n.parentElement || document);
                }
            });
        }
    }).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['hidden'] });

    // alert() from any page script: the same pop-up instead of the browser's box
    window.alert = (message) => popup('info', String(message ?? ''));

    // ---- Feedback the moment a page is requested ----
    // The app's own loading bar only starts once the server begins answering; for a form (Sign in, Save...) the
    // whole wait showed nothing, so it looked frozen. Show a bar at once, and a spinner on the button that was used.
    let busyBar = null;
    const busy = (button) => {
        if (!busyBar) {
            busyBar = document.createElement('div');
            busyBar.className = 'mz-app-busy';
            busyBar.setAttribute('role', 'progressbar');
            busyBar.setAttribute('aria-label', 'Loading');
            document.body.appendChild(busyBar);
        }
        busyBar.hidden = false;
        clearTimeout(busy.timer);
        busy.timer = setTimeout(idle, 30000);   // a download, or a page that never came: don't leave it spinning
        if (button && !button.classList.contains('is-busy')) {
            button.classList.add('is-busy');
            button.insertAdjacentHTML('afterbegin', '<span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>');
        }
    };
    const idle = () => {
        if (busyBar) busyBar.hidden = true;
        document.querySelectorAll('.is-busy').forEach(b => { b.classList.remove('is-busy'); b.querySelector('.spinner-border')?.remove(); });
    };
    // Forms that really navigate (not ones a script handles itself, or that open in a new window)
    document.addEventListener('submit', (e) => {
        const form = e.target;
        setTimeout(() => {
            if (e.defaultPrevented || form.target === '_blank' || form.closest('[data-mz-no-busy]')) return;
            busy(e.submitter || form.querySelector('button[type=submit], button:not([type])'));
        }, 0);
    });
    // Links to another page of the site
    document.addEventListener('click', (e) => {
        const a = e.target.closest('a[href]');
        if (!a || e.defaultPrevented || a.target === '_blank' || a.hasAttribute('download') || a.dataset.bsToggle) return;
        const url = new URL(a.href, location.href);
        if (url.origin !== location.origin || (url.pathname === location.pathname && url.search === location.search)) return;
        setTimeout(() => { if (!e.defaultPrevented) busy(null); }, 0);
    });
    // Back from the next page (or a form that didn't navigate after all): clear it
    window.addEventListener('pageshow', idle);
    window.addEventListener('mzIdle', idle);

    // Invoices: Android's print dialog, which can also save a PDF
    window.print = () => call('print', document.title);

    // ---- Settings: in the app, the website's "Settings" links open the app's own settings screen instead
    // (dark mode, ringing, ringtone, snooze, permissions), so there's one place for them ----
    document.querySelectorAll('[data-mz-settings-link]').forEach(a => {
        a.dataset.mzAppSettings = '';
        a.setAttribute('href', '#');
        const label = a.querySelector('span');
        if (label) { label.textContent = 'App settings'; return; }      // a row on the "You" page
        const icon = a.querySelector('i');
        a.textContent = 'App settings';
        if (icon) a.prepend(icon);
    });

    // /settings in the app: its own settings screen instead (dark mode, ringing, ringtone, permissions, test alert)
    const path = location.pathname.replace(new URL(base, location.href).pathname, '/');
    if (path === '/settings') { call('openSettings'); history.length > 1 ? history.back() : location.replace(base); }

    document.addEventListener('click', (e) => {
        if (e.target.closest('[data-mz-app-settings]')) { e.preventDefault(); call('openSettings'); }
    });
})();
