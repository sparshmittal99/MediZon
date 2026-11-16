/*
 * Product photo zoom, like shopping apps:
 *  - with a mouse, hovering the photo magnifies it in place, following the cursor;
 *  - clicking / tapping it opens a full-screen viewer: pinch or double-tap to zoom (phones), click or the mouse wheel
 *    to zoom (computers), drag to move around, Esc or the phone's back button to close.
 */
(function () {
    const thumbs = document.querySelectorAll('[data-mz-zoom]');
    if (!thumbs.length) return;

    const MAX = 5, DOUBLE = 2.5;
    const canHover = window.matchMedia('(hover: hover) and (pointer: fine)');

    // ---- In-place magnifier (mouse only) ----
    thumbs.forEach((thumb) => {
        const img = thumb.querySelector('img');
        thumb.addEventListener('mousemove', (e) => {
            if (!canHover.matches) return;
            const r = thumb.getBoundingClientRect();
            img.style.transformOrigin = `${((e.clientX - r.left) / r.width) * 100}% ${((e.clientY - r.top) / r.height) * 100}%`;
            thumb.classList.add('is-magnified');
        });
        thumb.addEventListener('mouseleave', () => thumb.classList.remove('is-magnified'));
        thumb.addEventListener('click', () => { thumb.classList.remove('is-magnified'); open(img); });
    });

    // ---- Full-screen viewer ----
    let viewer, stage, big, lastFocus;
    let scale = 1, tx = 0, ty = 0;
    let openedWithHistory = false;

    function build() {
        viewer = document.createElement('div');
        viewer.className = 'mz-zoom';
        viewer.hidden = true;
        viewer.setAttribute('role', 'dialog');
        viewer.setAttribute('aria-modal', 'true');
        viewer.innerHTML =
            '<div class="mz-zoom-bar">' +
            '  <button type="button" class="mz-zoom-btn" data-z="close" aria-label="Close"><i class="bi bi-x-lg" aria-hidden="true"></i></button>' +
            '</div>' +
            '<div class="mz-zoom-stage"><img alt=""></div>';
        document.body.appendChild(viewer);
        stage = viewer.querySelector('.mz-zoom-stage');
        big = stage.querySelector('img');

        viewer.querySelector('[data-z="close"]').addEventListener('click', () => close(false));
        viewer.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') { e.preventDefault(); close(false); }
            else if (e.key === 'Tab') { e.preventDefault(); viewer.querySelector('[data-z="close"]').focus(); }
        });
        stage.addEventListener('wheel', (e) => {
            e.preventDefault();
            const p = local(e.clientX, e.clientY);
            zoomTo(scale * Math.exp(-e.deltaY * 0.0025), p.x, p.y, false);
        }, { passive: false });
        stage.addEventListener('pointerdown', down);
        stage.addEventListener('pointermove', move);
        stage.addEventListener('pointerup', up);
        stage.addEventListener('pointercancel', up);
        window.addEventListener('resize', () => { if (!viewer.hidden) reset(false); });
        window.addEventListener('popstate', () => { if (viewer && !viewer.hidden) close(true); });
    }

    function open(img) {
        if (!viewer) build();
        lastFocus = document.activeElement;
        big.src = img.currentSrc || img.src;
        big.alt = img.alt;
        viewer.setAttribute('aria-label', img.alt ? `${img.alt}, zoomable image` : 'Zoomable image');
        viewer.hidden = false;
        document.documentElement.classList.add('mz-zoom-open');
        reset(false);
        viewer.querySelector('[data-z="close"]').focus();
        // The phone's back button closes the viewer, not the page
        try { history.pushState({ mzZoom: true }, ''); openedWithHistory = true; } catch (e) { openedWithHistory = false; }
    }

    function close(fromHistory) {
        if (viewer.hidden) return;
        viewer.hidden = true;
        document.documentElement.classList.remove('mz-zoom-open');
        pointers.clear();
        if (!fromHistory && openedWithHistory && history.state && history.state.mzZoom) history.back();
        openedWithHistory = false;
        if (lastFocus && lastFocus.focus) lastFocus.focus();
    }

    // ---- Geometry: points are relative to the stage centre, where the unzoomed photo sits ----
    function local(cx, cy) {
        const r = stage.getBoundingClientRect();
        return { x: cx - r.left - r.width / 2, y: cy - r.top - r.height / 2 };
    }

    function clampPan() {
        const w = big.offsetWidth * scale, h = big.offsetHeight * scale;
        const maxX = Math.max(0, (w - stage.clientWidth) / 2), maxY = Math.max(0, (h - stage.clientHeight) / 2);
        tx = Math.min(maxX, Math.max(-maxX, tx));
        ty = Math.min(maxY, Math.max(-maxY, ty));
    }

    function render(animate) {
        big.classList.toggle('is-animating', !!animate);
        big.style.transform = `translate(${tx}px, ${ty}px) scale(${scale})`;
        stage.classList.toggle('is-zoomed', scale > 1.01);
    }

    // Zoom to `next`, keeping the photo point under (px, py) where it is
    function zoomTo(next, px, py, animate) {
        next = Math.min(MAX, Math.max(1, next));
        tx = px - (px - tx) * (next / scale);
        ty = py - (py - ty) * (next / scale);
        scale = next;
        clampPan();
        render(animate);
    }

    function reset(animate) { scale = 1; tx = 0; ty = 0; render(animate); }

    // ---- Touch and mouse: drag to pan, two fingers to pinch, double-tap (touch) or click (mouse) to zoom ----
    const pointers = new Map();
    let pinch = null, drag = null, lastTap = null;

    function down(e) {
        stage.setPointerCapture(e.pointerId);
        pointers.set(e.pointerId, { x: e.clientX, y: e.clientY, sx: e.clientX, sy: e.clientY });
        big.classList.remove('is-animating');
        if (pointers.size === 2) {
            const [a, b] = [...pointers.values()];
            const mid = local((a.x + b.x) / 2, (a.y + b.y) / 2);
            pinch = { dist: Math.hypot(a.x - b.x, a.y - b.y) || 1, scale, tx, ty, mid };
            drag = null;
        } else if (pointers.size === 1) {
            drag = { x: e.clientX, y: e.clientY, tx, ty, moved: false };
        }
    }

    function move(e) {
        const p = pointers.get(e.pointerId);
        if (!p) return;
        p.x = e.clientX; p.y = e.clientY;
        if (pinch && pointers.size >= 2) {
            const [a, b] = [...pointers.values()];
            const mid = local((a.x + b.x) / 2, (a.y + b.y) / 2);
            // Allow a little under-zoom while pinching; it springs back on release
            const next = Math.min(MAX, Math.max(0.8, pinch.scale * Math.hypot(a.x - b.x, a.y - b.y) / pinch.dist));
            scale = next;
            tx = mid.x - (pinch.mid.x - pinch.tx) * (next / pinch.scale);
            ty = mid.y - (pinch.mid.y - pinch.ty) * (next / pinch.scale);
            if (scale >= 1) clampPan();
            render(false);
        } else if (drag) {
            const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
            if (Math.abs(dx) > 6 || Math.abs(dy) > 6) drag.moved = true;
            if (scale > 1.01) {
                tx = drag.tx + dx; ty = drag.ty + dy;
                clampPan();
                render(false);
                stage.classList.add('is-dragging');
            }
        }
    }

    function up(e) {
        const p = pointers.get(e.pointerId);
        pointers.delete(e.pointerId);
        stage.classList.remove('is-dragging');
        if (pinch) {
            if (pointers.size < 2) {
                pinch = null;
                if (scale < 1) reset(true);
                // The finger left on the glass carries on panning
                const rest = [...pointers.values()][0];
                drag = rest ? { x: rest.x, y: rest.y, tx, ty, moved: true } : null;
                lastTap = null;
            }
            return;
        }
        if (!drag || !p || e.type === 'pointercancel') { drag = null; return; }
        const tapped = !drag.moved;
        drag = null;
        if (!tapped) { lastTap = null; return; }

        const at = local(e.clientX, e.clientY);
        if (e.pointerType === 'mouse') {
            // Click toggles between fitted and zoomed in on that spot
            if (scale > 1.01) reset(true); else zoomTo(DOUBLE, at.x, at.y, true);
            return;
        }
        const now = Date.now();
        if (lastTap && now - lastTap.t < 300 && Math.hypot(e.clientX - lastTap.x, e.clientY - lastTap.y) < 30) {
            if (scale > 1.01) reset(true); else zoomTo(DOUBLE, at.x, at.y, true);
            lastTap = null;
        } else {
            lastTap = { t: now, x: e.clientX, y: e.clientY };
        }
    }
})();
