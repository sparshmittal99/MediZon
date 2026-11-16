/*
 * Catalogue > Import > Product pictures from a folder. The file names are matched to products (CatalogImageApi), the
 * staff member checks each match and can change it, then each chosen picture is uploaded on its own, three at a time.
 * Nothing is uploaded before "Upload". While the list is open the page doesn't reload for live changes.
 */
(function () {
    const root = document.querySelector('[data-mz-image-import]');
    if (!root) return;
    const out = root.querySelector('[data-mz-image-result]');
    const MAX_BYTES = 5 * 1024 * 1024;
    const MAX_FILES = 1000;
    const PICTURE = /\.(jpe?g|png|webp|gif)$/i;
    const PARALLEL = 3;
    const meta = (name) => document.querySelector(`meta[name="${name}"]`)?.content;
    const url = (path) => (window.mzUrl ? window.mzUrl(path) : path);

    let rows = [];
    let busy = false;
    let leftOut = 0;   // files chosen that aren't pictures

    function el(tag, attrs, ...children) {
        const e = document.createElement(tag);
        Object.entries(attrs || {}).forEach(([k, v]) => {
            if (v === null || v === undefined || v === false) return;
            if (k === 'class') e.className = v; else if (k === 'text') e.textContent = v; else e.setAttribute(k, v === true ? '' : v);
        });
        children.flat().forEach(c => { if (c != null) e.append(c); });
        return e;
    }

    function csrfHeaders(json) {
        const h = { Accept: 'application/json' };
        if (json) h['Content-Type'] = 'application/json';
        const header = meta('_csrf_header'), token = meta('_csrf');
        if (header && token) h[header] = token;
        return h;
    }

    async function errorOf(res) {
        try { return (await res.json()).error || 'Something went wrong. Try again.'; } catch (e) { return 'Something went wrong. Try again.'; }
    }

    async function matchNames(names) {
        const res = await fetch(url('/api/catalog-images/match'), {
            method: 'POST', credentials: 'same-origin', headers: csrfHeaders(true), body: JSON.stringify({ names }),
        });
        if (!res.ok) throw new Error(await errorOf(res));
        return res.json();
    }

    function hold(on) {
        if (on) root.setAttribute('data-mz-hold-live', ''); else root.removeAttribute('data-mz-hold-live');
    }

    function reset() {
        rows.forEach(r => r.preview && URL.revokeObjectURL(r.preview));
        rows = [];
        out.replaceChildren();
        out.hidden = true;
        hold(false);
    }

    function notice(text, kind) {
        out.hidden = false;
        out.replaceChildren(el('div', { class: `alert alert-${kind || 'warning'} py-2 small mb-0`, text }));
    }

    root.querySelectorAll('[data-mz-image-pick]').forEach(input => input.addEventListener('change', async () => {
        const files = [...input.files];
        input.value = '';
        if (busy || !files.length) return;
        reset();
        const pictures = files.filter(f => PICTURE.test(f.name) || /^image\/(jpeg|png|webp|gif)$/.test(f.type));
        const others = files.length - pictures.length;
        if (!pictures.length) { notice('There are no JPG, PNG, WebP or GIF pictures in what you chose.'); return; }
        if (pictures.length > MAX_FILES) { notice(`Choose up to ${MAX_FILES} pictures at a time; that has ${pictures.length}.`); return; }
        // A to Z without the extension, so "dolo 650.jpg" comes before its copy "dolo 650 (1).jpg" and gets the tick
        const bare = (f) => f.name.replace(/\.[^.]+$/, '');
        pictures.sort((a, b) => bare(a).localeCompare(bare(b), undefined, { numeric: true, sensitivity: 'base' }));
        hold(true);
        notice(`Matching ${pictures.length} pictures to products…`, 'info');
        let matches;
        try {
            matches = await matchNames(pictures.map(f => f.name));
        } catch (e) {
            notice(e.message, 'danger');
            hold(false);
            return;
        }
        // One at a time, so a second picture of the same product starts unticked
        pictures.forEach((file, k) => {
            const m = matches[k];
            const sure = m.status === 'MATCHED' || m.status === 'CHECK';
            const r = { file, status: m.status, candidates: m.candidates, choice: null, pick: false,
                state: null, message: null, preview: URL.createObjectURL(file) };
            if (sure) choose(r, m.candidates[0].id);
            rows.push(r);
        });
        leftOut = others;
        view = 'all';
        render();
    }));

    function product(r) {
        return r.choice == null ? null : r.candidates.find(c => c.id === r.choice) || null;
    }

    /**
     * Sets the row's product. A product without a picture is ticked to upload; one that already has a picture (added
     * by hand, say) is left unticked, so it keeps its picture unless the staff member ticks it.
     */
    function choose(r, id) {
        r.choice = id;
        const p = product(r);
        r.pick = !!p && !p.imageUrl && r.file.size <= MAX_BYTES && !rows.some(o => o !== r && o.pick && o.choice === id);
    }

    /** Why a row can't be ticked (null when it can): too big, or no product. */
    function cannot(r) {
        if (r.file.size > MAX_BYTES) return 'Over 5 MB: make it smaller and choose it again.';
        const p = product(r);
        if (!p) return r.status === 'NONE' && !r.candidates.length ? 'No product matches this name.' : 'Choose its product.';
        return null;
    }

    /** Ticks or unticks a row; ticking it unticks any other picture going on the same product. */
    function tick(r, on) {
        if (on && cannot(r)) return;
        r.pick = on;
        if (on) rows.forEach(o => { if (o !== r && o.choice === r.choice && o.state !== 'done') o.pick = false; });
    }

    const willUpload = (r) => r.pick && r.state !== 'done' && !cannot(r);
    const hasPicture = (r) => !!product(r)?.imageUrl && r.state !== 'done';

    /** Which rows the table shows: every one, only those whose product has no picture yet, or only those that have one. */
    const VIEWS = {
        all: ['All', () => true],
        missing: ['Without a picture', (r) => !hasPicture(r)],
        has: ['Already have a picture', hasPicture],
    };
    let view = 'all';

    const LABEL = { MATCHED: ['Matched', 'text-bg-success'], CHECK: ['Check', 'text-bg-warning'],
        CHOOSE: ['Choose', 'text-bg-warning'], NONE: ['No match', 'text-bg-secondary'] };

    function render() {
        const ready = rows.filter(willUpload);
        const replacing = ready.filter(r => product(r).imageUrl).length;
        const done = rows.filter(r => r.state === 'done').length;
        const failed = rows.filter(r => r.state === 'failed').length;
        const counts = Object.fromEntries(Object.keys(LABEL).map(s => [s, rows.filter(r => r.status === s).length]));
        const already = rows.filter(hasPicture).length;

        const summary = el('p', { class: 'small mb-2' },
            `${rows.length} pictures: ${counts.MATCHED} matched, ${counts.CHECK + counts.CHOOSE} to check, ${counts.NONE} without a match.`
            + (already ? ` ${already} ${already === 1 ? 'is for a product that already has' : 'are for products that already have'} a picture`
                + `: tick ${already === 1 ? 'it' : 'the ones'} to replace.` : '')
            + (leftOut ? ` ${leftOut} other ${leftOut === 1 ? 'file was' : 'files were'} left out.` : '')
            + (done ? ` ${done} uploaded.` : '') + (failed ? ` ${failed} failed.` : ''));

        // Filters, and tick / untick everything shown
        const filters = el('div', { class: 'btn-group btn-group-sm', role: 'group', 'aria-label': 'Show pictures' });
        Object.entries(VIEWS).forEach(([key, [name, test]]) => {
            const b = el('button', { type: 'button', class: `btn ${view === key ? 'btn-primary' : 'btn-outline-primary'}`,
                'aria-pressed': view === key ? 'true' : 'false', text: `${name} (${rows.filter(test).length})` });
            b.addEventListener('click', () => { view = key; render(); });
            filters.append(b);
        });
        const shown = rows.filter(VIEWS[view][1]);
        const all = el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary', disabled: busy, text: 'Tick all shown' });
        all.addEventListener('click', () => { shown.forEach(r => { if (r.state !== 'done') tick(r, true); }); render(); });
        const none = el('button', { type: 'button', class: 'btn btn-sm btn-outline-secondary', disabled: busy, text: 'Untick all shown' });
        none.addEventListener('click', () => { shown.forEach(r => { if (r.state !== 'done') tick(r, false); }); render(); });
        const tools = el('div', { class: 'd-flex flex-wrap gap-2 align-items-center mb-2' }, filters, all, none);

        const upload = el('button', { type: 'button', class: 'btn btn-primary', disabled: busy || !ready.length },
            el('i', { class: 'bi bi-upload me-1', 'aria-hidden': 'true' }),
            busy ? 'Uploading…' : `Upload ${ready.length} ${ready.length === 1 ? 'picture' : 'pictures'}`
                + (replacing ? ` (${replacing} replacing a picture)` : ''));
        upload.addEventListener('click', () => uploadAll());
        const finish = el('button', { type: 'button', class: 'btn btn-outline-secondary', disabled: busy, text: done ? 'Done' : 'Cancel' });
        finish.addEventListener('click', () => { if (done) { hold(false); location.reload(); } else reset(); });

        const body = el('tbody');
        shown.forEach(r => body.append(row(r)));
        if (!shown.length) body.append(el('tr', null, el('td', { colspan: 6, class: 'small text-muted', text: 'No pictures here.' })));
        const table = el('div', { class: 'table-responsive mz-panel-flush mb-2' },
            el('table', { class: 'table table-sm align-middle mb-0' },
                el('thead', null, el('tr', null, el('th', { text: 'Upload' }), el('th', { text: 'Picture' }), el('th', { text: 'File' }),
                    el('th', { text: 'Product' }), el('th', { text: 'Picture now' }), el('th', { text: '' }))),
                body));
        out.hidden = false;
        out.replaceChildren(summary, tools, table, el('div', { class: 'd-flex flex-wrap gap-2' }, upload, finish));
    }

    function thumb(src, alt) {
        return el('img', { src, alt, width: 48, height: 48, class: 'rounded border bg-white', style: 'object-fit: contain' });
    }

    function row(r) {
        const [label, badge] = LABEL[r.status];
        const p = product(r);
        const locked = busy || r.state === 'done' || r.state === 'sending';
        const why = cannot(r);

        const box = el('input', { type: 'checkbox', class: 'form-check-input', checked: r.state === 'done' || (r.pick && !why),
            disabled: locked || !!why, 'aria-label': `Upload ${r.file.name}` });
        box.addEventListener('change', () => { tick(r, box.checked); render(); });

        const select = el('select', { class: 'form-select form-select-sm', style: 'min-width: 14rem', disabled: locked,
            'aria-label': `Product for ${r.file.name}` });
        select.append(el('option', { value: '', text: r.candidates.length ? 'Skip this picture' : 'No product matches' }));
        r.candidates.forEach(c => select.append(el('option', { value: c.id, selected: c.id === r.choice,
            text: `${c.name}${c.code ? ' · ' + c.code : ''}${c.imageUrl ? ' (has a picture)' : ''}` })));
        select.append(el('option', { value: 'search', text: 'Search for another product…' }));
        select.addEventListener('change', () => {
            if (select.value === 'search') { search(r, cell); select.value = r.choice ?? ''; return; }
            choose(r, select.value ? Number(select.value) : null);
            render();
        });
        const cell = el('td', null, select,
            p?.manufacturer ? el('div', { class: 'small text-muted', text: p.manufacturer }) : null);

        const now = el('td');
        if (p?.imageUrl) {
            now.append(thumb(p.imageUrl, 'Picture now'));
            if (r.state !== 'done') now.append(el('span', { class: 'badge text-bg-info d-block mt-1', text: 'Has a picture' }));
        } else if (p) {
            now.append(el('span', { class: 'small text-muted', text: 'No picture' }));
        }

        let state;
        if (r.state === 'done') state = el('span', { class: 'small text-success fw-semibold' }, el('i', { class: 'bi bi-check-circle me-1' }), 'Uploaded');
        else if (r.state === 'sending') state = el('span', { class: 'small text-muted', text: 'Uploading…' });
        else if (r.state === 'failed') state = el('span', { class: 'small text-danger', text: r.message });
        else if (why) state = el('span', { class: 'small text-muted', text: why });
        else if (!r.pick) state = el('span', { class: 'small text-muted', text: p.imageUrl ? 'Keeps its picture' : 'Not ticked' });
        else state = el('span', { class: 'small text-primary', text: p.imageUrl ? 'Will replace its picture' : 'Will upload' });

        return el('tr', { class: !r.pick && r.state !== 'done' ? 'text-muted' : null },
            el('td', null, box),
            el('td', null, thumb(r.preview, r.file.name)),
            el('td', null, el('div', { class: 'small text-break', text: r.file.webkitRelativePath || r.file.name }),
                el('span', { class: `badge ${badge}`, text: label })),
            cell, now, el('td', null, state));
    }

    /** "Search for another product…": the typed words are matched like a file name; choosing one sets the row's product. */
    function search(r, cell) {
        if (cell.querySelector('[data-mz-image-search]')) return;
        const input = el('input', { type: 'search', class: 'form-control form-control-sm mt-1', placeholder: 'Product name or code',
            'data-mz-image-search': true, 'aria-label': 'Search for a product' });
        const list = el('div', { class: 'list-group list-group-flush small mt-1' });
        let timer = null;
        input.addEventListener('input', () => {
            clearTimeout(timer);
            const q = input.value.trim();
            if (q.length < 2) { list.replaceChildren(); return; }
            timer = setTimeout(async () => {
                try {
                    const [m] = await matchNames([q]);
                    list.replaceChildren(...(m.candidates.length ? m.candidates.map(c => {
                        const b = el('button', { type: 'button', class: 'list-group-item list-group-item-action py-1',
                            text: `${c.name}${c.code ? ' · ' + c.code : ''}` });
                        b.addEventListener('click', () => {
                            if (!r.candidates.some(x => x.id === c.id)) r.candidates = [c, ...r.candidates];
                            choose(r, c.id);
                            render();
                        });
                        return b;
                    }) : [el('div', { class: 'text-muted py-1', text: 'No product matches.' })]));
                } catch (e) {
                    list.replaceChildren(el('div', { class: 'text-danger py-1', text: e.message }));
                }
            }, 300);
        });
        cell.append(input, list);
        input.focus();
    }

    async function send(r) {
        const p = product(r);
        r.state = 'sending';
        const form = new FormData();
        form.append('file', r.file);
        form.append('replace', p.imageUrl ? 'true' : 'false');   // ticked on purpose for a product that has a picture
        try {
            const res = await fetch(url(`/api/catalog-images/${p.id}`), { method: 'POST', credentials: 'same-origin',
                headers: csrfHeaders(false), body: form });
            if (!res.ok) throw new Error(await errorOf(res));
            const saved = await res.json();
            // Every row offering this product now shows it has a picture
            rows.forEach(o => o.candidates.forEach(c => { if (c.id === p.id) c.imageUrl = saved.imageUrl; }));
            r.state = 'done';
        } catch (e) {
            r.state = 'failed';
            r.message = e.message || 'The upload failed. Try again.';
        }
    }

    async function uploadAll() {
        const queue = rows.filter(willUpload);
        if (!queue.length) return;
        busy = true;
        queue.forEach(r => { r.state = 'sending'; });
        render();
        const worker = async () => {
            for (let r = queue.shift(); r; r = queue.shift()) {
                await send(r);
                render();
            }
        };
        await Promise.all(Array.from({ length: PARALLEL }, worker));
        busy = false;
        render();
    }
})();
