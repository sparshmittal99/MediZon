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
            .then(j => { if (j && j.token) { call('link', j.token, j.account); render(); } })
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

    // Invoices: Android's print dialog, which can also save a PDF
    window.print = () => call('print', document.title);

    // ---- Settings: in the app, the website's "Settings" links open the app's own settings screen instead
    // (dark mode, ringing, ringtone, snooze, permissions), so there's one place for them ----
    document.querySelectorAll('[data-mz-settings-link]').forEach(a => {
        a.dataset.mzAppSettings = '';
        a.setAttribute('href', '#');
        const icon = a.querySelector('i');
        a.textContent = 'App settings';
        if (icon) a.prepend(icon);
    });

    // ---- "Dose alarms on this phone" ----
    const path = location.pathname.replace(new URL(base, location.href).pathname, '/');
    if (path === '/settings') { call('openSettings'); history.length > 1 ? history.back() : location.replace(base); }
    const onAlarmPage = account && (path === '/plans' || path === '/notifications/settings');
    let card = null;

    function render() {
        if (!onAlarmPage) return;
        let s;
        try { s = JSON.parse(call('alarmStatus') || '{}'); } catch (e) { s = {}; }
        const missing = [];
        if (s.notifications === false) missing.push('Notifications');
        if (s.exactAlarms === false) missing.push('Alarms & reminders');
        if (s.fullScreen === false) missing.push('Full-screen alerts (to show over the lock screen)');
        if (s.batteryUnrestricted === false) missing.push('Battery: Unrestricted (so the phone doesn\'t stop alarms)');

        if (!card) {
            card = document.createElement('section');
            card.className = 'mz-app-alarms';
            card.setAttribute('aria-live', 'polite');
            const page = document.querySelector('main.mz-page');
            if (!page) return;
            const first = page.firstElementChild;
            page.insertBefore(card, first ? first.nextSibling : null);
        }
        const ok = missing.length === 0;
        card.classList.toggle('is-off', !ok);
        const count = Number(s.alarms || 0);
        const rings = s.ringPhone !== false;
        const settingsBtn = '<button type="button" class="btn btn-sm btn-outline-secondary" data-mz-app-settings>'
            + '<i class="bi bi-gear me-1"></i>App settings</button>';
        card.innerHTML = ok
            ? `<i class="bi ${rings ? 'bi-telephone-inbound' : 'bi-bell'}" aria-hidden="true"></i><div>`
              + `<h2>${rings ? 'Dose alerts ring this phone' : 'Dose alerts come as notifications'}</h2>`
              + `<p>${count === 1 ? '1 alert is' : count + ' alerts are'} set for the next 7 days, even when the app is closed.`
              + (rings ? ' The phone rings with a popup to mark Taken or Skip.' : ' Turn on "Ring phone" in App settings to have it ring like a call.') + '</p>'
              + '<div class="mz-app-alarms-actions"><button type="button" class="btn btn-sm btn-outline-primary" data-mz-app-test>'
              + '<i class="bi bi-bell me-1"></i>Test alert</button>' + settingsBtn + '</div></div>'
            : '<i class="bi bi-exclamation-triangle" aria-hidden="true"></i><div><h2>Set up dose alerts</h2>'
              + '<p>To ring this phone when the app is closed, allow:</p><ul></ul>'
              + '<div class="mz-app-alarms-actions"><button type="button" class="btn btn-sm btn-primary" data-mz-app-fix>Set up alerts</button>'
              + '<button type="button" class="btn btn-sm btn-outline-primary" data-mz-app-test>Test alert</button>' + settingsBtn + '</div></div>';
        const list = card.querySelector('ul');
        if (list) missing.forEach(m => { const li = document.createElement('li'); li.textContent = m; list.appendChild(li); });
    }

    document.addEventListener('click', (e) => {
        if (e.target.closest('[data-mz-app-settings]')) { e.preventDefault(); call('openSettings'); return; }
        if (e.target.closest('[data-mz-app-fix]')) call('fixAlarmSettings');
        if (e.target.closest('[data-mz-app-test]')) {
            call('testAlarm');
            const btn = e.target.closest('[data-mz-app-test]');
            btn.textContent = 'Rings in 10 seconds…';
            btn.disabled = true;
        }
    });

    // The app calls this when coming back from a settings screen
    window.mzAppStatusChanged = render;
    render();
    // The first sync after linking finishes a moment later: refresh the count
    if (onAlarmPage) setTimeout(render, 4000);
})();
