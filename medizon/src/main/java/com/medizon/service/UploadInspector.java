package com.medizon.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Checks what an uploaded file really is, from its bytes, never from the name or the Content-Type the browser sent
 * (both are the uploader's to choose: {@code script.js} can be sent as "photo.png", type {@code image/png}). Only
 * JPEG, PNG, WebP, GIF and PDF are recognised; anything else (scripts, HTML, SVG, executables, archives) is refused.
 *
 * <p>PDFs are opened and refused if they carry active content: JavaScript, launch or form-submit actions, embedded
 * files, XFA forms or rich media. A prescription is a picture of paper; it never needs any of those.
 */
public final class UploadInspector {

    /** Most objects walked in one PDF, so a crafted file can't keep the server busy. */
    private static final int MAX_PDF_OBJECTS = 200_000;

    /** Action types a PDF may not carry. */
    private static final Set<String> BANNED_ACTIONS = Set.of(
            "JavaScript", "Launch", "SubmitForm", "ImportData", "RichMediaExecute", "Rendition", "GoToE", "GoToR");

    /** Keys whose mere presence means active or hidden content. */
    private static final Set<String> BANNED_KEYS = Set.of(
            "JS", "JavaScript", "EmbeddedFile", "EmbeddedFiles", "XFA", "RichMedia", "RichMediaContent", "Launch");

    private UploadInspector() {}

    /**
     * The real type of {@code bytes}' file, from its signature: "image/jpeg", "image/png", "image/webp", "image/gif",
     * "application/pdf", or null when it's none of them.
     */
    public static String detect(byte[] head) {
        if (head == null) return null;
        if (startsWith(head, 0, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (startsWith(head, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
        if (startsWith(head, 0, 'G', 'I', 'F', '8', '7', 'a') || startsWith(head, 0, 'G', 'I', 'F', '8', '9', 'a')) return "image/gif";
        if (startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P')) return "image/webp";
        if (startsWith(head, 0, '%', 'P', 'D', 'F', '-')) return "application/pdf";
        return null;
    }

    /**
     * Refuses a PDF that isn't a plain document.
     *
     * @throws BusinessException when it can't be opened or carries active content
     */
    public static void requireSafePdf(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String found = activeContent(doc.getDocument().getTrailer());
            if (found != null) {
                throw new BusinessException("This PDF contains " + found + ", which isn't allowed. Upload a plain PDF or a photo of the prescription.");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException("This PDF couldn't be opened (it may be damaged or password-protected). Upload a plain PDF or a photo.");
        }
    }

    /** What active content the PDF holds, walking every object reachable from its trailer; null when none. */
    static String activeContent(COSDictionary trailer) {
        Deque<COSBase> todo = new ArrayDeque<>();
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        todo.push(trailer);
        int walked = 0;
        while (!todo.isEmpty()) {
            COSBase item = todo.pop();
            if (item instanceof COSObject ref) item = ref.getObject();
            if (item == null || !seen.add(item)) continue;
            if (++walked > MAX_PDF_OBJECTS) return "too many objects";
            if (item instanceof COSDictionary dict) {
                for (COSName key : dict.keySet()) {
                    if (BANNED_KEYS.contains(key.getName())) return describe(key.getName());
                }
                COSBase action = dict.getDictionaryObject(COSName.S);
                if (action instanceof COSName name && BANNED_ACTIONS.contains(name.getName())) return describe(name.getName());
                // A stream's dictionary is walked, its (image or text) data isn't: it can't hold PDF actions
                for (COSBase value : dict.getValues()) todo.push(value);
            } else if (item instanceof COSArray array) {
                for (COSBase value : array) todo.push(value);
            }
        }
        return null;
    }

    private static String describe(String what) {
        return switch (what) {
            case "JS", "JavaScript" -> "JavaScript";
            case "EmbeddedFile", "EmbeddedFiles" -> "attached files";
            case "XFA" -> "an XFA form";
            default -> "active content (" + what + ")";
        };
    }

    private static boolean startsWith(byte[] b, int offset, int... sig) {
        if (b.length < offset + sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((b[offset + i] & 0xFF) != sig[i]) return false;
        return true;
    }
}
