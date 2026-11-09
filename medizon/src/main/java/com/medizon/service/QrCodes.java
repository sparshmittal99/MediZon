package com.medizon.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.Map;

/** QR codes drawn as inline SVG on the server, so what they hold (a 2FA secret) never goes to another site. */
public final class QrCodes {

    private QrCodes() {}

    /** An SVG of {@code text}: one path of dark squares on white, with the usual quiet zone around it. */
    public static String svg(String text) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 0));
            int n = m.getWidth(), quiet = 4, size = n + 2 * quiet;
            StringBuilder path = new StringBuilder();
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    if (m.get(x, y)) path.append('M').append(x + quiet).append(' ').append(y + quiet).append("h1v1h-1z");
                }
            }
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size + "\" shape-rendering=\"crispEdges\""
                    + " role=\"img\" aria-label=\"QR code for your authenticator app\">"
                    + "<rect width=\"" + size + "\" height=\"" + size + "\" fill=\"#fff\"/>"
                    + "<path fill=\"#000\" d=\"" + path + "\"/></svg>";
        } catch (WriterException e) {
            throw new IllegalStateException(e);
        }
    }
}
