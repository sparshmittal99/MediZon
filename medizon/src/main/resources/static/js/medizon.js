/* MediZon front-end helpers: CSRF-aware fetch, notification polling, desktop alerts, confirm dialogs. */
(function () {
    const meta = (name) => document.querySelector(`meta[name="${name}"]`)?.content;
    const base = (meta('mz_base') || '/').replace(/\/$/, '');

    window.mzUrl = (path) => base + path;

    window.mzFetch = function (path, options = {}) {
        const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
        const header = meta('_csrf_header'), token = meta('_csrf');
        if (header && token) headers[header] = token;
        return fetch(mzUrl(path), Object.assign({}, options, { headers, credentials: 'same-origin' }));
    };

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
        events.addEventListener('open', () => poll());       // catch up after a reconnect
    }

    // Logged-in pages carry the bell; only those listen and poll.
    if (document.getElementById('mzBellCount')) {
        poll();
        setInterval(poll, 30000);
        if (tabs) tabs.onmessage = () => poll();
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
        if (row) {
            if (data.quantity === 0) row.remove();
            else row.querySelector('[data-mz-line-total]').textContent = data.lineTotal;
        }
        summary.querySelector('[data-mz-subtotal]').textContent = data.subtotal;
        summary.querySelector('[data-mz-delivery]').textContent = data.delivery;
        summary.querySelector('[data-mz-total]').textContent = data.total;
        summary.querySelector('[data-mz-free-note]').hidden = data.freeDelivery;
        summary.querySelector('[data-mz-rx-note]').hidden = !data.prescriptionRequired;
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
})();
