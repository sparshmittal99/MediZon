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
    } else if (call('linkedAccount')) {
        // Signed out here, maybe from another device or by the admin: a sync finds out, and drops the alarms if so
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
        // data-mz-popup-text / -title / -link: shorter wording for the pop-up, its heading, and where a tap goes
        popup(kindOf(el), el.dataset.mzPopupText || textOf(el), el.dataset.mzPopupTitle, el.dataset.mzPopupLink);
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
        // No spinner on search buttons or the coupon buttons (Apply / Remove on the coupons screen, Remove on
        // checkout): the top bar is enough there
        if (button && !button.classList.contains('is-busy')
                && !button.closest('[role=search], .mz-coupons-page, .mz-coupon-remove')) {
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

    // ---- Header colour, and the status bar above it ----
    // Every page tells the app its header colour so the status bar matches. On the home page the header takes the
    // colour of the banner showing and changes as the banners slide; scrolled down it's back to the brand green.
    const root = document.documentElement;
    const nav = document.querySelector('.mz-nav');
    const home = document.body.classList.contains('mz-home') && nav;
    const phone = window.matchMedia('(max-width: 991.98px)');
    const brandGreen = () => (root.dataset.theme === 'dark' ? '#0A2A23' : '#0B3B31');
    const toHex = (css) => {
        const m = String(css || '').match(/rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+))?/);
        if (!m) return /^#[0-9a-f]{6}$/i.test(String(css).trim()) ? String(css).trim() : null;
        if (m[4] !== undefined && Number(m[4]) === 0) return null;   // transparent
        return '#' + [m[1], m[2], m[3]].map(n => Number(n).toString(16).padStart(2, '0')).join('');
    };
    let bannerColor = null;
    let compact = false;
    let sentColor = null;
    // The banner's colour on the home page at the top; the brand green everywhere else and in the compact bar
    const headColor = () => (home && !compact && bannerColor ? bannerColor : brandGreen());
    const sendColor = (force) => {
        const c = headColor();
        if (!force && c === sentColor) return;
        sentColor = c;
        call('setStatusBarColor', c);
    };

    if (home) {
        // The banner showing: its colour goes on the header (a CSS variable) and the status bar
        const carousel = document.getElementById('mzBanners');
        const varOf = (item, name) => {
            const banner = item && item.querySelector('.mz-banner');
            return banner ? toHex(getComputedStyle(banner).getPropertyValue(name)) : null;
        };
        const showBanner = (item) => {
            const c = varOf(item, '--mz-banner-top');
            if (!c) return;
            bannerColor = c;
            document.body.style.setProperty('--mz-head', c);
            // The search button: a lighter shade that goes with this banner
            const accent = varOf(item, '--mz-banner-accent');
            if (accent) document.body.style.setProperty('--mz-accent', accent);
            sendColor();
        };
        if (carousel) {
            showBanner(carousel.querySelector('.carousel-item.active'));
            // Fires as a slide starts (also for swipes), so header, banner and status bar move together
            carousel.addEventListener('slide.bs.carousel', (e) => showBanner(e.relatedTarget));
        }
    }

    // "<" back beside the search box, like shopping apps: only on a screen opened from another screen in the app.
    // Not on the screens the bottom tabs open (they're starting points, even when you come back to them), a screen
    // opened fresh (app start, a notification), nor home, sign-in, checkout and payment.
    const navBack = document.querySelector('[data-mz-back]');
    if (navBack) {
        document.addEventListener('click', (e) => {
            const tab = e.target.closest && e.target.closest('.mz-tabbar a');
            if (!tab) return;
            try { sessionStorage.setItem('mzTabNav', new URL(tab.href, location.href).pathname); } catch (_) { /* no storage */ }
        }, true);
        const here = path.replace(/\/+$/, '') || '/';      // without the app's base path (above)
        let tabStart = !!(history.state && history.state.mzTabStart);
        try {
            if (sessionStorage.getItem('mzTabNav') === location.pathname) {
                tabStart = true;            // remembered on this history entry, so it holds when Back returns here
                history.replaceState(Object.assign({}, history.state, { mzTabStart: true }), '');
            }
            sessionStorage.removeItem('mzTabNav');
        } catch (_) { /* no storage */ }
        const noBack = here === '/' || /^\/(login|register|forgot-password|verify-code|reset-password|checkout)(\/|$)/.test(here);
        let fromApp = false;
        try { fromApp = !!document.referrer && new URL(document.referrer).origin === location.origin; } catch (_) { /* bad referrer */ }
        if (!noBack && !tabStart && fromApp && history.length > 1) {
            navBack.hidden = false;
            navBack.addEventListener('click', () => history.back());
        }
    }

    // Compact bar: just the search box, with upload prescription beside it. Every screen but home always has it
    // (.mz-nav-mini, from the template so it's there at first paint). Home has the full header at the top and turns compact on scrolling down: the bar is sticky
    // with a negative top, so the brand row scrolls away and only the search box stays. Measured, because text
    // size (App settings) changes the row's height.
    const search = nav && nav.querySelector('.mz-search');
    if (nav && nav.querySelector('.mz-app-rx-li')) nav.classList.add('has-rx');
    if (search && home) {
        let offset = 0;
        const measure = () => {
            if (!search || !phone.matches) { offset = 0; nav.style.removeProperty('--mz-nav-top'); return; }
            const gap = parseFloat(getComputedStyle(nav).paddingBottom) || 8;
            offset = Math.max(0, Math.round(search.getBoundingClientRect().top - nav.getBoundingClientRect().top - gap));
            nav.style.setProperty('--mz-nav-top', -offset + 'px');
        };
        const onScroll = () => {
            const next = offset > 0 && window.scrollY >= offset - 1;
            if (next === compact) return;
            compact = next;
            nav.classList.toggle('is-compact', compact);
            sendColor();
        };
        // Measure only at the top (when not compact), so the moved prescription button doesn't skew it
        const remeasure = () => { if (!compact) measure(); onScroll(); };
        measure();
        onScroll();
        window.addEventListener('scroll', onScroll, { passive: true });
        window.addEventListener('resize', remeasure);
        if (document.fonts && document.fonts.ready) document.fonts.ready.then(remeasure);
    }

    // Voice search: the mic in the search bar opens the phone's voice input (Bridge.voiceSearch); what was heard
    // fills the box and searches. The mic always shows; where voice input can't work (an older app build, or a phone
    // without it) a tap says so and puts the cursor in the box instead.
    const mic = search && search.querySelector('.mz-search-mic');
    if (mic) {
        const voiceOk = () => typeof app.voiceSearch === 'function' && call('canVoiceSearch') === true;
        mic.addEventListener('click', () => {
            if (voiceOk()) { if (window.mzVoice) window.mzVoice.cancel(); call('voiceSearch'); return; }
            const box = search.querySelector('input[name="q"]');
            if (box) box.focus();
            alert(typeof app.voiceSearch === 'function'
                ? "Voice search isn't available on this phone. Type to search instead."
                : 'Update the MediZon app to search by voice.');
        });
    }
    // What the phone heard: the chat's mic (or the search screen's) gets it when it asked, else the search bar searches
    window.mzVoiceResult = (text) => {
        if (window.mzVoice && window.mzVoice.deliver(text)) return;
        const box = search && search.querySelector('input[name="q"]');
        if (!box || !text) return;
        box.value = String(text);
        if (search.requestSubmit) search.requestSubmit(); else search.submit();
    };
    sendColor(true);
    // Dark mode or text size changed in App settings: the header colour and its height may have changed
    const applyTheme2 = window.mzApplyTheme;
    window.mzApplyTheme = () => {
        if (applyTheme2) applyTheme2();
        window.dispatchEvent(new Event('resize'));
        sendColor(true);
    };
    // Back to this page from the next one: the app had the next page's colour
    window.addEventListener('pageshow', (e) => { if (e.persisted) sendColor(true); });

    // ---- Auto refresh, in place of pull-to-refresh ----
    // A page refreshes itself when it may be out of date: back in the app after a while, the connection coming
    // back, or left open untouched for a few minutes. Never while the user is busy with it (typing, a filled-in
    // form, an open dialog, a payment) - the same check the live updates use (medizon.js). Signed-in pages also
    // reload the moment their data changes elsewhere (live updates). The scroll position is kept.
    const SOFT_KEY = 'mz_soft';
    const RESUME_AFTER_MS = 60 * 1000;          // away from the app this long: refresh on return
    const IDLE_AFTER_MS = 5 * 60 * 1000;        // page this old and untouched for a minute: refresh
    const loadedAt = Date.now();
    let touchedAt = Date.now();
    let refreshing = false;

    // This load was an auto refresh: put the page back where it was
    try {
        if (sessionStorage.getItem(SOFT_KEY) === location.href) {
            const y = Number(sessionStorage.getItem(SOFT_KEY + '_y')) || 0;
            sessionStorage.removeItem(SOFT_KEY);
            sessionStorage.removeItem(SOFT_KEY + '_y');
            if (y > 0) {
                const back = () => { if (window.scrollY < 4) window.scrollTo(0, y); };
                back();
                window.addEventListener('load', back, { once: true });
            }
        }
    } catch (e) { /* no storage */ }

    const holdRefresh = () => {
        if (refreshing || document.hidden || !navigator.onLine) return true;
        if (busyBar && !busyBar.hidden) return true;                              // already going somewhere
        if (document.querySelector('.mz-pop, .is-invalid, .alert-danger:not([hidden])')) return true;
        if (/\/(checkout|payment)(\/|$)/.test(path)) return true;                 // never mid-checkout
        try { if (window.mzPageBusy && window.mzPageBusy()) return true; } catch (e) { return true; }
        return false;
    };
    const refresh = () => {
        if (holdRefresh()) return false;
        refreshing = true;
        try {
            sessionStorage.setItem(SOFT_KEY, location.href);
            sessionStorage.setItem(SOFT_KEY + '_y', String(Math.round(window.scrollY)));
        } catch (e) { /* refreshes anyway, from the top */ }
        busy(null);
        // A fresh GET of this address (never resends a form); with a #hash, replace() would only scroll
        if (location.hash) location.reload(); else location.replace(location.href);
        return true;
    };

    // The app calls this on coming back to the foreground, with how long it was away
    window.mzAppResumed = (awayMs) => { if (Number(awayMs) >= RESUME_AFTER_MS) refresh(); };
    // Connection back after dropping: whatever failed to load meanwhile comes in
    let wentOffline = false;
    window.addEventListener('offline', () => { wentOffline = true; });
    window.addEventListener('online', () => { if (wentOffline) { wentOffline = false; setTimeout(refresh, 600); } });
    // Shown again from the back/forward cache after a while
    window.addEventListener('pageshow', (e) => { if (e.persisted && Date.now() - loadedAt > RESUME_AFTER_MS) refresh(); });
    // Left open and untouched
    ['touchstart', 'keydown', 'input', 'scroll'].forEach(t => window.addEventListener(t, () => { touchedAt = Date.now(); }, { passive: true, capture: true }));
    setInterval(() => {
        const now = Date.now();
        if (now - loadedAt >= IDLE_AFTER_MS && now - touchedAt >= 60 * 1000) refresh();
    }, 30 * 1000);

    // Sign in with Google: Google doesn't allow its sign-in page inside an app's WebView, so the phone's own Google
    // account picker is used. The app sends back an ID token (window.mzGoogleToken), posted here like a form so the
    // sign-in lands in this WebView. An app too old to have the picker hides the button.
    const googleBtn = document.querySelector('a[data-mz-google]');
    if (googleBtn) {
        const client = googleBtn.dataset.mzGoogleClient;
        const nonce = googleBtn.dataset.mzGoogleNonce;
        if (typeof app.googleSignIn !== 'function' || !client || !nonce) {
            googleBtn.hidden = true;
            const or = googleBtn.previousElementSibling;
            if (or && or.classList.contains('mz-or')) or.hidden = true;
        } else {
            googleBtn.addEventListener('click', (e) => {
                e.preventDefault();
                call('googleSignIn', client, nonce);
            });
            window.mzGoogleToken = (token) => {
                if (!token) return;    // closed the picker
                const form = document.createElement('form');
                form.method = 'POST';
                form.action = googleBtn.dataset.mzGoogleTokenUrl;
                form.hidden = true;
                const field = (name, value) => {
                    const input = document.createElement('input');
                    input.type = 'hidden';
                    input.name = name;
                    input.value = value;
                    form.appendChild(input);
                };
                field('idToken', token);
                field('_csrf', meta('_csrf'));
                document.body.appendChild(form);
                form.submit();
            };
        }
    }
})();
