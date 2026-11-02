// Forgot-password flow: the "Verify code" and "Set new password" pages.
// Kept out of the templates because the Content-Security-Policy blocks inline scripts and onclick handlers.
(function () {
    'use strict';

    const ALERT_MS = 1800;   // pop-ups close themselves, like a toast

    // ---- Shared: the red-cross pop-up ----

    function showAlert(overlay, onClosed) {
        // A fresh copy of the card replays its pop-in and tick/cross drawing each time it's shown
        const card = overlay.firstElementChild;
        if (card) overlay.replaceChild(card.cloneNode(true), card);
        overlay.hidden = false;
        overlay.style.opacity = '1';
        let closed = false;
        const close = () => {
            if (closed) return;
            closed = true;
            clearTimeout(timer);
            document.removeEventListener('keydown', onKey);
            overlay.style.transition = 'opacity .25s ease';
            overlay.style.opacity = '0';
            setTimeout(() => {
                overlay.hidden = true;
                overlay.style.transition = '';
                if (onClosed) onClosed();
            }, 250);
        };
        const onKey = e => { if (e.key === 'Escape') close(); };
        const timer = setTimeout(close, ALERT_MS);
        overlay.onclick = close;
        document.addEventListener('keydown', onKey);
    }

    function focusInvalid(input) {
        if (!input) return;
        input.classList.add('is-invalid');
        input.focus();
        input.select();
    }

    // ---- Verify code page ----

    function setupVerifyPage() {
        const form = document.getElementById('verifyCodeForm');
        if (!form) return;

        const email = document.getElementById('emailInput').value || '';
        const codeInput = document.getElementById('code');
        const resendBtn = document.getElementById('resendBtn');
        const codeValue = document.getElementById('codeValue');
        const sentModal = document.getElementById('mzCodeSentModal');
        const alertModal = document.getElementById('mzVerifyErrorModal');
        const alertMsg = document.getElementById('mzVerifyErrorMsg');
        const cooldown = parseInt(resendBtn.dataset.cooldown || '30', 10);
        const storageKey = 'mz_resend_until_' + email.toLowerCase();
        let tick = null;

        function readUntil() {
            try { return parseInt(sessionStorage.getItem(storageKey) || '0', 10); } catch (e) { return 0; }
        }
        function writeUntil(t) {
            try { sessionStorage.setItem(storageKey, String(t)); } catch (e) { /* private mode: timer still runs */ }
        }

        let until = readUntil();

        function render() {
            const left = Math.ceil((until - Date.now()) / 1000);
            if (left > 0) {
                resendBtn.disabled = true;
                resendBtn.classList.add('mz-resend-wait');
                resendBtn.textContent = 'Resend code in 0:' + String(left).padStart(2, '0');
            } else {
                clearInterval(tick);
                tick = null;
                resendBtn.disabled = false;
                resendBtn.classList.remove('mz-resend-wait');
                resendBtn.textContent = 'Resend code';
            }
        }

        function startCooldown(seconds) {
            until = Date.now() + seconds * 1000;
            writeUntil(until);
            render();
            clearInterval(tick);
            tick = setInterval(render, 1000);
        }

        function showSent() {
            if (sentModal) showAlert(sentModal, () => codeInput.focus());
        }

        // ---- Masked code field: each digit shows briefly, then becomes a dot (like a phone's password field) ----
        const DOT = '•';
        const REVEAL_MS = 800;
        let code = '';
        let revealTimer = null;

        function paint(revealLast) {
            const shown = code.length && revealLast
                ? DOT.repeat(code.length - 1) + code.slice(-1)
                : DOT.repeat(code.length);
            codeInput.value = shown;
            codeValue.value = code;
            // Typing is always at the end, so the dots line up with the digits behind them
            codeInput.setSelectionRange(shown.length, shown.length);
        }

        function setCode(next, revealLast) {
            code = next.replace(/\D/g, '').slice(0, 6);
            clearTimeout(revealTimer);
            paint(revealLast);
            if (revealLast) revealTimer = setTimeout(() => paint(false), REVEAL_MS);
        }

        codeInput.addEventListener('input', () => {
            const typed = codeInput.value;
            codeInput.classList.remove('is-invalid');
            // Everything before the first real digit is a dot standing for a digit we already have
            const dots = typed.split('').filter(c => c === DOT).length;
            const digits = typed.replace(/\D/g, '');
            const next = code.slice(0, Math.min(dots, code.length)) + digits;
            // A single digit typed is shown for a moment; a pasted or autofilled code goes straight to dots
            setCode(next, next.length === code.length + 1);
        });
        // Keep the caret at the end so editing only ever adds or removes the last digit
        ['focus', 'click', 'keyup'].forEach(ev => codeInput.addEventListener(ev, () => {
            const end = codeInput.value.length;
            if (codeInput.selectionStart !== end) codeInput.setSelectionRange(end, end);
        }));

        function showError(msg) {
            if (msg && alertMsg) alertMsg.textContent = msg;
            showAlert(alertModal, () => focusInvalid(codeInput));
        }

        resendBtn.addEventListener('click', () => {
            if (resendBtn.disabled || !email) return;
            resendBtn.disabled = true;
            resendBtn.textContent = 'Sending…';
            fetch(form.dataset.resendUrl, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
                body: JSON.stringify({ email: email })
            }).then(res => {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                startCooldown(cooldown);
                setCode('', false);
                codeInput.classList.remove('is-invalid');
                showSent();
            }).catch(() => {
                // Couldn't reach the API: fall back to a normal form post, which reloads with the timer running
                document.getElementById('resendFallbackForm').submit();
            });
        });

        form.addEventListener('submit', e => {
            codeValue.value = code;
            if (!/^\d{6}$/.test(code)) {
                e.preventDefault();
                showError('Please enter the complete 6-digit verification code.');
            }
        });

        // The server says a code was just sent (first arrival, or the no-JS resend): start the countdown.
        // Otherwise carry on with one that was already running before a reload or a wrong code.
        if (form.dataset.codeSent === 'true') startCooldown(cooldown);
        else if (until > Date.now()) startCooldown(Math.ceil((until - Date.now()) / 1000));
        else render();

        if (sentModal && !sentModal.hidden) showSent();
        if (alertModal && !alertModal.hidden) showError();
    }

    // ---- Set new password page ----

    function setupResetPage() {
        const form = document.getElementById('resetPasswordForm');
        if (!form) return;

        const newPwd = document.getElementById('newPassword');
        const confirmPwd = document.getElementById('confirmPassword');
        const matchMsg = document.getElementById('pwdMatchMsg');
        const lengthMsg = document.getElementById('pwdLengthMsg');
        const alertModal = document.getElementById('mzResetErrorModal');
        const alertTitle = document.getElementById('mzResetErrorTitle');
        const alertMsg = document.getElementById('mzResetErrorMsg');

        function check() {
            const p1 = newPwd.value, p2 = confirmPwd.value;

            if (!p1) {
                lengthMsg.innerHTML = '<i class="bi bi-info-circle me-1"></i>Must be at least 8 characters.';
                newPwd.classList.remove('is-valid', 'is-invalid');
            } else if (p1.length >= 8) {
                lengthMsg.innerHTML = '<span class="text-success"><i class="bi bi-check-circle me-1"></i>8+ characters requirement met</span>';
                newPwd.classList.replace('is-invalid', 'is-valid') || newPwd.classList.add('is-valid');
            } else {
                lengthMsg.innerHTML = '<span class="text-danger"><i class="bi bi-x-circle me-1"></i>Must be at least 8 characters ('
                    + p1.length + '/8)</span>';
                newPwd.classList.replace('is-valid', 'is-invalid') || newPwd.classList.add('is-invalid');
            }

            if (!p2) {
                matchMsg.hidden = true;
                confirmPwd.classList.remove('is-valid', 'is-invalid');
            } else if (p1 === p2) {
                matchMsg.hidden = false;
                matchMsg.className = 'mz-pw-match ok';
                matchMsg.innerHTML = '<i class="bi bi-check-circle-fill"></i> Passwords match';
                confirmPwd.classList.replace('is-invalid', 'is-valid') || confirmPwd.classList.add('is-valid');
            } else {
                matchMsg.hidden = false;
                matchMsg.className = 'mz-pw-match bad';
                matchMsg.innerHTML = '<i class="bi bi-x-circle-fill"></i> Passwords do not match';
                confirmPwd.classList.replace('is-valid', 'is-invalid') || confirmPwd.classList.add('is-invalid');
            }
        }

        function showError(title, msg, input) {
            if (title) alertTitle.textContent = title;
            if (msg) alertMsg.textContent = msg;
            showAlert(alertModal, () => focusInvalid(input));
        }

        newPwd.addEventListener('input', check);
        confirmPwd.addEventListener('input', check);

        form.addEventListener('submit', e => {
            if (newPwd.value.length < 8) {
                e.preventDefault();
                showError('Password Too Short', 'Your new password must be at least 8 characters long.', newPwd);
            } else if (newPwd.value !== confirmPwd.value) {
                e.preventDefault();
                showError('Passwords Don\'t Match', 'The two passwords you entered are different.', confirmPwd);
            }
        });

        // An error the server sent back with the page
        if (alertModal && !alertModal.hidden) showError(null, null, confirmPwd);
    }

    // ---- Sign-in page, right after the password was changed ----

    function setupSignInPage() {
        const modal = document.getElementById('mzSuccessModal');
        if (!modal) return;
        showAlert(modal, () => {
            const email = document.getElementById('email');
            const password = document.getElementById('password');
            (email && email.value ? password : email)?.focus();
        });
    }

    function init() {
        setupVerifyPage();
        setupResetPage();
        setupSignInPage();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
