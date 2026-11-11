/* Voice in and out, for the chat and the search box (window.mzVoice).
   Listening: inside the Android app, the phone's own voice input (MediZonApp.voiceInput / voiceSearch; the words come
   back through window.mzVoiceResult, see app.js); in a browser, the Web Speech API (Chrome, Edge, Safari).
   Speaking: the app's text-to-speech (MediZonApp.speak; window.mzSpeechDone when it finishes), else the browser's
   speechSynthesis. Hindi (Devanagari) is read in Hindi, everything else in Indian English. */
(function () {
    const app = window.MediZonApp;
    const call = (fn, ...args) => { try { return app && typeof app[fn] === 'function' ? app[fn](...args) : undefined; } catch (e) { return undefined; } };
    const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    const synth = window.speechSynthesis;

    const inApp = !!app;
    const appListens = () => inApp && typeof app.voiceSearch === 'function' && call('canVoiceSearch') === true;
    const appSpeaks = () => inApp && typeof app.speak === 'function';

    let pending = null;       // { onResult, onEnd } waiting for the app's voice screen
    let recognition = null;   // the browser's, while listening
    let speechDone = null;

    const lang = (text) => /[ऀ-ॿ]/.test(text || '') ? 'hi-IN' : 'en-IN';

    /** Listen once. opts: onResult(text) when done, onInterim(text) while speaking (browser only), onEnd() always,
        onError(message), prompt (the app's voice screen). Returns a function that stops listening. */
    function listen(opts = {}) {
        stopSpeaking();
        if (appListens()) {
            pending = opts;
            if (typeof app.voiceInput === 'function') call('voiceInput', opts.prompt || 'Speak now');
            else call('voiceSearch');
            return () => { pending = null; };
        }
        if (!Recognition) {
            opts.onError && opts.onError(inApp ? 'Update the MediZon app to use voice.' : "Voice typing isn't supported in this browser. Try Chrome or Edge.");
            opts.onEnd && opts.onEnd();
            return () => {};
        }
        if (recognition) { try { recognition.abort(); } catch (e) { /* already stopped */ } }
        const r = new Recognition();
        recognition = r;
        r.lang = opts.lang || 'en-IN';
        r.interimResults = true;
        r.continuous = false;
        r.maxAlternatives = 1;
        let finalText = '', ended = false;
        r.onresult = (e) => {
            let interim = '';
            for (let i = e.resultIndex; i < e.results.length; i++) {
                const t = e.results[i][0].transcript;
                if (e.results[i].isFinal) finalText += t; else interim += t;
            }
            opts.onInterim && opts.onInterim((finalText + interim).trim());
        };
        r.onerror = (e) => {
            const msg = e.error === 'not-allowed' || e.error === 'service-not-allowed'
                ? 'Allow the microphone for this site to use voice typing.'
                : e.error === 'no-speech' ? "Didn't catch that. Tap the mic and try again."
                : e.error === 'network' ? 'Voice typing needs an internet connection.'
                : e.error === 'aborted' ? null : "Voice typing didn't work. Please try again.";
            if (msg && opts.onError) opts.onError(msg);
        };
        r.onend = () => {
            if (ended) return;
            ended = true;
            if (recognition === r) recognition = null;
            const text = finalText.trim();
            if (text && opts.onResult) opts.onResult(text);
            opts.onEnd && opts.onEnd();
        };
        try { r.start(); } catch (e) { r.onend(); }
        return () => { try { r.stop(); } catch (e) { /* stopped */ } };
    }

    /** The app's voice screen answered (empty when cancelled). True when a listen() was waiting for it. */
    function deliver(text) {
        if (!pending) return false;
        const p = pending;
        pending = null;
        const t = String(text || '').trim();
        if (t && p.onResult) p.onResult(t);
        p.onEnd && p.onEnd();
        return true;
    }

    // ------------------------------------------------------------------ speaking

    /** Reads plain text aloud; onEnd when it finishes or is stopped. */
    function speak(text, onEnd) {
        stopSpeaking();
        const clean = String(text || '')
            .replace(/\*\*|__|`/g, '')
            .replace(/https?:\/\/\S+/g, '')
            .replace(/(^|\s)\/[a-z][\w\/-]*/gi, '$1')      // page paths like /orders read badly
            .replace(/₹\s?/g, 'rupees ')
            .replace(/\s+/g, ' ').trim();
        if (!clean) { onEnd && onEnd(); return; }
        speechDone = onEnd || null;
        if (appSpeaks()) { call('speak', clean, lang(clean)); return; }
        if (!synth) { finish(); return; }
        // Long replies: a sentence at a time, as Chrome cuts off long utterances
        const parts = clean.match(/[^.!?।]+[.!?।]*\s*/g) || [clean];
        const voice = pickVoice(lang(clean));
        parts.forEach((p, i) => {
            const u = new SpeechSynthesisUtterance(p.trim());
            u.lang = lang(clean);
            if (voice) u.voice = voice;
            u.rate = 1;
            if (i === parts.length - 1) { u.onend = finish; u.onerror = finish; }
            synth.speak(u);
        });
    }

    function pickVoice(l) {
        const voices = synth ? synth.getVoices() : [];
        return voices.find(v => v.lang === l) || voices.find(v => v.lang && v.lang.startsWith(l.slice(0, 2)) && /India|IN/.test(v.lang + v.name))
            || (l === 'en-IN' ? voices.find(v => v.lang && v.lang.startsWith('en')) : null) || null;
    }
    if (synth && typeof synth.addEventListener === 'function') synth.addEventListener('voiceschanged', () => {});

    function finish() {
        const d = speechDone;
        speechDone = null;
        d && d();
    }

    function stopSpeaking() {
        if (appSpeaks()) call('stopSpeaking');
        if (synth && (synth.speaking || synth.pending)) synth.cancel();
        finish();
    }

    window.mzSpeechDone = finish;

    window.mzVoice = {
        canListen: () => appListens() || !!Recognition || inApp,   // in an old app build the mic still shows, and says to update
        canSpeak: () => appSpeaks() || !!synth,
        listen, deliver, speak, stopSpeaking,
        cancel: () => { pending = null; },
    };

    // Leaving the page stops the reading
    window.addEventListener('pagehide', () => stopSpeaking());
})();
