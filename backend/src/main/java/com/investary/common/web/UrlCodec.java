package com.investary.common.web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class UrlCodec {

    private UrlCodec() {
    }

    /**
     * Percent-decodes a URL component as UTF-8. Unlike {@link java.net.URLDecoder}, '+' is kept as is:
     * the plus-as-space rule only applies to form data, not to paths.
     */
    public static String percentDecode(String value) {
        if (value == null) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length() && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                out.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(bytes, 0, bytes.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }
}
