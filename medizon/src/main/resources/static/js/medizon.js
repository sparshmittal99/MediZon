/* MediZon front-end helpers: CSRF-aware fetch, live page updates, notification polling, desktop alerts, confirm dialogs. */
(function () {
    const meta = (name) => document.querySelector(`meta[name="${name}"]`)?.content;
    const base = (meta('mz_base') || '/').replace(/\/$/, '');

    window.mzUrl = (path) => base + path;

    // ---- Admin > Users: a store account needs its store ----
    document.querySelectorAll('[data-mz-new-user]').forEach((form) => {
        const role = form.querySelector('[data-mz-role]');
        const pick = form.querySelector('[data-mz-store-pick]');
        const sync = () => {
            const store = role.value === 'STORE';
            pick.hidden = !store;
            pick.querySelector('select').required = store;
        };
        role.addEventListener('change', sync);
        sync();
    });

    // ---- no inline handlers (the Content-Security-Policy blocks them): filters that apply on change, print buttons ----
    document.addEventListener('change', (e) => {
        if (e.target.matches && e.target.matches('select[data-mz-autosubmit]')) e.target.form.submit();
    });
    document.addEventListener('click', (e) => {
        if (e.target.closest && e.target.closest('[data-mz-print]')) window.print();
    });
    // A product picture that doesn't load is removed, leaving the placeholder; some may have failed before this ran
    document.addEventListener('error', (e) => {
        if (e.target.matches && e.target.matches('img[data-mz-hide-broken]')) e.target.remove();
    }, true);
    document.querySelectorAll('img[data-mz-hide-broken]').forEach(img => { if (img.complete && img.naturalWidth === 0) img.remove(); });

    // ---- checkout: prices, stores and delivery times depend on the address, so changing it reloads the summary ----
    document.addEventListener('change', (e) => {
        const r = e.target;
        if (r.matches && r.matches('input[name="addressId"][form="placeOrder"]')) {
            location.href = mzUrl('/checkout?address=' + encodeURIComponent(r.value));
        }
    });

    // ---- monitoring: JavaScript errors and page load times go to Admin > Monitoring ----
    // Sent as plain text with sendBeacon, so a report survives the page being closed. A page reports at most 5 errors.
    const beacon = (path, body) => {
        const data = JSON.stringify(body);
        try {
            if (navigator.sendBeacon && navigator.sendBeacon(mzUrl(path), new Blob([data], { type: 'text/plain' }))) return;
        } catch (ignored) { /* fall through to fetch */ }
        fetch(mzUrl(path), { method: 'POST', body: data, keepalive: true, credentials: 'same-origin',
            headers: { 'Content-Type': 'text/plain' } }).catch(() => {});
    };
    let errorsSent = 0;
    const reportError = (report) => {
        if (errorsSent++ < 5) beacon('/api/monitoring/client-error', Object.assign({ page: location.pathname }, report));
    };
    window.addEventListener('error', (e) => {
        if (!e.message) return;                 // a broken image or stylesheet, not a script error
        reportError({ kind: 'error', message: e.message, source: e.filename, line: e.lineno, column: e.colno, stack: e.error?.stack });
    });
    window.addEventListener('unhandledrejection', (e) => {
        const r = e.reason;
        reportError({ kind: 'promise', message: String(r?.message || r), stack: r?.stack });
    });
    window.addEventListener('load', () => setTimeout(() => {
        const nav = performance.getEntriesByType?.('navigation')?.[0];
        if (!nav || nav.loadEventEnd <= 0) return;
        beacon('/api/monitoring/page-timing', { page: location.pathname, ttfb: Math.round(nav.responseStart),
            domReady: Math.round(nav.domContentLoadedEventEnd), load: Math.round(nav.loadEventEnd) });
    }, 0));

    window.mzFetch = function (path, options = {}) {
        const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
        const header = meta('_csrf_header'), token = meta('_csrf');
        if (header && token) headers[header] = token;
        return fetch(mzUrl(path), Object.assign({}, options, { headers, credentials: 'same-origin' }));
    };

    // ---- live pages: a page reloads itself when something it shows is changed elsewhere ----
    // The page knows the change version it was built from (meta mz_live). The server sends newer versions over the
    // live connection (and the notification poll); a newer one reloads the page, unless the user is busy with it:
    // typing, a filled-in form, an open dialog or the assistant. Then a "Refresh" pill waits for them instead.
    const live = (function () {
        const tag = document.querySelector('meta[name="mz_live"]');
        let version = Number(tag?.content || 0);
        let pending = 0, timer = null, leaving = false, pill = null;

        const edited = (el) => {
            if (el.disabled || el.type === 'hidden') return false;
            if (el.type === 'file') return el.files.length > 0;
            if (el.type === 'checkbox' || el.type === 'radio') return el.checked !== el.defaultChecked;
            if (el.tagName === 'SELECT') return [...el.options].some(o => o.selected !== o.defaultSelected);
            return el.value !== el.defaultValue;
        };
        const busy = () => {
            const a = document.activeElement;
            if (a && a.matches('textarea, select, [contenteditable="true"], input:not([type=checkbox]):not([type=radio]):not([type=button]):not([type=submit])')) return true;
            if (document.querySelector('.modal.show, .offcanvas.show, .dropdown-menu.show, .mz-bot-panel:not([hidden])')) return true;
            return [...document.querySelectorAll('form input, form textarea, form select')].some(edited);
        };
        const showPill = () => {
            if (pill) return;
            pill = document.createElement('button');
            pill.type = 'button';
            pill.className = 'mz-live-pill';
            pill.innerHTML = '<i class="bi bi-arrow-clockwise me-1" aria-hidden="true"></i>Updated · Refresh';
            pill.addEventListener('click', () => location.reload());
            document.body.appendChild(pill);
        };
        function apply() {
            timer = null;
            if (pending <= version || leaving || document.hidden) return;   // hidden tabs catch up when shown
            if (busy()) { showPill(); timer = setTimeout(apply, 2000); return; }
            location.reload();
        }
        // Leaving the page anyway (a form was sent, a link opened): don't reload over the navigation.
        window.addEventListener('beforeunload', () => { leaving = true; setTimeout(() => { leaving = false; }, 5000); });
        document.addEventListener('visibilitychange', () => { if (!document.hidden && !timer) apply(); });

        return {
            // A newer change exists: wait a moment, so a reply to this page's own request can claim it first
            seen(v) {
                v = Number(v) || 0;
                if (!tag || v <= version) return;
                pending = Math.max(pending, v);
                if (!timer) timer = setTimeout(apply, 800);
            },
            // This page made the change itself and already shows it
            adopt(v) { v = Number(v) || 0; if (v > version) version = v; },
        };
    })();
    const nativeFetch = window.fetch.bind(window);
    window.fetch = (...args) => nativeFetch(...args).then(res => {
        const v = res.headers.get('X-MZ-Live');
        if (v) live.adopt(v);
        return res;
    });

    // Number boxes ignore the mouse wheel. Scrolling the page over a focused price box used to step it down a
    // paisa a tick, so an MRP typed as 600 could be saved as 599.81.
    document.addEventListener('wheel', (e) => {
        const el = e.target;
        if (el instanceof HTMLInputElement && el.type === 'number' && document.activeElement === el) el.blur();
    }, { passive: true });

    // ---- in-page toast for new notifications ----
    function toast(item) {
        let wrap = document.querySelector('.mz-toasts');
        if (!wrap) {
            wrap = document.createElement('div');
            wrap.className = 'mz-toasts';
            wrap.setAttribute('aria-live', 'polite');
            document.body.appendChild(wrap);
        }
        const el = document.createElement('div');
        el.className = 'toast show';
        el.setAttribute('role', 'status');
        el.innerHTML = `<div class="toast-header"><i class="bi bi-bell me-2 text-primary"></i>
              <strong class="me-auto"></strong><button type="button" class="btn-close" data-bs-dismiss="toast" aria-label="Close"></button></div>
            <div class="toast-body"><p class="mb-2"></p><a class="btn btn-sm btn-primary">Open</a></div>`;
        el.querySelector('strong').textContent = item.title;
        el.querySelector('p').textContent = item.message;
        el.querySelector('a').href = mzUrl(item.url);
        el.querySelector('.btn-close').addEventListener('click', () => el.remove());
        wrap.appendChild(el);
        setTimeout(() => el.remove(), 15000);
    }

    function desktop(item) {
        if (!('Notification' in window) || Notification.permission !== 'granted') return;
        const n = new Notification(item.title, { body: item.message, tag: 'medizon-' + item.id });
        n.onclick = () => { window.focus(); location.href = mzUrl(item.url); };
    }

    function setBadge(id, count) {
        const el = document.getElementById(id);
        if (!el) return;
        el.textContent = count;
        el.classList.toggle('d-none', !count);
    }
    window.mzSetBadge = setBadge;

    // How new alerts reach the page:
    // - One tab per browser holds a live connection (/api/notifications/stream); the server sends "notify" the
    //   moment a notification is created, and that tab tells the other tabs over a BroadcastChannel.
    // - Each tab then fetches anything newer than the last id it has shown (so every tab gets every alert),
    //   and also polls every 30 s as a safety net.
    // - The same connection carries "changed" events, shared with the other tabs the same way (see live pages).
    // - Only the connection-holding tab raises the desktop notification, so there is one popup per alert.
    const store = (key) => ({
        get() { try { return Number(localStorage.getItem(key) || 0); } catch (e) { return 0; } },
        set(v) { try { if (v > this.get()) localStorage.setItem(key, String(v)); } catch (e) { /* private mode */ } },
    });
    const seen = store('mzNotifSeen');        // newest id any tab has shown; stops replays on page loads
    const popped = store('mzNotifPopped');    // newest id that got a desktop popup
    const canElect = 'EventSource' in window && !!navigator.locks;
    let tabSeen = seen.get();
    let leader = false;
    let polling = null;

    async function poll() {
        if (polling) return polling;          // a push and the timer can land together; fetch once
        polling = (async () => {
            try {
                const res = await fetch(mzUrl('/api/notifications/poll?after=' + tabSeen), { credentials: 'same-origin' });
                if (!res.ok || !(res.headers.get('content-type') || '').includes('json')) return;
                const data = await res.json();
                setBadge('mzBellCount', data.unread);
                live.seen(data.version);
                data.items.forEach(item => {
                    if (item.id <= tabSeen) return;
                    toast(item);
                    if ((leader || !canElect) && item.id > popped.get()) { popped.set(item.id); desktop(item); }
                });
                tabSeen = Math.max(tabSeen, data.latest || 0, ...data.items.map(i => i.id));
                seen.set(tabSeen);
            } catch (e) { /* offline: try again next tick */ }
        })().finally(() => { polling = null; });
        return polling;
    }

    const tabs = 'BroadcastChannel' in window ? new BroadcastChannel('mz-notify') : null;

    function listen() {
        const events = new EventSource(mzUrl('/api/notifications/stream'));
        events.addEventListener('notify', () => { poll(); if (tabs) tabs.postMessage('notify'); });
        events.addEventListener('changed', (e) => { live.seen(e.data); if (tabs) tabs.postMessage({ changed: e.data }); });
        events.addEventListener('open', () => poll());       // catch up after a reconnect
    }

    // Logged-in pages carry the bell; only those listen and poll.
    if (document.getElementById('mzBellCount')) {
        poll();
        setInterval(poll, 30000);
        if (tabs) tabs.onmessage = (e) => (e.data && e.data.changed ? live.seen(e.data.changed) : poll());
        document.addEventListener('visibilitychange', () => { if (!document.hidden) poll(); });
        if (canElect) {
            // Whichever tab gets the lock keeps the connection; when it closes, another tab takes over.
            navigator.locks.request('mz-notify-leader', () => {
                leader = true;
                listen();
                return new Promise(() => {});
            });
        } else if ('EventSource' in window) {
            listen();
        }
    }

    // ---- in-page confirm dialog for <form data-mz-confirm="..."> (replaces window.confirm) ----
    let confirmModal, confirmForm;
    function confirmDialog() {
        if (confirmModal) return confirmModal;
        const el = document.createElement('div');
        el.className = 'modal fade';
        el.tabIndex = -1;
        el.setAttribute('aria-labelledby', 'mzConfirmTitle');
        el.setAttribute('aria-hidden', 'true');
        el.innerHTML = `<div class="modal-dialog modal-dialog-centered"><div class="modal-content mz-confirm">
              <div class="modal-body text-center">
                <div class="mz-confirm-icon" aria-hidden="true"><i class="bi bi-exclamation-triangle"></i></div>
                <h2 class="h5 mb-2" id="mzConfirmTitle"></h2>
                <p class="text-muted mb-0" data-mz-msg></p>
              </div>
              <div class="modal-footer justify-content-center border-0 pt-0">
                <button type="button" class="btn btn-outline-secondary" data-bs-dismiss="modal">Keep it</button>
                <button type="button" class="btn btn-danger" data-mz-ok></button>
              </div></div></div>`;
        document.body.appendChild(el);
        el.querySelector('[data-mz-ok]').addEventListener('click', () => {
            const form = confirmForm;
            confirmForm = null;
            bootstrap.Modal.getInstance(el).hide();
            if (form) { form.dataset.mzConfirmed = '1'; form.requestSubmit ? form.requestSubmit() : form.submit(); }
        });
        confirmModal = el;
        return el;
    }

    document.addEventListener('submit', (e) => {
        const form = e.target;
        if (!form.matches('form[data-mz-confirm]')) return;
        if (form.dataset.mzConfirmed) { delete form.dataset.mzConfirmed; return; }
        e.preventDefault();
        const el = confirmDialog();
        el.querySelector('#mzConfirmTitle').textContent = form.dataset.mzConfirmTitle || 'Are you sure?';
        el.querySelector('[data-mz-msg]').textContent = form.dataset.mzConfirm;
        el.querySelector('[data-mz-ok]').textContent = form.dataset.mzConfirmOk || 'Confirm';
        confirmForm = form;
        bootstrap.Modal.getOrCreateInstance(el).show();
    });

    // ---- add to cart: "Add" becomes a - qty + stepper, changed in place through /api/cart ----
    function renderQty(box, qty, message) {
        const max = Number(box.dataset.max || 0);
        box.dataset.qty = qty;
        const add = box.querySelector('.mz-qty-add');
        const stepper = box.querySelector('.mz-qty-stepper');
        const oos = box.querySelector('.mz-qty-oos');
        const note = box.querySelector('.mz-qty-note');
        if (add) add.hidden = qty > 0 || max <= 0;
        if (oos) oos.hidden = qty > 0 || max > 0;
        stepper.hidden = qty <= 0;
        stepper.querySelector('.mz-qty-count').textContent = qty;
        stepper.querySelector('[data-step="1"]').disabled = qty >= max;
        if (note) {
            note.hidden = qty <= 0;
            if (message) note.querySelector('[data-mz-qty-msg]').textContent = message;
        }
    }

    function qtyError(box, text) {
        const el = box.querySelector('.mz-qty-error');
        el.textContent = text;
        el.hidden = false;
        clearTimeout(el._t);
        el._t = setTimeout(() => { el.hidden = true; }, 4000);
    }

    async function setQty(box, qty) {
        if (box.classList.contains('is-busy')) return;
        const before = Number(box.dataset.qty || 0);
        box.classList.add('is-busy');
        try {
            const res = await mzFetch('/api/cart/' + box.dataset.mzCart, { method: 'POST', body: JSON.stringify({ quantity: qty }) });
            if (res.redirected) { location.href = res.url; return; }      // signed out: go to the login page
            const data = await res.json();
            if (!res.ok) { qtyError(box, data.error || 'Could not update your cart.'); return; }
            const msg = data.quantity > before ? 'Added to cart' : 'Cart updated';
            document.querySelectorAll(`[data-mz-cart="${box.dataset.mzCart}"]`).forEach(b => renderQty(b, data.quantity, msg));
            setBadge('mzCartCount', data.cartCount);
            updateCartPage(box, data);
        } catch (e) {
            qtyError(box, 'Could not reach MediZon. Check your connection.');
        } finally {
            box.classList.remove('is-busy');
        }
    }

    /** On the cart page: refresh the row amount and the summary, and drop rows that reach zero. */
    function updateCartPage(box, data) {
        const summary = document.querySelector('[data-mz-cart-summary]');
        if (!summary) return;
        if (data.empty) { location.reload(); return; }      // shows the "cart is empty" state
        const row = box.closest('[data-mz-cart-row]');
        // A line that couldn't be delivered, or one that now moves to another store, is simplest shown fresh
        if (row && data.quantity > 0 && (row.classList.contains('mz-cart-unavailable') || !data.store)) { location.reload(); return; }
        const deliveries = summary.querySelector('[data-mz-deliveries]');
        if (deliveries) {
            deliveries.hidden = (data.shipmentCount || 0) < 2;
            deliveries.querySelector('span').textContent = `Comes from ${data.shipmentCount} stores, as separate orders paid for together.`;
        }
        if (row) {
            if (data.quantity === 0) row.remove();
            else row.querySelector('[data-mz-line-total]').textContent = rupees(data.lineTotal);
            const unit = row.querySelector('[data-mz-unit-price]');
            if (unit && data.unitPrice != null) unit.textContent = rupees(data.unitPrice);
            const store = row.querySelector('[data-mz-line-store]');
            if (store && data.store) store.textContent = data.store;
            const alloc = row.querySelector('[data-mz-alloc]');
            if (alloc && data.allocation) renderAlloc(alloc, data.allocation, data.quantity);
        }
        const freeDelivery = Number(data.deliveryFee) === 0;
        summary.querySelector('[data-mz-subtotal]').textContent = rupees(data.subtotal);
        summary.querySelector('[data-mz-delivery]').textContent = freeDelivery ? 'Free' : rupees(data.deliveryFee);
        const roundOff = summary.querySelector('[data-mz-round-off]');
        if (roundOff) {
            const r = Number(data.roundOff);
            roundOff.hidden = r === 0;
            roundOff.querySelector('[data-mz-round-off-value]').textContent = (r < 0 ? '−' : '+') + rupees(Math.abs(r));
        }
        summary.querySelector('[data-mz-total]').textContent = rupees(data.total);
        summary.querySelector('[data-mz-free-note]').hidden = freeDelivery;
        summary.querySelector('[data-mz-rx-note]').hidden = !data.prescriptionRequired;
    }

    /** An amount from the API (a number of rupees) the way the pages print it: ₹1,234.50. */
    function rupees(amount) {
        return '₹' + Number(amount).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    document.addEventListener('submit', (e) => {
        const box = e.target.closest('[data-mz-cart]');
        if (!box || !e.target.matches('.mz-qty-add')) return;
        e.preventDefault();
        setQty(box, 1);
    });
    document.addEventListener('click', (e) => {
        const btn = e.target.closest('.mz-qty-btn');
        if (!btn) return;
        const box = btn.closest('[data-mz-cart]');
        setQty(box, Number(box.dataset.qty || 0) + Number(btn.dataset.step));
    });

    // ---- AI prescription reading takes a few seconds: say so on the button ----
    document.addEventListener('submit', (e) => {
        if (!e.target.matches('[data-mz-ai-busy]') || e.defaultPrevented) return;
        const btn = e.target.querySelector('button[type="submit"]');
        if (!btn) return;
        setTimeout(() => {      // after the form has submitted, so the button's value still goes with it
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>';
            btn.append(btn.dataset.busyText || 'Reading your prescription…');
        }, 0);
    });

    // ---- pharmacist's approval form: add or remove medicine rows ----
    document.addEventListener('click', (e) => {
        const add = e.target.closest('[data-mz-cover-add]');
        if (add) {
            const box = add.closest('[data-mz-cover]');
            const row = box.querySelector('[data-mz-cover-blank]').cloneNode(true);
            row.removeAttribute('data-mz-cover-blank');
            row.querySelector('select').value = '';
            row.querySelector('input').value = '1';
            add.before(row);
            row.querySelector('select').focus();
            return;
        }
        const remove = e.target.closest('[data-mz-cover-remove]');
        if (remove) {
            const row = remove.closest('.mz-cover-row');
            if (row.hasAttribute('data-mz-cover-blank')) { row.querySelector('select').value = ''; return; }
            row.remove();
        }
    });

    // ---- "Upload prescription" in the category bar: pick a file and it uploads straight away ----
    document.addEventListener('change', (e) => {
        if (!e.target.matches('[data-mz-rx-quick] input[type="file"]') || !e.target.files.length) return;
        const form = e.target.form;
        const label = form.querySelector('[data-mz-rx-quick-label]');
        if (label) label.textContent = 'Reading your prescription…';
        form.submit();
    });

    // ---- "Notify me" on out-of-stock products: a back-in-stock alert, on or off through /api/stock-alerts ----
    document.addEventListener('submit', async (e) => {
        const box = e.target.closest('[data-mz-notify]');
        if (!box) return;
        e.preventDefault();
        if (box.classList.contains('is-busy')) return;
        const on = box.dataset.on !== 'true';
        box.classList.add('is-busy');
        try {
            const res = await mzFetch('/api/stock-alerts/' + box.dataset.mzNotify, { method: 'POST', body: JSON.stringify({ on }) });
            if (res.redirected) { location.href = res.url; return; }
            const data = await res.json();
            const cart = box.closest('[data-mz-cart]');
            if (!res.ok) { if (cart) qtyError(cart, data.error || 'Could not set the alert.'); return; }
            document.querySelectorAll(`[data-mz-notify="${box.dataset.mzNotify}"]`).forEach(b => {
                b.dataset.on = String(data.on);
                const btn = b.querySelector('button');
                btn.setAttribute('aria-pressed', String(data.on));
                btn.title = data.on ? 'Tap to stop the back-in-stock alert' : "Get notified when it's back in stock";
                const input = b.querySelector('input[name="on"]');
                if (input) input.value = String(!data.on);
            });
        } catch (err) {
            const cart = box.closest('[data-mz-cart]');
            if (cart) qtyError(cart, 'Could not reach MediZon. Check your connection.');
        } finally {
            box.classList.remove('is-busy');
        }
    });

    // The assistant can change the cart too; resync every stepper on the page when it does.
    document.addEventListener('mz:cart-changed', async () => {
        if (!document.querySelector('[data-mz-cart]')) return;
        try {
            const res = await fetch(mzUrl('/api/cart'), { credentials: 'same-origin' });
            if (!res.ok) return;
            const data = await res.json();
            document.querySelectorAll('[data-mz-cart]').forEach(b => renderQty(b, data.quantities[b.dataset.mzCart] || 0));
        } catch (e) { /* the next page load will show the right numbers */ }
    });

    // ---- admin product image: Upload or Link, with a live preview ----
    document.querySelectorAll('[data-mz-image-picker]').forEach(box => {
        const img = box.querySelector('[data-mz-image-preview]');
        const icon = box.querySelector('[data-mz-image-placeholder]');
        const file = box.querySelector('input[name="imageFile"]');
        const link = box.querySelector('input[name="imageLink"]');
        const remove = box.querySelector('input[name="removeImage"]');
        const original = img.getAttribute('src') || '';
        let objectUrl;
        const show = (src) => {
            img.classList.toggle('d-none', !src);
            icon.classList.toggle('d-none', !!src);
            if (src) img.src = src; else img.removeAttribute('src');
        };
        const current = () => (remove && remove.checked) ? '' : original;
        img.addEventListener('error', () => show(''));

        file.addEventListener('change', () => {
            if (objectUrl) URL.revokeObjectURL(objectUrl);
            objectUrl = file.files[0] ? URL.createObjectURL(file.files[0]) : null;
            if (objectUrl) { link.value = ''; if (remove) remove.checked = false; }
            show(objectUrl || current());
        });
        link.addEventListener('input', () => {
            const v = link.value.trim();
            if (v) { file.value = ''; if (remove) remove.checked = false; }
            show(/^https?:\/\//i.test(v) ? v : current());
        });
        if (remove) remove.addEventListener('change', () => {
            if (remove.checked) { file.value = ''; link.value = ''; }
            show(current());
        });
    });

    // ---- cart: who the packs are for ----
    // One pack goes to one choice (tapping another moves it). With several packs, each chosen person has their own
    // - n + and the line's quantity is their sum. "Others" (outside the family) is never mixed with family members.
    function renderAlloc(box, allocation, quantity) {
        const max = Number(box.dataset.max || 0);
        const opts = [...box.querySelectorAll('.mz-alloc-opt')];
        const on = opts.filter(o => (allocation[o.dataset.member] || 0) > 0).length;
        box.classList.toggle('is-multi', quantity > 1);
        opts.forEach(o => {
            const n = allocation[o.dataset.member] || 0;
            o.dataset.packs = n;
            o.classList.toggle('is-on', n > 0);
            o.querySelector('.mz-alloc-pick').setAttribute('aria-pressed', n > 0);
            o.querySelector('.mz-alloc-n').textContent = n;
            o.querySelector('[data-d="1"]').disabled = quantity >= max;
            o.querySelector('[data-d="-1"]').disabled = on === 1 && n <= 1;   // the last pack goes with the main stepper
        });
        const patients = box.querySelector('[data-mz-patients]');
        if (patients) {
            const names = [...new Set(opts.filter(o => (allocation[o.dataset.member] || 0) > 0)
                .map(o => o.dataset.patient).filter(Boolean))];
            patients.querySelector('span').textContent = names.join(', ');
            patients.hidden = names.length === 0;
        }
    }
    document.querySelectorAll('[data-mz-alloc]').forEach(box => {
        const now = {};
        box.querySelectorAll('.mz-alloc-opt').forEach(o => { now[o.dataset.member] = Number(o.dataset.packs || 0); });
        renderAlloc(box, now, Object.values(now).reduce((a, b) => a + b, 0));
    });

    function allocError(box, text) {
        const err = box.querySelector('.mz-qty-error');
        err.textContent = text;
        err.hidden = false;
        clearTimeout(err._t);
        err._t = setTimeout(() => { err.hidden = true; }, 5000);
    }

    /** The packs per person after a tap, or null when the tap changes nothing. */
    function nextAllocation(box, opt, step) {
        const max = Number(box.dataset.max || 0);
        const opts = [...box.querySelectorAll('.mz-alloc-opt')];
        const next = {};
        opts.forEach(o => { next[o.dataset.member] = Number(o.dataset.packs || 0); });
        const total = Object.values(next).reduce((a, b) => a + b, 0);
        const on = opts.filter(o => next[o.dataset.member] > 0);
        const id = opt.dataset.member;
        const mine = next[id];

        if (step) {                                              // - n + on one person
            if (step > 0 && total >= max) { allocError(box, `Only ${max} packs are in stock.`); return null; }
            if (step < 0 && mine <= 1 && on.length === 1) return null;
            next[id] = Math.max(0, mine + step);
            return next;
        }
        if (mine > 0) {                                          // tapping someone already chosen
            if (on.length === 1) return null;                    // someone has to get it
            next[id] = 0;                                        // their packs come off the line
            return next;
        }
        if (total <= 1) {                                        // one pack: it goes to them
            Object.keys(next).forEach(k => { next[k] = 0; });
            next[id] = 1;
            return next;
        }
        const biggest = on.reduce((a, b) => (next[b.dataset.member] > next[a.dataset.member] ? b : a));
        if (next[biggest.dataset.member] >= 2) {                 // share an existing pack with them
            next[biggest.dataset.member] -= 1;
            next[id] = 1;
            return next;
        }
        if (total >= max) { allocError(box, `Only ${max} packs are in stock.`); return null; }
        next[id] = 1;                                            // everyone has one already: add a pack for them
        return next;
    }

    document.addEventListener('click', async (e) => {
        const pick = e.target.closest('.mz-alloc-pick');
        const stepBtn = e.target.closest('.mz-alloc-step button');
        if (!pick && !stepBtn) return;
        const box = e.target.closest('[data-mz-alloc]');
        const opt = e.target.closest('.mz-alloc-opt');
        if (!box || box.classList.contains('is-busy')) return;
        const packs = nextAllocation(box, opt, stepBtn ? Number(stepBtn.dataset.d) : 0);
        if (!packs) return;
        box.classList.add('is-busy');
        try {
            const res = await mzFetch('/api/cart/' + box.dataset.mzAlloc + '/allocation', { method: 'POST', body: JSON.stringify({ packs }) });
            if (res.redirected) { location.href = res.url; return; }
            const data = await res.json();
            if (!res.ok) { allocError(box, data.error || 'Could not save that.'); return; }
            box.querySelector('.mz-qty-error').hidden = true;
            document.querySelectorAll(`[data-mz-cart="${box.dataset.mzAlloc}"]`).forEach(b => renderQty(b, data.quantity, 'Cart updated'));
            setBadge('mzCartCount', data.cartCount);
            updateCartPage(box, data);
        } catch (ex) {
            allocError(box, 'Could not reach MediZon. Check your connection.');
        } finally {
            box.classList.remove('is-busy');
        }
    });

    // ---- plan form: show the fields for the chosen schedule; custom dates as removable chips ----
    document.querySelectorAll('[data-mz-schedule]').forEach(section => {
        const radios = section.querySelectorAll('[data-mz-schedule-type]');
        const sync = () => {
            const type = [...radios].find(r => r.checked)?.value || 'EVERY_DAY';
            section.querySelectorAll('[data-show-for]').forEach(el => {
                el.hidden = !el.dataset.showFor.split(' ').includes(type);
            });
        };
        radios.forEach(r => r.addEventListener('change', sync));
        sync();

        // The common shelf only tracks stock: no schedule or refills. For a person, offer to move stock off the shelf.
        const member = document.querySelector('[data-mz-plan-member]');
        const medicine = document.querySelector('[data-mz-plan-medicine]');
        const take = document.querySelector('[data-mz-take-common]');
        // Medicines on offer depend on who takes them: each option lists the member ids it was delivered for.
        // Options are rebuilt rather than hidden, since Safari ignores hidden <option>s.
        const allMedicines = medicine ? [...medicine.options].filter(o => o.value) : [];
        const syncMedicines = () => {
            if (!medicine || !member) return;
            const who = member.value;
            const chosen = medicine.value;
            const fits = allMedicines.filter(o => (o.dataset.members || '').split(' ').includes(who));
            [...medicine.options].forEach(o => { if (o.value) o.remove(); });
            fits.forEach(o => medicine.add(o));
            medicine.value = fits.some(o => o.value === chosen) ? chosen : '';
            document.querySelector('[data-mz-plan-medicine-none]').hidden = fits.length > 0;
            document.querySelector('[data-mz-plan-medicine-hint]').hidden = fits.length === 0;
        };
        if (member) {
            member.addEventListener('change', syncMedicines);
            syncMedicines();
            const syncMember = () => {
                const shelf = member.selectedOptions[0]?.dataset.common === 'true';
                document.querySelectorAll('[data-mz-person-only]').forEach(el => { el.hidden = shelf; });
                document.querySelectorAll('[data-mz-shelf-only]').forEach(el => { el.hidden = !shelf; });
                if (take && medicine) {
                    const opt = medicine.selectedOptions[0];
                    const units = Number(opt?.dataset.shelf || 0);
                    take.hidden = shelf || units <= 0;
                    take.querySelector('[data-mz-take-common-text]').textContent = units > 0
                        ? `${units} ${opt.dataset.unit} on the common shelf. We'll move what this course needs, or all of it for an ongoing medicine.`
                        : '';
                }
            };
            member.addEventListener('change', syncMember);
            if (medicine) medicine.addEventListener('change', syncMember);
            syncMember();
        }

        // Automatic refills: choose which saved address they go to
        const refillAddress = document.querySelector('[data-mz-refill-address]');
        if (refillAddress) {
            const modes = document.querySelectorAll('[data-mz-reorder-mode]');
            const syncRefill = () => {
                refillAddress.hidden = [...modes].find(r => r.checked)?.value !== 'AUTO';
            };
            modes.forEach(r => r.addEventListener('change', syncRefill));
            syncRefill();
        }

        const dates = section.querySelector('[data-mz-dates]');
        if (!dates) return;
        const input = dates.querySelector('[data-mz-date-input]');
        const add = dates.querySelector('[data-mz-date-add]');
        const list = dates.querySelector('[data-mz-date-list]');
        input.removeAttribute('name');            // with JavaScript, the chips carry the dates
        add.hidden = false;
        const fmt = new Intl.DateTimeFormat('en-IN', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
        const addDate = () => {
            const v = input.value;
            if (!v) { input.focus(); return; }
            if (list.querySelector(`input[value="${v}"]`)) { input.value = ''; return; }
            const li = document.createElement('li');
            li.innerHTML = '<input type="hidden" name="customDates"><span></span>'
                + '<button type="button" class="btn-close btn-close-sm" aria-label="Remove date" data-mz-date-remove></button>';
            li.querySelector('input').value = v;
            li.querySelector('span').textContent = fmt.format(new Date(v + 'T00:00:00'));
            const after = [...list.children].find(c => c.querySelector('input').value > v);
            list.insertBefore(li, after || null);
            input.value = '';
            input.focus();
        };
        add.addEventListener('click', addDate);
        input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); addDate(); } });
        list.addEventListener('click', (e) => {
            if (e.target.matches('[data-mz-date-remove]')) e.target.closest('li').remove();
        });
    });

    // ---- alerts on this device: member picks only matter for "Only these members" ----
    document.querySelectorAll('[data-mz-device-form]').forEach(form => {
        const picks = form.querySelector('[data-mz-member-picks]');
        const sync = () => {
            const all = form.querySelector('[data-mz-all]:checked')?.value !== 'false';
            picks.classList.toggle('is-off', all);
            picks.querySelectorAll('input').forEach(i => { i.disabled = all; });
        };
        form.querySelectorAll('[data-mz-all]').forEach(r => r.addEventListener('change', sync));
        sync();
    });

    // ---- return request: photo previews and the photo limit ----
    document.querySelectorAll('[data-mz-return-photos]').forEach(input => {
        const box = document.querySelector('[data-mz-photo-previews]');
        const err = document.querySelector('[data-mz-photo-error]');
        const max = Number(input.dataset.max || 3);
        let urls = [];
        input.addEventListener('change', () => {
            urls.forEach(u => URL.revokeObjectURL(u));
            box.replaceChildren();
            err.hidden = input.files.length <= max;
            if (input.files.length > max) {
                err.textContent = `Choose at most ${max} photos.`;
                input.value = '';
                urls = [];
                return;
            }
            urls = [...input.files].map(f => URL.createObjectURL(f));
            urls.forEach((u, i) => {
                const img = document.createElement('img');
                img.src = u;
                img.alt = 'Photo ' + (i + 1);
                box.appendChild(img);
            });
        });
    });

    // ---- support ticket: notice a reply while the page is open. Shows it right away, unless a reply is being typed ----
    (function () {
        const page = document.querySelector('[data-mz-ticket-poll]');
        if (!page) return;
        const latest = Number(page.dataset.latest || 0);
        const banner = document.querySelector('[data-mz-ticket-new]');
        const typing = () => [...document.querySelectorAll('[data-mz-reply] textarea')].some(t => t.value.trim())
            || [...document.querySelectorAll('[data-mz-reply] input[type=file]')].some(f => f.files.length);
        const showLatest = () => { location.hash = 'latest'; location.reload(); };
        const timer = setInterval(async () => {
            if (document.hidden) return;
            try {
                const res = await fetch(page.dataset.mzTicketPoll, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
                if (!res.ok || !(res.headers.get('content-type') || '').includes('json')) return;   // signed out, or offline
                const data = await res.json();
                if (Number(data.latest) <= latest) return;
                clearInterval(timer);
                if (typing()) banner.hidden = false; else showLatest();
            } catch (e) { /* try again next time */ }
        }, 15000);
        document.querySelector('[data-mz-ticket-refresh]')?.addEventListener('click', (e) => { e.preventDefault(); showLatest(); });
    })();

    // ---- admin sales report: show the inputs for the chosen period type; financial years read best month by month ----
    document.querySelectorAll('[data-mz-period-form]').forEach(form => {
        const sync = (changed) => {
            const kind = form.querySelector('[data-mz-period]:checked')?.value || 'range';
            form.querySelectorAll('[data-period-for]').forEach(el => { el.hidden = el.dataset.periodFor !== kind; });
            if (changed && kind === 'fy') form.querySelector('#gMonth').checked = true;
        };
        form.querySelectorAll('[data-mz-period]').forEach(r => r.addEventListener('change', () => sync(true)));
        sync(false);
    });

    // "Turn on desktop alerts" buttons
    document.querySelectorAll('[data-mz-enable-alerts]').forEach(btn => {
        const sync = () => {
            if (!('Notification' in window)) { btn.textContent = 'Desktop alerts not supported'; btn.disabled = true; return; }
            if (Notification.permission === 'granted') { btn.innerHTML = '<i class="bi bi-bell-fill me-1"></i>Desktop alerts on'; btn.disabled = true; }
            if (Notification.permission === 'denied') { btn.textContent = 'Alerts blocked in browser settings'; btn.disabled = true; }
        };
        sync();
        btn.addEventListener('click', async () => { await Notification.requestPermission(); sync(); });
    });

    // ---- account settings: show / hide passwords, and catch mismatched new passwords before posting ----
    document.addEventListener('click', (e) => {
        const btn = e.target.closest('[data-mz-pw-toggle]');
        if (!btn) return;
        const input = btn.parentElement.querySelector('input');
        const show = input.type === 'password';
        input.type = show ? 'text' : 'password';
        btn.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
        btn.querySelector('i').className = show ? 'bi bi-eye-slash' : 'bi bi-eye';
    });
    document.querySelectorAll('[data-mz-password-form]').forEach(form => {
        const next = form.querySelector('[name="newPassword"]');
        const confirm = form.querySelector('[name="confirmPassword"]');

        // Step 1: the customer types their current password (never filled in by the browser) and we check it;
        // step 2: the new password twice. The server checks the current password again when saving.
        const current = form.querySelector('[data-mz-no-autofill]');
        const step1 = form.querySelector('[data-mz-step="1"]');
        const step2 = form.querySelector('[data-mz-step="2"]');
        const nextBtn = form.querySelector('[data-mz-step-next]');
        const error = form.querySelector('[data-mz-step-error]');
        const done = form.querySelector('[data-mz-step-done]');
        const dots = form.querySelectorAll('[data-mz-step-dot]');
        current.readOnly = true;
        current.value = '';
        current.addEventListener('focus', () => { current.readOnly = false; });
        const goTo = (n) => {
            step1.querySelector('.mz-pw').hidden = n === 2;
            step1.querySelector('label').hidden = n === 2;
            nextBtn.hidden = n === 2;
            step2.hidden = n === 1;
            done.hidden = n === 1;
            dots.forEach(d => d.classList.toggle('is-active', Number(d.dataset.mzStepDot) <= n));
            (n === 1 ? current : next).focus();
        };
        nextBtn.hidden = false;
        step2.hidden = true;
        const verify = async () => {
            error.hidden = true;
            if (!current.value) { error.textContent = 'Enter your current password.'; error.hidden = false; current.focus(); return; }
            nextBtn.disabled = true;
            try {
                const res = await mzFetch('/api/account/verify-password', { method: 'POST', body: JSON.stringify({ password: current.value }) });
                const data = await res.json();
                if (res.ok) { goTo(2); return; }
                error.textContent = data.error || 'That isn’t your current password.';
                error.hidden = false;
                current.select();
            } catch (e) {
                error.textContent = 'Could not reach MediZon. Check your connection.';
                error.hidden = false;
            } finally { nextBtn.disabled = false; }
        };
        nextBtn.addEventListener('click', verify);
        current.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); verify(); } });
        form.querySelector('[data-mz-step-back]').addEventListener('click', () => {
            current.value = '';
            next.value = '';
            confirm.value = '';
            goTo(1);
        });
        const check = () => {
            const bad = confirm.value !== '' && confirm.value !== next.value;
            confirm.classList.toggle('is-invalid', bad);
            confirm.setCustomValidity(bad ? 'The new passwords don’t match.' : '');
        };
        next.addEventListener('input', check);
        confirm.addEventListener('input', check);
    });

    // ---- delivery location: "Delivers to" in the header ----
    // The browser turns the device location, a pin on the map or a typed address into a PIN code using
    // OpenStreetMap's Nominatim geocoder; /api/location checks it against the admin's delivery areas and remembers it.
    // Geocoding helpers shared by the location picker and the address form
    const OSM = 'https://nominatim.openstreetmap.org';
    const pinOf = (s) => { const p = String(s || '').replace(/\s+/g, ''); return /^[1-9][0-9]{5}$/.test(p) ? p : null; };
    const labelOf = (a = {}) => {
        const area = a.suburb || a.neighbourhood || a.residential || a.village || a.city_district || '';
        const city = a.city || a.town || a.state_district || a.county || '';
        return [area, city].filter((v, i, all) => v && all.indexOf(v) === i).join(', ');
    };

    async function reverse(lat, lon) {
        const res = await fetch(`${OSM}/reverse?format=jsonv2&addressdetails=1&accept-language=en&lat=${lat}&lon=${lon}`);
        if (!res.ok) throw new Error('geocoder');
        const data = await res.json();
        return { pincode: pinOf(data.address?.postcode), label: labelOf(data.address), address: data.address };
    }

    (function locationPicker() {
        const modal = document.getElementById('mzLocModal');
        if (!modal) return;
        const result = modal.querySelector('[data-mz-loc-result]');
        const gps = modal.querySelector('[data-mz-loc-gps]');
        const pinForm = modal.querySelector('[data-mz-loc-pin-form]');
        const pinInput = pinForm.querySelector('input');
        const search = modal.querySelector('[data-mz-loc-search]');
        const hits = modal.querySelector('[data-mz-loc-hits]');
        const mapWrap = modal.querySelector('[data-mz-loc-map-wrap]');
        const mapOpen = modal.querySelector('[data-mz-loc-map-open]');
        const mapText = modal.querySelector('[data-mz-loc-map-text]');
        const mapConfirm = modal.querySelector('[data-mz-loc-map-confirm]');


        function show(kind, text) {
            result.className = 'mz-loc-result is-' + kind;
            const icon = { ok: 'bi-check-circle-fill', off: 'bi-x-circle-fill', busy: 'bi-hourglass-split' }[kind];
            result.innerHTML = `<i class="bi ${icon}" aria-hidden="true"></i><span></span>`;
            result.querySelector('span').textContent = text;
            result.hidden = false;
        }

        // A product page shows when it arrives at this location: refresh it once the picker closes
        let locationChanged = false;
        modal.addEventListener('hidden.bs.modal', () => {
            if (locationChanged && document.querySelector('[data-mz-delivery]')) location.reload();
        });

        function updateHeader(data) {
            document.querySelectorAll('[data-mz-loc]').forEach(btn => {
                btn.classList.toggle('is-off', !data.serviceable);
                btn.querySelector('[data-mz-loc-k]').textContent = data.serviceable ? 'Delivers to' : 'We don’t deliver to';
                btn.querySelector('[data-mz-loc-v]').textContent = (data.label ? data.label + ' ' : '') + data.pincode;
                btn.setAttribute('aria-label', 'Change delivery location');
            });
            modal.dataset.pincode = data.pincode;
            locationChanged = true;
        }

        /** Checks and remembers a PIN code, or a saved address when {@code addressId} is given. */
        async function choose(pincode, label, addressId) {
            show('busy', addressId ? 'Checking this address…' : `Checking ${pincode}…`);
            try {
                const body = addressId ? { addressId } : { pincode, label: label || '' };
                const res = await mzFetch('/api/location', { method: 'POST', body: JSON.stringify(body) });
                const data = await res.json();
                if (!res.ok) { show('off', data.error || 'Could not check that PIN code.'); return; }
                updateHeader(data);
                modal.querySelectorAll('[data-mz-loc-address]').forEach(b =>
                    b.classList.toggle('is-selected', String(addressId || '') === b.dataset.mzLocAddress));
                if (data.serviceable) {
                    show('ok', `Great, we deliver to ${data.label ? data.label + ' ' : ''}${data.pincode}.`);
                    setTimeout(() => bootstrap.Modal.getInstance(modal)?.hide(), 1100);
                } else {
                    show('off', `Sorry, we don’t deliver to ${data.pincode} yet. Try another PIN code or address.`);
                }
            } catch (e) {
                show('off', 'Could not reach MediZon. Check your connection.');
            }
        }


        // Device location
        gps.addEventListener('click', () => {
            if (!navigator.geolocation) { show('off', 'This browser can’t share its location. Enter a PIN code instead.'); return; }
            show('busy', 'Finding where you are…');
            gps.disabled = true;
            navigator.geolocation.getCurrentPosition(async (pos) => {
                try {
                    const here = await reverse(pos.coords.latitude, pos.coords.longitude);
                    if (here.pincode) await choose(here.pincode, here.label);
                    else show('off', 'We couldn’t find a PIN code for your location. Enter it below.');
                } catch (e) {
                    show('off', 'Couldn’t look up your location right now. Enter a PIN code instead.');
                } finally { gps.disabled = false; }
            }, (err) => {
                gps.disabled = false;
                show('off', err.code === err.PERMISSION_DENIED
                    ? 'Location access is blocked. Allow it in your browser, or enter a PIN code.'
                    : 'Couldn’t get your location. Enter a PIN code instead.');
            }, { enableHighAccuracy: true, timeout: 15000, maximumAge: 60000 });
        });

        // Saved addresses
        modal.querySelectorAll('[data-mz-loc-address]').forEach(btn =>
            btn.addEventListener('click', () => choose(null, null, Number(btn.dataset.mzLocAddress))));

        // Typed PIN code
        pinInput.addEventListener('input', () => { pinInput.value = pinInput.value.replace(/\D/g, '').slice(0, 6); });
        pinForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const pin = pinOf(pinInput.value);
            if (!pin) { show('off', 'Enter a valid 6 digit PIN code.'); pinInput.focus(); return; }
            choose(pin, '');
        });

        // Address search
        let searchTimer, searchSeq = 0;
        search.addEventListener('input', () => {
            clearTimeout(searchTimer);
            const q = search.value.trim();
            if (q.length < 3) { hits.replaceChildren(); return; }
            searchTimer = setTimeout(async () => {
                const seq = ++searchSeq;
                try {
                    const res = await fetch(`${OSM}/search?format=jsonv2&addressdetails=1&countrycodes=in&limit=6&accept-language=en&q=${encodeURIComponent(q)}`);
                    const places = res.ok ? await res.json() : [];
                    if (seq !== searchSeq) return;          // a newer search is on its way
                    hits.replaceChildren(...places.map(place => {
                        const li = document.createElement('li');
                        const btn = document.createElement('button');
                        btn.type = 'button';
                        const name = place.display_name.split(', ').slice(0, 4).join(', ');
                        btn.textContent = name;
                        const pin = pinOf(place.address?.postcode);
                        if (pin) {
                            const tag = document.createElement('span');
                            tag.className = 'mz-loc-hit-pin';
                            tag.textContent = pin;
                            btn.appendChild(tag);
                        }
                        btn.addEventListener('click', async () => {
                            hits.replaceChildren();
                            search.value = name;
                            if (pin) { choose(pin, labelOf(place.address)); return; }
                            try {
                                const spot = await reverse(place.lat, place.lon);
                                if (spot.pincode) choose(spot.pincode, spot.label || labelOf(place.address));
                                else show('off', 'That place has no PIN code on the map. Enter the PIN code instead.');
                            } catch (e) { show('off', 'Couldn’t look up that place. Enter the PIN code instead.'); }
                        });
                        li.appendChild(btn);
                        return li;
                    }));
                    if (!places.length) {
                        const li = document.createElement('li');
                        li.className = 'small text-muted px-2 py-1';
                        li.textContent = 'No places found. Try a nearby landmark or the PIN code.';
                        hits.appendChild(li);
                    }
                } catch (e) { /* offline: the PIN code box still works */ }
            }, 450);
        });

        // Pin on the map (Leaflet + OpenStreetMap tiles, loaded the first time the map opens)
        let map, marker, picked;
        const loadLeaflet = () => window.L ? Promise.resolve() : new Promise((ok, fail) => {
            const css = document.createElement('link');
            css.rel = 'stylesheet';
            css.href = 'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css';
            css.integrity = 'sha384-sHL9NAb7lN7rfvG5lfHpm643Xkcjzp4jFvuavGOndn6pjVqS6ny56CAt3nsEVT4H';
            css.crossOrigin = 'anonymous';
            document.head.appendChild(css);
            const js = document.createElement('script');
            js.src = 'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js';
            js.integrity = 'sha384-cxOPjt7s7Iz04uaHJceBmS+qpjv2JkIHNVcuOrM+YHwZOmJGBXI00mdUXEq65HTH';
            js.crossOrigin = 'anonymous';
            js.onload = ok;
            js.onerror = fail;
            document.head.appendChild(js);
        });
        async function pinned(latlng) {
            picked = null;
            mapConfirm.disabled = true;
            mapText.textContent = 'Looking up this spot…';
            try {
                const spot = await reverse(latlng.lat, latlng.lng);
                if (!spot.pincode) { mapText.textContent = 'No PIN code here. Move the pin a little or enter the PIN code.'; return; }
                picked = spot;
                mapText.textContent = `${spot.label ? spot.label + ' · ' : ''}PIN ${spot.pincode}`;
                mapConfirm.disabled = false;
            } catch (e) { mapText.textContent = 'Couldn’t look up this spot right now.'; }
        }
        mapOpen.addEventListener('click', async () => {
            mapWrap.hidden = !mapWrap.hidden;
            mapOpen.textContent = mapWrap.hidden ? 'Show map' : 'Hide map';
            if (mapWrap.hidden) return;
            try { await loadLeaflet(); } catch (e) { mapText.textContent = 'The map couldn’t load. Enter a PIN code instead.'; return; }
            if (!map) {
                const start = [20.5937, 78.9629];      // centre of India until we know better
                map = L.map(modal.querySelector('[data-mz-loc-map]')).setView(start, 5);
                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19, attribution: '&copy; OpenStreetMap contributors' }).addTo(map);
                marker = L.marker(start, { draggable: true }).addTo(map);
                marker.on('dragend', () => pinned(marker.getLatLng()));
                map.on('click', (e) => { marker.setLatLng(e.latlng); pinned(e.latlng); });
                if (navigator.geolocation) {
                    navigator.geolocation.getCurrentPosition((pos) => {
                        const here = [pos.coords.latitude, pos.coords.longitude];
                        map.setView(here, 16);
                        marker.setLatLng(here);
                        pinned(marker.getLatLng());
                    }, () => {}, { timeout: 10000, maximumAge: 60000 });
                }
            }
            setTimeout(() => map.invalidateSize(), 50);
        });
        mapConfirm.addEventListener('click', () => { if (picked) choose(picked.pincode, picked.label); });

        modal.addEventListener('show.bs.modal', () => {
            result.hidden = true;
            hits.replaceChildren();
            pinInput.value = modal.dataset.pincode || '';
        });
        modal.addEventListener('shown.bs.modal', () => { if (map) map.invalidateSize(); });
    })();

    // ---- address form: myself or someone else, address type, fill from this device's location, PIN code check ----
    document.querySelectorAll('[data-mz-address-form]').forEach(form => {
        const name = form.querySelector('[name="receiverName"]');
        const phone = form.querySelector('[name="receiverPhone"]');
        const mine = { name: form.dataset.myName || '', phone: form.dataset.myPhone || '' };
        const syncWho = (changed) => {
            const other = form.querySelector('[data-mz-for-whom]:checked')?.value === 'true';
            form.querySelector('[data-mz-receiver-title]').textContent = other ? 'Receiver’s details' : 'Your details';
            form.querySelector('[data-mz-receiver-name-label]').textContent = other ? 'Receiver’s name' : 'Your name';
            form.querySelector('[data-mz-receiver-phone-label]').textContent = other ? 'Receiver’s mobile number' : 'Your mobile number';
            if (!changed) return;
            if (other) {
                // Start empty for someone else, unless the customer already typed another person's details
                if (name.value === mine.name) name.value = '';
                if (phone.value === mine.phone) phone.value = '';
                name.focus();
            } else {
                if (!name.value) name.value = mine.name;
                if (!phone.value) phone.value = mine.phone;
            }
        };
        form.querySelectorAll('[data-mz-for-whom]').forEach(r => r.addEventListener('change', () => syncWho(true)));
        syncWho(false);

        const custom = form.querySelector('[data-mz-address-custom]');
        const syncType = () => {
            const other = form.querySelector('[data-mz-address-type]:checked')?.value === 'OTHER';
            custom.hidden = !other;
            if (other) custom.querySelector('input').focus();
        };
        form.querySelectorAll('[data-mz-address-type]').forEach(r => r.addEventListener('change', syncType));

        // Live "we deliver here" check on the PIN code
        const pin = form.querySelector('[data-mz-address-pin]');
        const pinMsg = form.querySelector('[data-mz-address-pin-msg]');
        let lastPin = '';
        const checkPin = async () => {
            const p = pinOf(pin.value);
            if (!p) { pinMsg.textContent = ''; lastPin = ''; return; }
            if (p === lastPin) return;
            lastPin = p;
            try {
                const res = await fetch(mzUrl('/api/location/check?pincode=' + p), { credentials: 'same-origin' });
                const data = await res.json();
                if (p !== lastPin) return;
                pinMsg.className = 'small ' + (data.serviceable ? 'text-success' : 'text-danger');
                pinMsg.innerHTML = data.serviceable
                    ? '<i class="bi bi-check-circle-fill me-1"></i>We deliver to this PIN code.'
                    : '<i class="bi bi-x-circle-fill me-1"></i>Sorry, we don’t deliver to this PIN code yet.';
            } catch (e) { pinMsg.textContent = ''; }
        };
        pin.addEventListener('input', () => { pin.value = pin.value.replace(/[^0-9 ]/g, ''); checkPin(); });
        checkPin();

        // Fill area, city, state and PIN code from where the device is
        const gps = form.querySelector('[data-mz-address-gps]');
        const gpsMsg = form.querySelector('[data-mz-address-gps-msg]');
        const say = (text, bad) => { gpsMsg.textContent = text; gpsMsg.className = 'small mb-3 ' + (bad ? 'text-danger' : 'text-muted'); gpsMsg.hidden = false; };
        gps.addEventListener('click', () => {
            if (!navigator.geolocation) { say('This browser can’t share its location.', true); return; }
            gps.disabled = true;
            say('Finding where you are…');
            navigator.geolocation.getCurrentPosition(async (pos) => {
                try {
                    const here = await reverse(pos.coords.latitude, pos.coords.longitude);
                    const a = here.address || {};
                    const set = (field, value) => { const el = form.querySelector(`[name="${field}"]`); if (value) el.value = value; };
                    set('area', [a.road, a.suburb || a.neighbourhood].filter(Boolean).join(', '));
                    set('city', a.city || a.town || a.village || a.state_district);
                    set('state', a.state);
                    set('pincode', here.pincode);
                    say('Filled in from your location. Add your flat or house number and check the rest.');
                    form.querySelector('[name="addressLine"]').focus();
                    checkPin();
                } catch (e) {
                    say('Couldn’t look up your location right now. Type the address instead.', true);
                } finally { gps.disabled = false; }
            }, (err) => {
                gps.disabled = false;
                say(err.code === err.PERMISSION_DENIED ? 'Location access is blocked. Allow it in your browser, or type the address.'
                    : 'Couldn’t get your location. Type the address instead.', true);
            }, { enableHighAccuracy: true, timeout: 15000, maximumAge: 60000 });
        });
    });

    // ---- phones: highlight the tab bar entry for this section ----
    document.querySelectorAll('.mz-tabbar [data-mz-tab]').forEach(tab => {
        const path = location.pathname.slice(base.length) || '/';
        const on = tab.dataset.mzTab.split(' ').some(p => p === '/' ? path === '/' : path === p || path.startsWith(p + '/'));
        tab.classList.toggle('active', on);
        if (on) tab.setAttribute('aria-current', 'page');
    });

    // ---- language: the footer picker translates the whole site through Google's website translator ----
    // The choice lives in Google's own "googtrans" cookie; its script is only loaded once a language other than English is picked.
    (function () {
        const picker = document.querySelector('[data-mz-lang]');
        const match = document.cookie.match(/(?:^|;\s*)googtrans=\/[^/;]*\/([^;]+)/);
        const lang = match ? decodeURIComponent(match[1]) : 'en';
        if (picker) {
            picker.value = [...picker.options].some(o => o.value === lang) ? lang : 'en';
            picker.addEventListener('change', () => {
                const host = location.hostname;
                // Google may have written the cookie on the bare host or the dotted domain: clear every copy
                ['', host, '.' + host].forEach(d => {
                    document.cookie = 'googtrans=; path=/; max-age=0' + (d ? '; domain=' + d : '');
                });
                if (picker.value !== 'en') document.cookie = 'googtrans=/en/' + picker.value + '; path=/; max-age=31536000; samesite=lax';
                location.reload();
            });
        }
        if (lang === 'en') return;
        const holder = document.createElement('div');
        holder.id = 'mzTranslate';
        holder.hidden = true;
        document.body.appendChild(holder);
        window.mzTranslateInit = () => new google.translate.TranslateElement({ pageLanguage: 'en', autoDisplay: false }, 'mzTranslate');
        const script = document.createElement('script');
        script.src = 'https://translate.google.com/translate_a/element.js?cb=mzTranslateInit';
        script.async = true;
        document.body.appendChild(script);
    })();

    // ---- help centre: filter questions as you type; topic chips only for sections on this page ----
    (function () {
        const search = document.querySelector('[data-mz-help-search]');
        if (!search) return;
        const groups = [...document.querySelectorAll('.mz-faq-group')];
        const empty = document.querySelector('[data-mz-help-empty]');
        document.querySelectorAll('[data-mz-help-topic]').forEach(chip => {
            if (!document.getElementById('help-' + chip.dataset.mzHelpTopic)) chip.remove();
        });
        // A link to a topic (from the footer) opens its first answer
        const target = location.hash && document.getElementById(decodeURIComponent(location.hash.slice(1)));
        if (target && target.classList.contains('mz-faq-group')) target.querySelector('details')?.setAttribute('open', '');

        search.addEventListener('input', () => {
            const words = search.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
            let shown = 0;
            groups.forEach(g => {
                let inGroup = 0;
                g.querySelectorAll('.mz-faq-item').forEach(item => {
                    const hit = words.every(w => item.textContent.toLowerCase().includes(w));
                    item.hidden = !hit;
                    if (words.length) item.open = hit && inGroup === 0;   // open the best (first) answer in each group
                    if (hit) inGroup++;
                });
                g.hidden = inGroup === 0;
                shown += inGroup;
            });
            if (empty) empty.hidden = shown > 0;
        });
    })();
})();
