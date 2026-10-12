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

    // ---- cart: who each medicine is for, saved as boxes are ticked ----
    document.addEventListener('change', async (e) => {
        const box = e.target;
        const form = box.closest('form[data-mz-members]');
        if (!form || box.type !== 'checkbox') return;
        const err = form.querySelector('.mz-qty-error');
        const ids = [...form.querySelectorAll('input[name="memberIds"]:checked')].map(i => Number(i.value));
        form.classList.add('is-busy');
        try {
            const res = await mzFetch('/api/cart/' + form.dataset.mzMembers + '/members', { method: 'POST', body: JSON.stringify({ memberIds: ids }) });
            if (res.redirected) { location.href = res.url; return; }
            const data = await res.json();
            if (!res.ok) {
                box.checked = !box.checked;                 // put the box back the way the server has it
                err.textContent = data.error || 'Could not save that.';
                err.hidden = false;
                clearTimeout(err._t);
                err._t = setTimeout(() => { err.hidden = true; }, 5000);
            } else {
                err.hidden = true;
            }
        } catch (ex) {
            box.checked = !box.checked;
            err.textContent = 'Could not reach MediZon. Check your connection.';
            err.hidden = false;
        } finally {
            form.classList.remove('is-busy');
        }
    });
    document.querySelectorAll('form[data-mz-members] noscript').forEach(n => n.remove());

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
        if (member) {
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
})();
