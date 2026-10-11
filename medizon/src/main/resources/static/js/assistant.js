/* MediZon Assistant: the floating chat in the bottom-right corner.
   Talks to /api/assistant (which forwards to the built-in Python assistant). The conversation lives in the
   server session, so it carries on across pages; whether the panel is open is remembered per tab. */
(function () {
    const root = document.getElementById('mzBot');
    if (!root) return;

    const panel = root.querySelector('.mz-bot-panel');
    const launcher = root.querySelector('.mz-bot-launcher');
    const log = root.querySelector('.mz-bot-log');
    const chipsBar = root.querySelector('.mz-bot-chips');
    const form = root.querySelector('.mz-bot-form');
    const input = root.querySelector('.mz-bot-input');
    const sendBtn = root.querySelector('.mz-bot-send');
    const status = root.querySelector('[data-mz-bot-status]');
    const teaser = root.querySelector('.mz-bot-teaser');
    const firstName = root.dataset.firstName || '';
    const isAdmin = root.dataset.role === 'admin';

    const store = {
        get(k) { try { return sessionStorage.getItem(k); } catch (e) { return null; } },
        set(k, v) { try { sessionStorage.setItem(k, v); } catch (e) { /* private mode */ } },
        del(k) { try { sessionStorage.removeItem(k); } catch (e) { /* private mode */ } },
    };
    const url = (path) => (typeof path === 'string' && path.startsWith('/')) ? mzUrl(path) : path;
    let loaded = false, busy = false;

    // ---------------------------------------------------------------- text formatting (safe: escape first)
    function esc(s) {
        return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }
    function format(text) {
        return esc(text)
            .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
            .replace(/(^|[\s(])_(.+?)_(?=[\s.,)!?]|$)/g, '$1<em>$2</em>')
            .replace(/\n/g, '<br>');
    }

    // ---------------------------------------------------------------- rendering
    function scrollDown() { log.scrollTop = log.scrollHeight; }

    function bubble(from, text) {
        const el = document.createElement('div');
        el.className = 'mz-bot-msg ' + (from === 'user' ? 'is-user' : 'is-bot');
        el.innerHTML = format(text);
        log.appendChild(el);
        return el;
    }

    function actionButton(a) {
        if (a.href) {
            const link = document.createElement('a');
            link.className = 'btn btn-sm ' + (a.primary ? 'btn-primary' : 'btn-outline-primary');
            link.href = url(a.href);
            link.textContent = a.label;
            return link;
        }
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'btn btn-sm ' + (a.primary ? 'btn-primary' : 'btn-outline-primary');
        b.textContent = a.label;
        b.addEventListener('click', () => send({ payload: a.payload, label: a.label + (a.payload?.medicine_name ? ': ' + a.payload.medicine_name : '') }));
        return b;
    }

    function card(c) {
        const el = document.createElement('div');
        el.className = 'mz-bot-card' + (c.type === 'info' ? ' is-info' : '');
        if (c.type === 'medicine') {
            const thumb = document.createElement('a');
            thumb.className = 'mz-bot-thumb';
            thumb.href = url(c.url);
            thumb.setAttribute('aria-hidden', 'true');
            thumb.tabIndex = -1;
            thumb.innerHTML = c.image ? `<img src="${esc(url(c.image))}" alt="">` : '<i class="bi bi-capsule-pill"></i>';
            el.appendChild(thumb);
        }
        const body = document.createElement('div');
        body.className = 'mz-bot-card-body';
        body.innerHTML = `<a class="mz-bot-card-title" href="${esc(url(c.url))}">${esc(c.title)}</a>`
            + (c.badge ? ` <span class="mz-bot-badge">${esc(c.badge)}</span>` : '')
            + (c.rx ? ' <span class="mz-bot-rx" title="Prescription required">Rx</span>' : '')
            + (c.subtitle ? `<div class="mz-bot-card-sub">${esc(c.subtitle)}</div>` : '')
            + (c.price ? `<div class="mz-bot-price">${esc(c.price)}${c.mrp ? ` <s>${esc(c.mrp)}</s>` : ''}`
                + (c.type === 'medicine' && !c.in_stock && !isAdmin ? ' <span class="mz-bot-oos">Out of stock</span>' : '') + '</div>' : '');
        if (c.actions?.length) {
            const row = document.createElement('div');
            row.className = 'mz-bot-actions';
            c.actions.forEach(a => row.appendChild(actionButton(a)));
            body.appendChild(row);
        }
        el.appendChild(body);
        return el;
    }

    /** Renders a bot turn and returns its first element. */
    function renderBot(m) {
        let first = null;
        if (m.text) first = bubble('bot', m.text);
        if (m.cards?.length) {
            const wrap = document.createElement('div');
            wrap.className = 'mz-bot-cards';
            m.cards.forEach(c => wrap.appendChild(card(c)));
            log.appendChild(wrap);
            first = first || wrap;
        }
        return first;
    }

    /** Short replies: scroll to the bottom. Long ones: show where the reply starts so it reads top-down. */
    function reveal(el) {
        if (el && log.scrollHeight - el.offsetTop > log.clientHeight) log.scrollTop = el.offsetTop - 12;
        else scrollDown();
    }

    function setChips(list) {
        chipsBar.innerHTML = '';
        (list || []).forEach(s => {
            let el;
            if (s.href) {
                el = document.createElement('a');
                el.href = url(s.href);
            } else {
                el = document.createElement('button');
                el.type = 'button';
                el.addEventListener('click', () => s.payload ? send({ payload: s.payload, label: s.label })
                                                              : send({ message: s.message || s.label }));
            }
            el.className = 'mz-bot-chip';
            el.textContent = s.label;
            chipsBar.appendChild(el);
        });
        chipsBar.hidden = !chipsBar.children.length;
    }

    function typing(on) {
        let t = log.querySelector('.mz-bot-typing');
        if (on && !t) {
            t = document.createElement('div');
            t.className = 'mz-bot-msg is-bot mz-bot-typing';
            t.setAttribute('aria-label', 'Assistant is typing');
            t.innerHTML = '<span></span><span></span><span></span>';
            log.appendChild(t);
            scrollDown();
        } else if (!on && t) {
            t.remove();
        }
    }

    // ---------------------------------------------------------------- talking to the server
    async function send({ message, payload, label, silent }) {
        if (busy) return;
        const text = (message || '').trim();
        if (!text && !payload) return;
        busy = true;
        sendBtn.disabled = true;
        if (!silent) bubble('user', text || label || '…');
        setChips([]);
        typing(true);
        try {
            const res = await mzFetch('/api/assistant/chat', {
                method: 'POST',
                body: JSON.stringify({ message: text, payload: payload || null, label: label || null }),
            });
            if (res.status === 401 || res.redirected) { location.href = mzUrl('/login'); return; }
            const data = await res.json();
            typing(false);
            const first = renderBot({ text: data.reply, cards: data.cards });
            setChips(data.suggestions);
            status.textContent = data.offline ? 'Offline' : 'Online';
            root.classList.toggle('is-offline', !!data.offline);
            if (data.cartCount !== null && data.cartCount !== undefined) {
                if (window.mzSetBadge) mzSetBadge('mzCartCount', data.cartCount);
                document.dispatchEvent(new CustomEvent('mz:cart-changed'));
            }
            if (data.navigate) {
                store.set('mzBotOpen', '1');
                setTimeout(() => { location.href = mzUrl(data.navigate); }, 900);
            }
            reveal(first);
        } catch (e) {
            typing(false);
            bubble('bot', "I couldn't reach the server. Check your connection and try again.");
            scrollDown();
        } finally {
            busy = false;
            sendBtn.disabled = false;
        }
    }

    async function loadHistory() {
        if (loaded) return;
        loaded = true;
        try {
            const res = await fetch(mzUrl('/api/assistant/history'), { credentials: 'same-origin' });
            const data = res.ok ? await res.json() : { messages: [] };
            const messages = data.messages || [];
            messages.forEach(m => m.from === 'user' ? bubble('user', m.text) : renderBot(m));
            const lastBot = [...messages].reverse().find(m => m.from === 'bot');
            setChips(lastBot ? lastBot.suggestions : []);
            scrollDown();
            if (!messages.length) send({ payload: { intent: 'greet' }, silent: true });
        } catch (e) {
            send({ payload: { intent: 'greet' }, silent: true });
        }
    }

    // ---------------------------------------------------------------- open / close
    function open(focus = true) {
        panel.hidden = false;
        requestAnimationFrame(() => root.classList.add('is-open'));
        launcher.setAttribute('aria-expanded', 'true');
        launcher.setAttribute('aria-label', 'Close MediZon assistant');
        teaser.hidden = true;
        store.set('mzBotOpen', '1');
        store.set('mzBotTeased', '1');
        loadHistory().then(scrollDown);
        if (focus) setTimeout(() => input.focus(), 150);
    }

    function close() {
        root.classList.remove('is-open');
        launcher.setAttribute('aria-expanded', 'false');
        launcher.setAttribute('aria-label', 'Open MediZon assistant');
        store.del('mzBotOpen');
        setTimeout(() => { if (!root.classList.contains('is-open')) panel.hidden = true; }, 200);
    }

    launcher.addEventListener('click', () => root.classList.contains('is-open') ? close() : open());
    root.querySelector('[data-mz-bot-close]').addEventListener('click', () => { close(); launcher.focus(); });
    root.querySelector('[data-mz-bot-reset]').addEventListener('click', async () => {
        await mzFetch('/api/assistant/reset', { method: 'POST' }).catch(() => {});
        log.innerHTML = '';
        setChips([]);
        send({ payload: { intent: 'greet' }, silent: true });
        input.focus();
    });
    panel.addEventListener('keydown', (e) => { if (e.key === 'Escape') { close(); launcher.focus(); } });

    form.addEventListener('submit', (e) => {
        e.preventDefault();
        const text = input.value;
        input.value = '';
        send({ message: text });
    });

    // Buttons elsewhere on the page: <button data-mz-ask="Tell me about Paracetamol">
    document.addEventListener('click', (e) => {
        const trigger = e.target.closest('[data-mz-ask], [data-mz-ask-payload]');
        if (!trigger) return;
        e.preventDefault();
        open(false);
        const payload = trigger.dataset.mzAskPayload ? JSON.parse(trigger.dataset.mzAskPayload) : null;
        const ask = () => payload ? send({ payload, label: trigger.textContent.trim() }) : send({ message: trigger.dataset.mzAsk });
        // let the history load first so the question lands after it
        setTimeout(ask, loaded ? 0 : 400);
    });

    // ---------------------------------------------------------------- first visit in this tab
    if (store.get('mzBotOpen')) {
        open(false);
    } else if (!store.get('mzBotTeased')) {
        teaser.querySelector('.mz-bot-teaser-text').textContent = isAdmin
            ? `Hi${firstName ? ' ' + firstName : ''}! Want a quick summary of what needs your attention?`
            : `Hi${firstName ? ' ' + firstName : ''}! Need help finding a medicine or setting a reminder?`;
        setTimeout(() => { if (!root.classList.contains('is-open')) teaser.hidden = false; }, 1500);
        teaser.querySelector('.mz-bot-teaser-text').addEventListener('click', () => open());
        teaser.querySelector('.mz-bot-teaser-close').addEventListener('click', () => {
            teaser.hidden = true;
            store.set('mzBotTeased', '1');
        });
    }
})();
