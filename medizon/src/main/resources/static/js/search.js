/* Search suggestions, Amazon style, from /api/search/suggest (which understands part of a name, typos and spellings
   by sound, so shoppers don't need the exact name).
   - Wide screens: type in the header's box; suggestions drop down right under it, the same width, and the page
     behind dims. Arrow keys walk the list (the box shows the highlighted one), Enter searches, Esc closes.
   - Phones and the Android app: tapping the box opens a full-screen search with its own bar (back, box, mic).
     Each row has the ↖ arrow that puts it in the box to keep typing; back closes it.
   Both show recent searches (this device) when the box is empty. Choosing a suggestion searches for it. */
(function () {
    const headerForm = document.querySelector('form.mz-search');
    const headerInput = headerForm && headerForm.querySelector('input[name="q"]');
    if (!headerInput || typeof window.mzUrl !== 'function') return;

    const RECENT_KEY = 'mzRecentSearches';
    const MAX_RECENT = 8;
    const voice = window.mzVoice;
    const inApp = !!window.MediZonApp;
    const wideMq = window.matchMedia('(min-width: 768px)');
    const isWide = () => wideMq.matches && !inApp;
    const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const searchUrl = (q) => mzUrl('medicines') + '?q=' + encodeURIComponent(q);
    const categoryUrl = (c) => mzUrl('medicines') + '?category=' + encodeURIComponent(c);
    headerInput.setAttribute('autocomplete', 'off');   // the browser's own history list would cover ours

    // ---------------------------------------------------------------- recent searches (this device only)
    function recent() {
        try { const r = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]'); return Array.isArray(r) ? r : []; } catch (e) { return []; }
    }
    function saveRecent(items) { try { localStorage.setItem(RECENT_KEY, JSON.stringify(items)); } catch (e) { /* private mode */ } }
    function remember(q) {
        const t = String(q || '').trim();
        if (t) saveRecent([t, ...recent().filter(r => r.toLowerCase() !== t.toLowerCase())].slice(0, MAX_RECENT));
    }
    function forget(q) { saveRecent(recent().filter(x => x !== q)); }

    /** Categories from the page's own menu, for the full-screen search's empty state. */
    function categories() {
        const seen = new Set(), out = [];
        document.querySelectorAll('a[href*="category="]').forEach(a => {
            const name = a.textContent.trim();
            if (name && !seen.has(name.toLowerCase()) && !a.closest('.mz-sx, .mz-sd')) { seen.add(name.toLowerCase()); out.push(name); }
        });
        return out.slice(0, 14);
    }

    /** Amazon's look: what was typed stays plain, the rest of the suggestion is bold. */
    function completion(text, q) {
        const words = q.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean).sort((a, b) => b.length - a.length);
        if (!words.length) return `<b>${esc(text)}</b>`;
        const re = new RegExp('(' + words.map(w => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|') + ')', 'gi');
        return text.split(re).map((part, i) => !part ? '' : i % 2 ? esc(part) : `<b>${esc(part)}</b>`).join('');
    }

    // ---------------------------------------------------------------- suggestions, newest typing wins
    const cache = new Map();
    let timer = null, controller = null, seq = 0;

    function suggest(q, show) {
        clearTimeout(timer);
        const key = q.toLowerCase();
        if (cache.has(key)) { show(cache.get(key)); return; }
        timer = setTimeout(async () => {
            if (controller) controller.abort();
            controller = new AbortController();
            const mine = ++seq;
            document.documentElement.classList.add('mz-search-loading');
            try {
                const res = await fetch(mzUrl('api/search/suggest') + '?q=' + encodeURIComponent(q),
                    { credentials: 'same-origin', headers: { Accept: 'application/json' }, signal: controller.signal });
                if (!res.ok) throw new Error('HTTP ' + res.status);
                const data = await res.json();
                cache.set(key, data);
                if (cache.size > 60) cache.delete(cache.keys().next().value);
                if (mine === seq) show(data);
            } catch (e) {
                if (e.name !== 'AbortError' && mine === seq) show({ medicines: [], categories: [] });   // Enter still searches
            } finally {
                if (mine === seq) document.documentElement.classList.remove('mz-search-loading');
            }
        }, 120);
    }
    function cancelSuggest() { clearTimeout(timer); if (controller) controller.abort(); seq++; }

    /**
     * The rows for a query: matching recent searches first, then medicine names (the top one says its category,
     * like Amazon's "in Electronics"), then categories. Each: { text, url, kind, hint }.
     */
    function rowsFor(q, data) {
        const rows = [], seen = new Set();
        const add = (r) => { const k = r.kind + r.text.toLowerCase(); if (!seen.has(k)) { seen.add(k); rows.push(r); } };
        recent().filter(r => r.toLowerCase().startsWith(q.toLowerCase()) && r.toLowerCase() !== q.toLowerCase())
            .slice(0, 2).forEach(r => add({ text: r, url: searchUrl(r), kind: 'recent' }));
        (data.medicines || []).forEach((m, i) => add({ text: m.name, url: searchUrl(m.name), kind: 'medicine',
            hint: i < 2 && m.category ? 'in ' + m.category : null }));
        (data.categories || []).forEach(c => add({ text: c, url: categoryUrl(c), kind: 'category', hint: 'Category' }));
        return rows;
    }

    /** One list for either mode. fill: the ↖ arrow (full screen). remove: how a recent search is removed. */
    function renderRows(list, rows, q, { fill, onFill, onRemove }) {
        list.innerHTML = '';
        rows.forEach((r, i) => {
            const li = document.createElement('li');
            li.className = 'mz-sx-item is-' + r.kind;
            li.id = list.id + '-' + i;
            li.setAttribute('role', 'option');
            li.dataset.text = r.text;
            const icon = r.kind === 'recent' ? 'bi-clock-history' : r.kind === 'category' ? 'bi-grid' : 'bi-search';
            li.innerHTML = `<a class="mz-sx-link-row" href="${esc(r.url)}" tabindex="-1">
                    <i class="bi ${icon} mz-sx-ico" aria-hidden="true"></i>
                    <span class="mz-sx-text"><span class="mz-sx-title">${q ? completion(r.text, q) : esc(r.text)}</span>${
                        r.hint ? ` <span class="mz-sx-in">${esc(r.hint)}</span>` : ''}</span></a>`;
            li.querySelector('a').addEventListener('click', () => { if (r.kind !== 'category') remember(r.text); });
            if (r.kind === 'recent' && onRemove) {
                const del = document.createElement('button');
                del.type = 'button';
                del.className = 'mz-sx-del';
                del.setAttribute('aria-label', 'Remove ' + r.text + ' from recent searches');
                del.innerHTML = fill ? '<i class="bi bi-x-lg"></i>' : 'Remove';
                del.addEventListener('mousedown', (e) => e.preventDefault());
                del.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); forget(r.text); onRemove(); });
                li.appendChild(del);
            } else if (fill && r.kind !== 'category') {
                const b = document.createElement('button');
                b.type = 'button';
                b.className = 'mz-sx-fill';
                b.setAttribute('aria-label', 'Edit search: ' + r.text);
                b.innerHTML = '<i class="bi bi-arrow-up-left"></i>';
                b.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); onFill(r.text); });
                li.appendChild(b);
            }
            list.appendChild(li);
        });
    }

    /** Arrow keys: moves the highlight and shows that suggestion in the box, back to what was typed past the ends. */
    function stepper(list, box) {
        let active = -1, typed = '';
        return {
            reset() { active = -1; typed = box.value; box.removeAttribute('aria-activedescendant'); },
            get active() { return active; },
            move(delta) {
                const items = [...list.querySelectorAll('.mz-sx-item')];
                if (!items.length) return;
                if (active === -1) typed = box.value;
                active += delta;
                if (active >= items.length) active = -1;
                if (active < -1) active = items.length - 1;
                items.forEach((el, i) => el.classList.toggle('is-active', i === active));
                if (active >= 0) {
                    box.value = items[active].dataset.text;
                    box.setAttribute('aria-activedescendant', items[active].id);
                    items[active].scrollIntoView({ block: 'nearest' });
                } else {
                    box.value = typed;
                    box.removeAttribute('aria-activedescendant');
                }
            },
            activeItem() { return active >= 0 ? list.querySelectorAll('.mz-sx-item')[active] : null; },
        };
    }

    // ================================================================ wide screens: the dropdown under the box
    const drop = document.createElement('div');
    drop.className = 'mz-sd';
    drop.hidden = true;
    drop.innerHTML = '<div class="mz-sd-fix" hidden></div><ul class="mz-sx-list" id="mzSdList" role="listbox" aria-label="Search suggestions"></ul>';
    const dropShade = document.createElement('div');
    dropShade.className = 'mz-sd-shade';
    dropShade.hidden = true;
    document.body.append(dropShade, drop);
    const dropList = drop.querySelector('ul');
    const dropFix = drop.querySelector('.mz-sd-fix');
    const dropKeys = stepper(dropList, headerInput);
    const nav = headerForm.closest('.mz-nav') || headerForm.parentElement;
    headerInput.setAttribute('role', 'combobox');
    headerInput.setAttribute('aria-controls', 'mzSdList');
    headerInput.setAttribute('aria-autocomplete', 'list');
    headerInput.setAttribute('aria-expanded', 'false');

    function placeDrop() {
        const r = headerForm.getBoundingClientRect();
        drop.style.left = r.left + 'px';
        drop.style.top = r.bottom + 'px';
        drop.style.width = r.width + 'px';
        dropShade.style.top = Math.max(0, nav.getBoundingClientRect().bottom) + 'px';
    }

    function showDrop(rows, q, fixText) {
        renderRows(dropList, rows, q, { fill: false, onRemove: refreshDrop });
        dropKeys.reset();
        dropFix.hidden = !fixText;
        if (fixText) dropFix.innerHTML = `Showing results for <b>${esc(fixText)}</b>`;
        const any = rows.length > 0;
        drop.hidden = !any;
        dropShade.hidden = false;
        headerForm.classList.toggle('is-suggesting', any);
        headerInput.setAttribute('aria-expanded', String(any));
        placeDrop();
    }

    function refreshDrop() {
        if (!isWide() || document.activeElement !== headerInput) return;
        const q = headerInput.value.trim();
        if (!q) {
            cancelSuggest();
            showDrop(recent().map(r => ({ text: r, url: searchUrl(r), kind: 'recent' })), '', null);
            return;
        }
        suggest(q, (data) => {
            if (headerInput.value.trim() !== q || document.activeElement !== headerInput) return;
            showDrop(rowsFor(q, data), q, data.didYouMean && (data.medicines || []).length ? data.didYouMean : null);
        });
    }

    function hideDrop() {
        cancelSuggest();
        drop.hidden = true;
        dropShade.hidden = true;
        headerForm.classList.remove('is-suggesting');
        headerInput.setAttribute('aria-expanded', 'false');
    }

    drop.addEventListener('mousedown', (e) => e.preventDefault());    // keep the box focused while clicking a row
    dropShade.addEventListener('mousedown', (e) => { e.preventDefault(); headerInput.blur(); });
    window.addEventListener('resize', () => { if (!drop.hidden) placeDrop(); });
    window.addEventListener('scroll', () => { if (!dropShade.hidden) placeDrop(); }, { passive: true });

    // ================================================================ phones and the app: full screen
    const sx = document.createElement('div');
    sx.className = 'mz-sx';
    sx.hidden = true;
    sx.innerHTML = `
        <div class="mz-sx-sheet" role="dialog" aria-modal="true" aria-label="Search medicines">
            <form class="mz-sx-bar" role="search" autocomplete="off">
                <button type="button" class="mz-sx-btn mz-sx-back" data-sx-close aria-label="Close search"><i class="bi bi-arrow-left"></i></button>
                <div class="mz-sx-box">
                    <input class="mz-sx-input" type="search" name="q" enterkeyhint="search" autocomplete="off" autocapitalize="off"
                           spellcheck="false" placeholder="Search medicines, salts or brands" aria-label="Search medicines"
                           role="combobox" aria-expanded="false" aria-controls="mzSxList" aria-autocomplete="list">
                    <button type="button" class="mz-sx-clear" aria-label="Clear" hidden><i class="bi bi-x-circle-fill"></i></button>
                    <button type="button" class="mz-sx-mic" aria-label="Search by voice" hidden><i class="bi bi-mic-fill"></i></button>
                </div>
            </form>
            <div class="mz-sx-body">
                <div class="mz-sx-fix" hidden></div>
                <div class="mz-sx-head" data-sx-recent-head hidden><h2>Recent searches</h2><button type="button" class="mz-sx-link" data-sx-clear-recent>Clear all</button></div>
                <ul class="mz-sx-list" id="mzSxList" role="listbox" aria-label="Suggestions"></ul>
                <div class="mz-sx-empty" hidden></div>
                <section class="mz-sx-cats" hidden>
                    <div class="mz-sx-head"><h2>Shop by category</h2></div>
                    <div class="mz-sx-chips"></div>
                </section>
            </div>
        </div>`;
    document.body.appendChild(sx);
    const sxInput = sx.querySelector('.mz-sx-input');
    const sxClear = sx.querySelector('.mz-sx-clear');
    const sxMic = sx.querySelector('.mz-sx-mic');
    const sxList = sx.querySelector('#mzSxList');
    const sxFix = sx.querySelector('.mz-sx-fix');
    const sxEmpty = sx.querySelector('.mz-sx-empty');
    const sxRecentHead = sx.querySelector('[data-sx-recent-head]');
    const sxCats = sx.querySelector('.mz-sx-cats');
    const sxKeys = stepper(sxList, sxInput);
    const fillBox = (t) => { sxInput.value = t + ' '; sxInput.focus(); updateSx(); };

    function updateSx() {
        const q = sxInput.value.trim();
        sxClear.hidden = !sxInput.value;
        sxMic.hidden = !!sxInput.value || !(voice && voice.canListen());
        sxEmpty.hidden = true;
        if (!q) {
            cancelSuggest();
            sxFix.hidden = true;
            const r = recent();
            renderRows(sxList, r.map(t => ({ text: t, url: searchUrl(t), kind: 'recent' })), '', { fill: true, onFill: fillBox, onRemove: updateSx });
            sxRecentHead.hidden = !r.length;
            const cats = categories();
            sxCats.querySelector('.mz-sx-chips').innerHTML = cats.map(c => `<a class="mz-chip" href="${esc(categoryUrl(c))}">${esc(c)}</a>`).join('');
            sxCats.hidden = !cats.length;
            sxKeys.reset();
            return;
        }
        suggest(q, (data) => {
            if (sxInput.value.trim() !== q || sx.hidden) return;
            sxRecentHead.hidden = true;
            sxCats.hidden = true;
            const rows = rowsFor(q, data);
            renderRows(sxList, rows, q, { fill: true, onFill: fillBox, onRemove: updateSx });
            sxKeys.reset();
            sxFix.hidden = !(data.didYouMean && (data.medicines || []).length);
            if (!sxFix.hidden) sxFix.innerHTML = `Showing results for <b>${esc(data.didYouMean)}</b>`;
            sxEmpty.hidden = rows.length > 0;
            if (!rows.length) sxEmpty.innerHTML = `<p>No medicines match “${esc(q)}”.</p><p class="small">Try fewer letters, or the salt name, like “paracetamol”.</p>`;
        });
    }

    let openedWithHistory = false;
    function openSx() {
        if (!sx.hidden) return;
        sx.hidden = false;
        document.documentElement.classList.add('mz-sx-open');
        sxInput.value = headerInput.value;
        updateSx();
        sxInput.focus();
        // The phone's back button closes the search, not the page
        try { history.pushState({ mzSearch: true }, ''); openedWithHistory = true; } catch (e) { openedWithHistory = false; }
    }
    function closeSx(fromHistory) {
        if (sx.hidden) return;
        if (stopListening) stopListening();
        cancelSuggest();
        sx.hidden = true;
        document.documentElement.classList.remove('mz-sx-open');
        headerInput.value = sxInput.value.trim();
        if (!fromHistory && openedWithHistory && history.state && history.state.mzSearch) history.back();
        openedWithHistory = false;
    }
    window.addEventListener('popstate', () => { if (!sx.hidden) closeSx(true); });

    function go(url) {
        // Leave the search's history entry behind, so back from the results returns to this page
        sx.hidden = true;
        document.documentElement.classList.remove('mz-sx-open');
        if (openedWithHistory && history.state && history.state.mzSearch) location.replace(url);
        else location.href = url;
    }

    sx.addEventListener('click', (e) => {
        if (e.target.closest('[data-sx-close]')) { e.preventDefault(); closeSx(false); return; }
        if (e.target.closest('[data-sx-clear-recent]')) { saveRecent([]); updateSx(); return; }
        const link = e.target.closest('a[href]');
        if (link && sx.contains(link)) { e.preventDefault(); go(link.href); }
    });
    sx.querySelector('form').addEventListener('submit', (e) => {
        e.preventDefault();
        const item = sxKeys.activeItem();
        if (item) { item.querySelector('a').click(); return; }
        const q = sxInput.value.trim();
        if (!q) return;
        remember(q);
        go(searchUrl(q));
    });
    sxInput.addEventListener('input', updateSx);
    sxInput.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowDown') { e.preventDefault(); sxKeys.move(1); }
        else if (e.key === 'ArrowUp') { e.preventDefault(); sxKeys.move(-1); }
        else if (e.key === 'Escape') { e.preventDefault(); closeSx(false); }
    });
    sxClear.addEventListener('click', () => { sxInput.value = ''; updateSx(); sxInput.focus(); });

    let stopListening = null;
    sxMic.addEventListener('click', () => {
        if (stopListening) { stopListening(); return; }
        if (!voice) return;
        sx.classList.add('is-listening');
        sxInput.placeholder = 'Listening… say a medicine';
        stopListening = voice.listen({
            prompt: 'Say a medicine, salt or brand',
            onInterim: (t) => { sxInput.value = t; },
            onResult: (t) => { sxInput.value = t; remember(t); go(searchUrl(t)); },
            onError: (msg) => { sxEmpty.hidden = false; sxEmpty.innerHTML = `<p>${esc(msg)}</p>`; },
            onEnd: () => { stopListening = null; sx.classList.remove('is-listening'); sxInput.placeholder = 'Search medicines, salts or brands'; },
        });
    });

    // ================================================================ the header's box picks the mode
    headerInput.addEventListener('focus', () => {
        if (isWide()) { refreshDrop(); return; }
        headerInput.blur();
        openSx();
    });
    headerInput.addEventListener('mousedown', (e) => { if (!isWide()) { e.preventDefault(); openSx(); } });
    headerInput.addEventListener('input', () => { if (isWide()) refreshDrop(); });
    headerInput.addEventListener('blur', () => { if (isWide()) setTimeout(() => { if (document.activeElement !== headerInput) hideDrop(); }, 0); });
    headerInput.addEventListener('keydown', (e) => {
        if (!isWide()) return;
        if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            if (drop.hidden) { refreshDrop(); return; }
            e.preventDefault();
            dropKeys.move(e.key === 'ArrowDown' ? 1 : -1);
        } else if (e.key === 'Escape') {
            hideDrop();
        }
    });
    headerForm.addEventListener('submit', (e) => {
        const item = isWide() ? dropKeys.activeItem() : null;
        if (item && item.classList.contains('is-category')) { e.preventDefault(); location.href = item.querySelector('a').href; return; }
        if (!headerInput.value.trim()) { e.preventDefault(); return; }
        remember(headerInput.value);
        hideDrop();
    });
    // Rotating a tablet or resizing across the breakpoint: close whichever no longer fits
    wideMq.addEventListener && wideMq.addEventListener('change', () => { hideDrop(); if (isWide()) closeSx(false); });
})();
