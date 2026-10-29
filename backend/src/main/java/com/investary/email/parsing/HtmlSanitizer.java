package com.investary.email.parsing;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeTraversor;
import org.springframework.stereotype.Component;

/**
 * Produces a self-contained, inert HTML preview of untrusted email HTML. Scripts, event handlers,
 * styles, forms and embedded content are removed; images are replaced by their alt text so no
 * remote resource (such as a tracking pixel) is ever loaded; links are rewritten to their
 * unwrapped destination and open in a new tab without a referrer.
 */
@Component
public class HtmlSanitizer {

    public static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'";

    private static final Safelist SAFELIST = Safelist.relaxed()
            .removeTags("img")
            .removeProtocols("a", "href", "ftp")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow")
            .addEnforcedAttribute("a", "target", "_blank");

    private static final String TEMPLATE = """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="%s">
            <meta name="referrer" content="no-referrer">
            <style>
            body{font-family:'Segoe UI',system-ui,-apple-system,sans-serif;font-size:14px;line-height:1.55;color:#13233b;margin:16px;overflow-wrap:anywhere}
            table{border-collapse:collapse;max-width:100%%}td,th{vertical-align:top;padding:2px 4px}
            a{color:#187d55}p{margin:0 0 .6em}ul{padding-left:1.4em}
            </style></head><body>%s</body></html>
            """;

    private final TrackingLinkUnwrapper unwrapper;

    public HtmlSanitizer(TrackingLinkUnwrapper unwrapper) {
        this.unwrapper = unwrapper;
    }

    public String sanitize(String html) {
        Document dirty = Jsoup.parse(html == null ? "" : html);
        for (Element image : dirty.select("img")) {
            String alt = TextCleaner.clean(image.attr("alt"));
            if (alt.isEmpty()) {
                image.remove();
            } else {
                image.replaceWith(new TextNode("[" + alt + "]"));
            }
        }
        for (Element link : dirty.select("a[href]")) {
            link.attr("href", unwrapper.unwrap(link.attr("href").strip()));
        }
        // Preheader padding is hidden with CSS in the original; styles are dropped here, so drop the filler too.
        NodeTraversor.traverse((node, depth) -> {
            if (node instanceof TextNode text) {
                text.text(TextCleaner.removeInvisible(text.getWholeText()));
            }
        }, dirty.body());
        Document clean = new Cleaner(SAFELIST).clean(dirty);
        clean.outputSettings().prettyPrint(false);
        return TEMPLATE.formatted(CONTENT_SECURITY_POLICY, clean.body().html());
    }
}
