/* The payment page. MediZon Money pays what it can; the rest is paid by UPI (MediZon UPI's checkout, below), card or
   net banking (Razorpay Checkout, showing only that method), or on delivery. Online orders are placed once the server
   has confirmed the payment; the rest with "Place order". Either way an "Order placed" screen shows, then the order
   opens. No inline scripts: the CSP blocks them. */
(function () {
    const page = document.querySelector('[data-mz-payment]');
    if (!page) return;
    const d = page.dataset;
    const total = Number(d.total), balance = Number(d.balance);
    const wallet = page.querySelector('[data-mz-use-wallet]');
    const methods = [...page.querySelectorAll('input[name="method"]')];
    const list = page.querySelector('[data-mz-pay-methods]');
    const covered = page.querySelector('[data-mz-pay-covered]');
    const restTitle = page.querySelector('[data-mz-rest-title]');
    const buttons = [...page.querySelectorAll('[data-mz-pay-go]')];
    const error = page.querySelector('[data-mz-pay-error]');
    const overlay = document.querySelector('[data-mz-pay-overlay]');
    const money = (n) => '₹' + n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    let busy = false;

    /** What MediZon Money pays, what's left and how: WALLET when it covers everything. */
    const choice = () => {
        const used = wallet && wallet.checked ? Math.min(balance, total) : 0;
        const rest = Math.round((total - used) * 100) / 100;
        const picked = methods.find((m) => m.checked);
        return { used, rest, method: rest <= 0 ? 'WALLET' : picked ? picked.value : null };
    };
    const paysOnline = (method) => method !== null && method !== 'WALLET' && method !== 'COD';

    const sync = () => {
        const c = choice();
        page.querySelectorAll('[data-mz-wallet-line]').forEach((el) => { el.hidden = c.used === 0; });
        page.querySelectorAll('[data-mz-wallet-amount]').forEach((el) => { el.textContent = '− ' + money(c.used); });
        page.querySelectorAll('[data-mz-to-pay]').forEach((el) => { el.textContent = money(c.rest); });
        covered.hidden = c.rest > 0;
        list.hidden = c.rest <= 0;
        restTitle.textContent = c.used > 0 && c.rest > 0 ? 'Pay the remaining ' + money(c.rest) + ' with' : 'Pay with';
        const label = !c.method ? 'Choose how to pay' : paysOnline(c.method) ? 'Pay ' + money(c.rest) : 'Place order';
        buttons.forEach((b) => {
            b.querySelector('[data-mz-pay-label]').textContent = label;
            b.disabled = busy || !c.method;
        });
    };

    const showError = (text) => {
        error.hidden = !text;
        error.querySelector('[data-mz-pay-error-text]').textContent = text || '';
        if (text) error.scrollIntoView({ behavior: 'smooth', block: 'center' });
    };
    const setBusy = (on) => { busy = on; sync(); };
    const showBusy = (title) => {
        overlay.hidden = false;
        overlay.querySelector('[data-mz-pay-state="busy"]').hidden = false;
        overlay.querySelector('[data-mz-pay-state="done"]').hidden = true;
        overlay.querySelector('[data-mz-pay-busy-title]').textContent = title;
    };
    const fail = (message) => {
        overlay.hidden = true;
        setBusy(false);
        showError(message);
    };

    /** "Order placed": what was paid and the order numbers, then on to the order after a moment. */
    const done = (placed) => {
        overlay.hidden = false;
        overlay.querySelector('[data-mz-pay-state="busy"]').hidden = true;
        const box = overlay.querySelector('[data-mz-pay-state="done"]');
        box.hidden = false;
        const n = placed.orderNumbers.length;
        box.querySelector('[data-mz-pay-done-title]').textContent = n > 1 ? n + ' orders placed!' : 'Order placed!';
        box.querySelector('[data-mz-pay-paid]').textContent = placed.paid;
        box.querySelector('[data-mz-pay-numbers]').textContent = (n > 1 ? 'Orders ' : 'Order ') + placed.orderNumbers.join(', ');
        const view = box.querySelector('[data-mz-pay-view]');
        view.href = mzUrl(placed.redirect);
        view.textContent = n > 1 ? 'View orders' : 'View order';
        box.querySelector('[data-mz-pay-done-title]').focus();
        setTimeout(() => { location.href = view.href; }, 3200);
    };

    const post = async (path, body) => {
        let res;
        try {
            res = await mzFetch(path, { method: 'POST', body: JSON.stringify(body) });
        } catch (e) {
            throw new Error("You're offline or the connection dropped. Check it and try again.");
        }
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.error || 'Something went wrong. Please try again.');
        return data;
    };

    /** The account's mobile number as "+91" and 10 digits (accounts keep it as typed); null when it isn't one. */
    const mobile = (raw) => {
        const digits = (raw || '').replace(/\D/g, '').replace(/^(91|0)(?=\d{10}$)/, '');
        return /^[6-9]\d{9}$/.test(digits) ? '+91' + digits : null;
    };

    // ------------------------------------------------------------------ Razorpay: cards and net banking

    /** Razorpay Checkout showing only the method picked. Contact and email come from the account, so it doesn't ask. */
    const openRazorpay = (o) => {
        if (typeof window.Razorpay !== 'function') throw new Error("The payment window couldn't load. Check your connection and reload the page.");
        const contact = mobile(d.phone);
        let confirming = false, lastFailure = null;
        const checkout = new window.Razorpay({
            key: o.key,
            order_id: o.orderId,
            amount: o.amount,
            currency: 'INR',
            name: 'MediZon',
            description: 'Pay by ' + o.label,
            prefill: { name: d.name || '', email: d.email || '', contact: contact || '', method: o.method },
            readonly: { contact: !!contact, email: !!d.email },
            hidden: { contact: !!contact, email: !!d.email },
            config: {
                display: {
                    blocks: { medizon: { name: 'Pay by ' + o.label, instruments: [{ method: o.method }] } },
                    sequence: ['block.medizon'],
                    preferences: { show_default_blocks: false },
                },
            },
            theme: { color: '#0E6B57' },
            handler: async (r) => {
                confirming = true;
                showBusy('Confirming your payment');
                try {
                    done(await post('/api/checkout/pay/confirm',
                        { orderId: r.razorpay_order_id, paymentId: r.razorpay_payment_id, signature: r.razorpay_signature }));
                } catch (e) {
                    fail(e.message);
                }
            },
            modal: {
                ondismiss: () => {
                    if (confirming) return;
                    setBusy(false);
                    showError(lastFailure ? 'Payment failed: ' + lastFailure + ' No order was placed. Try again or choose another way to pay.'
                        : 'Payment cancelled. No order was placed; you can try again or choose another way to pay.');
                },
            },
        });
        checkout.on('payment.failed', (r) => { lastFailure = (r && r.error && r.error.description) || 'the bank declined it.'; });
        checkout.open();
    };

    // ------------------------------------------------------------------ MediZon UPI

    const upi = (() => {
        const root = document.querySelector('[data-mz-upi]');
        if (!root) return null;
        const q = (sel) => root.querySelector(sel);
        const steps = [...root.querySelectorAll('[data-mz-upi-step]')];
        const back = q('[data-mz-upi-back]');
        const idForm = q('[data-mz-upi-id-form]');
        const idInput = idForm.elements.upiId;
        const verifyBtn = q('[data-mz-upi-verify]');
        const requestBtn = q('[data-mz-upi-request]');
        const verified = q('[data-mz-upi-verified]');
        const idError = q('[data-mz-upi-id-error]');
        const dots = q('[data-mz-upi-dots]');
        const pinError = q('[data-mz-upi-pin-error]');
        let opened = null, payer = null, timer = null, deadline = 0, pin = '', pinLength = 4, wrongPins = 0, sending = false;

        const show = (name) => {
            steps.forEach((s) => { s.hidden = s.dataset.mzUpiStep !== name; });
            back.hidden = name !== 'wait' && name !== 'pin';
            root.dataset.step = name;
        };
        const close = (message) => {
            clearInterval(timer);
            root.hidden = true;
            document.body.classList.remove('mz-upi-open');
            opened = null;
            setBusy(false);
            if (message) showError(message);
        };

        /** Counts down the request's life; when it runs out the request is gone, as in a UPI app. */
        const tick = () => {
            const left = Math.max(0, Math.round((deadline - Date.now()) / 1000));
            q('[data-mz-upi-timer]').textContent = Math.floor(left / 60) + ':' + String(left % 60).padStart(2, '0');
            if (left === 0) close('The UPI payment request expired. No money was taken; you can try again.');
        };

        /** A QR code for the request, drawn from its id (test mode: decorative, no real app can pay it). */
        const drawQr = (seed) => {
            const canvas = q('[data-mz-upi-qr]');
            const ctx = canvas.getContext('2d');
            const n = 25, size = canvas.width / n;
            let h = 2166136261;
            for (const ch of seed) h = Math.imul(h ^ ch.charCodeAt(0), 16777619);
            const next = () => { h ^= h << 13; h ^= h >>> 17; h ^= h << 5; return (h >>> 0) / 4294967296; };
            ctx.fillStyle = '#fff';
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            ctx.fillStyle = '#17302A';
            const finder = (x, y) => {
                for (let i = 0; i < 7; i++) for (let j = 0; j < 7; j++) {
                    const ring = i === 0 || i === 6 || j === 0 || j === 6, core = i >= 2 && i <= 4 && j >= 2 && j <= 4;
                    if (ring || core) ctx.fillRect((x + i) * size, (y + j) * size, size, size);
                }
            };
            const inFinder = (i, j) => (i < 8 && j < 8) || (i > n - 9 && j < 8) || (i < 8 && j > n - 9);
            for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) {
                if (!inFinder(i, j) && next() > 0.52) ctx.fillRect(i * size, j * size, size, size);
            }
            finder(0, 0); finder(n - 7, 0); finder(0, n - 7);
        };

        const resetId = () => {
            payer = null;
            verified.hidden = true;
            idError.hidden = true;
            requestBtn.disabled = true;
            requestBtn.textContent = 'Verify your UPI ID to pay';
            verifyBtn.disabled = false;
            verifyBtn.textContent = 'Verify';
        };

        /** The request is with the customer's UPI app: "approve it there" (played on this screen in test mode). */
        const waitFor = (who) => {
            payer = who;
            const mark = q('[data-mz-upi-wait-mark]');
            mark.textContent = who.mark || 'UPI';
            mark.style.setProperty('--app', who.color || 'var(--mz-green)');
            q('[data-mz-upi-wait-title]').textContent = who.app ? 'Approve in ' + who.app : 'Approve the payment';
            q('[data-mz-upi-wait-text]').textContent = who.upiId
                ? 'We sent a payment request to ' + who.upiId + '. Open your UPI app and approve it.'
                : who.app ? 'Opening ' + who.app + '… Approve the payment there.' : 'Approve the payment in the UPI app you scanned with.';
            q('[data-mz-upi-phone-label]').textContent = (who.app || 'Your UPI app') + ' (test)';
            show('wait');
        };

        const renderPin = () => {
            dots.innerHTML = '';
            for (let i = 0; i < pinLength; i++) {
                const dot = document.createElement('span');
                dot.className = i < pin.length ? 'is-filled' : '';
                dots.appendChild(dot);
            }
            q('[data-mz-upi-pin-title]').textContent = 'ENTER ' + pinLength + '-DIGIT UPI PIN';
            q('[data-mz-upi-pin-switch]').textContent = pinLength === 4 ? 'Use a 6-digit PIN' : 'Use a 4-digit PIN';
            root.querySelector('[data-mz-upi-key="ok"]').disabled = pin.length !== pinLength;
        };
        const askPin = () => {
            pin = '';
            pinError.hidden = true;
            q('[data-mz-upi-from]').textContent = payer.upiId ? 'From ' + payer.upiId : 'From your ' + (payer.app || 'UPI') + ' account';
            renderPin();
            show('pin');
        };

        /** The PIN is checked here, as a UPI app checks it; "0000" plays a wrong PIN. Then the server takes the payment. */
        const submitPin = async () => {
            if (pin.length !== pinLength || sending) return;
            if (/^0+$/.test(pin)) {
                wrongPins++;
                pin = '';
                renderPin();
                pinError.hidden = false;
                pinError.textContent = wrongPins >= 3 ? '' : 'Incorrect UPI PIN. ' + (3 - wrongPins) + (3 - wrongPins === 1 ? ' attempt' : ' attempts') + ' left.';
                if (wrongPins >= 3) close('Too many wrong UPI PIN attempts. No money was taken; try again later or choose another way to pay.');
                return;
            }
            sending = true;
            show('processing');
            try {
                const placed = await post('/api/checkout/upi/approve', { orderId: opened.orderId, upiId: payer.upiId || null });
                clearInterval(timer);
                root.hidden = true;
                document.body.classList.remove('mz-upi-open');
                done(placed);
            } catch (e) {
                close(e.message);
            } finally {
                sending = false;
            }
        };

        // Pick a UPI app
        root.querySelectorAll('[data-mz-upi-app]').forEach((b) => b.addEventListener('click', () =>
            waitFor({ app: b.dataset.mzUpiApp, mark: b.dataset.mzUpiMark, color: getComputedStyle(b).getPropertyValue('--app') })));
        // Or a UPI ID: verified first, as UPI apps do
        idInput.addEventListener('input', resetId);
        root.querySelectorAll('[data-mz-upi-handle]').forEach((b) => b.addEventListener('click', () => {
            const name = idInput.value.trim().split('@')[0] || (mobile(d.phone) || '').replace('+91', '');
            idInput.value = name + b.dataset.mzUpiHandle;
            resetId();
            idInput.focus();
        }));
        idForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const value = idInput.value.trim();
            if (!value) { idError.hidden = false; idError.textContent = 'Enter your UPI ID, like 9876543210@ybl.'; return; }
            verifyBtn.disabled = true;
            verifyBtn.textContent = 'Verifying…';
            try {
                const r = await post('/api/checkout/upi/verify', { upiId: value });
                q('[data-mz-upi-name]').textContent = r.name;
                verified.hidden = false;
                idError.hidden = true;
                payer = { upiId: r.upiId };
                requestBtn.disabled = false;
                requestBtn.textContent = 'Pay ' + money(opened.amount / 100);
                verifyBtn.textContent = 'Verified';
            } catch (err) {
                idError.hidden = false;
                idError.textContent = err.message;
                verifyBtn.disabled = false;
                verifyBtn.textContent = 'Verify';
            }
        });
        requestBtn.addEventListener('click', () => { if (payer && payer.upiId) waitFor({ upiId: payer.upiId }); });
        // Or scan the QR
        q('[data-mz-upi-scan]').addEventListener('click', () => waitFor({ app: null }));

        // In the UPI app: decline, or pay and enter the PIN
        q('[data-mz-upi-decline]').addEventListener('click', () => close('You declined the payment in your UPI app. No money was taken.'));
        q('[data-mz-upi-approve]').addEventListener('click', askPin);
        q('[data-mz-upi-pin-switch]').addEventListener('click', () => { pinLength = pinLength === 4 ? 6 : 4; pin = ''; renderPin(); });
        root.querySelectorAll('[data-mz-upi-key]').forEach((b) => b.addEventListener('click', () => {
            const key = b.dataset.mzUpiKey;
            if (key === 'ok') return submitPin();
            pinError.hidden = true;
            if (key === 'back') pin = pin.slice(0, -1);
            else if (pin.length < pinLength) pin += key;
            renderPin();
        }));
        document.addEventListener('keydown', (e) => {
            if (root.hidden) return;
            if (e.key === 'Escape' && root.dataset.step !== 'processing') { close('Payment cancelled. No order was placed.'); return; }
            if (root.dataset.step !== 'pin') return;
            if (/^\d$/.test(e.key) && pin.length < pinLength) { pin += e.key; pinError.hidden = true; renderPin(); }
            else if (e.key === 'Backspace') { pin = pin.slice(0, -1); renderPin(); }
            else if (e.key === 'Enter') submitPin();
            else return;
            e.preventDefault();
        });

        back.addEventListener('click', () => show(root.dataset.step === 'pin' ? 'wait' : 'choose'));
        q('[data-mz-upi-close]').addEventListener('click', () => {
            if (root.dataset.step !== 'processing') close('Payment cancelled. No order was placed; you can try again or choose another way to pay.');
        });

        return {
            /** Opens MediZon UPI's checkout for the payment the server opened. */
            open(o) {
                opened = o;
                wrongPins = 0;
                root.querySelectorAll('[data-mz-upi-amount]').forEach((el) => { el.textContent = money(o.amount / 100); });
                idInput.value = '';
                resetId();
                drawQr(o.orderId);
                deadline = Date.now() + o.expiresIn * 1000;
                clearInterval(timer);
                timer = setInterval(tick, 1000);
                tick();
                show('choose');
                root.hidden = false;
                document.body.classList.add('mz-upi-open');
                root.querySelector('.mz-upi-app').focus();
            },
        };
    })();

    const go = async () => {
        const c = choice();
        if (!c.method || busy) return;
        showError('');
        setBusy(true);
        const body = { addressId: Number(d.address), method: c.method, useWallet: c.used > 0 };
        try {
            if (paysOnline(c.method)) {
                const opened = await post('/api/checkout/pay', body);
                if (opened.gateway === 'MEDIZON_UPI' && upi) upi.open(opened);
                else openRazorpay(opened);
            } else {
                showBusy('Placing your order');
                done(await post('/api/checkout/place', body));
            }
        } catch (e) {
            fail(e.message);
        }
    };

    wallet?.addEventListener('change', sync);
    methods.forEach((m) => m.addEventListener('change', sync));
    buttons.forEach((b) => b.addEventListener('click', go));
    sync();
})();
