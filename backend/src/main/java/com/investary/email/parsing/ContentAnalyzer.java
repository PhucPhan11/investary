package com.investary.email.parsing;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Turns an email body into readable blocks in reading order, a normalized plain-text version and
 * a deduplicated list of http(s) links. Headings are recognised generically (h1–h6, or short
 * paragraphs that are entirely bold), so no brokerage-specific template knowledge is needed.
 */
@Component
public class ContentAnalyzer {

    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "li", "h1", "h2", "h3", "h4", "h5", "h6", "td", "th", "div", "blockquote", "pre",
            "dd", "dt", "address", "caption", "center", "section", "article", "header", "footer");
    private static final String BLOCK_SELECTOR = String.join(",", BLOCK_TAGS);
    private static final Set<String> BOLD_TAGS = Set.of("strong", "b", "h1", "h2", "h3", "h4", "h5", "h6");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^(\\d{1,2}|[IVX]{1,4})[.)]\\s+\\S.*");
    private static final Pattern PLAIN_LINK = Pattern.compile("(?i)\\bhttps?://[^\\s<>\"'\\[\\]()]+");
    private static final Pattern PLAIN_LEAD = Pattern.compile("^([^:\\n]{1,40}:)\\s");
    private static final int MAX_HEADING_LENGTH = 120;
    private static final int MAX_LEAD_LENGTH = 60;
    private static final int MAX_LINK_TEXT_LENGTH = 300;

    private final TrackingLinkUnwrapper unwrapper;

    public ContentAnalyzer(TrackingLinkUnwrapper unwrapper) {
        this.unwrapper = unwrapper;
    }

    public AnalyzedContent analyzeHtml(String html) {
        Document document = Jsoup.parse(html);
        document.select("script, style, head, noscript, template, title, meta, link, object, embed, iframe").remove();

        State state = new State();
        for (Element element : document.body().getAllElements()) {
            String tag = element.normalName();
            if (BLOCK_TAGS.contains(tag)) {
                if (element.select(BLOCK_SELECTOR).size() == 1) {
                    state.addBlock(blockText(element), headingLevel(element), lead(element));
                } else if (StringUtils.hasText(element.ownText())) {
                    state.addBlock(TextCleaner.clean(element.ownText()), 0, null);
                }
            }
            if (tag.equals("a") && element.hasAttr("href")) {
                String text = TextCleaner.clean(element.text());
                if (text.isEmpty()) {
                    text = TextCleaner.clean(element.select("img[alt]").eachAttr("alt").stream().findFirst().orElse(""));
                }
                state.addLink(element.attr("href"), text);
            }
        }
        return state.result();
    }

    public AnalyzedContent analyzePlainText(String text) {
        State state = new State();
        for (String paragraph : TextCleaner.clean(text).split("\\n\\s*\\n")) {
            String block = paragraph.strip();
            if (block.isEmpty()) {
                continue;
            }
            boolean singleLine = block.indexOf('\n') < 0;
            int level = 0;
            if (singleLine && block.length() <= MAX_HEADING_LENGTH) {
                if (NUMBERED_HEADING.matcher(block).matches()) {
                    level = 1;
                } else if (block.length() <= 80 && block.equals(block.toUpperCase(Locale.ROOT)) && block.chars().anyMatch(Character::isLetter)) {
                    level = 2;
                }
            }
            Matcher lead = PLAIN_LEAD.matcher(block);
            state.addBlock(block, level, level == 0 && lead.find() ? lead.group(1) : null);

            for (String line : block.split("\\n")) {
                Matcher url = PLAIN_LINK.matcher(line);
                while (url.find()) {
                    String found = trimTrailingPunctuation(url.group());
                    String label = TextCleaner.clean(line.replace(url.group(), " ")).replaceAll("[\\s:\\-–]+$", "");
                    state.addLink(found, label);
                }
            }
        }
        return state.result();
    }

    private static String blockText(Element element) {
        StringBuilder text = new StringBuilder();
        NodeTraversor.traverse((node, depth) -> {
            if (node instanceof TextNode textNode) {
                text.append(textNode.getWholeText().replaceAll("\\s+", " "));
            } else if (node instanceof Element child && child.normalName().equals("br")) {
                text.append('\n');
            }
        }, element);
        return TextCleaner.clean(text.toString());
    }

    private static int headingLevel(Element element) {
        String tag = element.normalName();
        String text = TextCleaner.clean(element.text());
        if (text.isEmpty() || text.length() > MAX_HEADING_LENGTH) {
            return 0;
        }
        boolean numbered = NUMBERED_HEADING.matcher(text).matches();
        if (tag.matches("h[1-6]")) {
            return tag.equals("h1") || tag.equals("h2") || numbered ? 1 : 2;
        }
        List<TextNode> visible = visibleTextNodes(element);
        boolean allBold = !visible.isEmpty() && visible.stream().allMatch(node -> hasAncestor(node, element, BOLD_TAGS));
        boolean onlyLinkText = visible.stream().allMatch(node -> hasAncestor(node, element, Set.of("a")));
        if (!allBold || onlyLinkText) {
            return 0;
        }
        return numbered ? 1 : 2;
    }

    /** Bold text at the start of a body block, such as {@code "VN-Index:"} or a ticker. */
    private static String lead(Element element) {
        StringBuilder lead = new StringBuilder();
        for (TextNode node : visibleTextNodes(element)) {
            if (!hasAncestor(node, element, BOLD_TAGS)) {
                break;
            }
            lead.append(node.getWholeText());
        }
        String result = TextCleaner.clean(lead.toString().replaceAll("\\s+", " "));
        return result.isEmpty() || result.length() > MAX_LEAD_LENGTH ? null : result;
    }

    private static List<TextNode> visibleTextNodes(Element element) {
        List<TextNode> nodes = new ArrayList<>();
        NodeTraversor.traverse((node, depth) -> {
            if (node instanceof TextNode textNode && !TextCleaner.clean(textNode.getWholeText()).isEmpty()) {
                nodes.add(textNode);
            }
        }, element);
        return nodes;
    }

    private static boolean hasAncestor(Node node, Element boundary, Set<String> tags) {
        for (Node current = node.parent(); current != null; current = current.parent()) {
            if (current instanceof Element element && tags.contains(element.normalName())) {
                return true;
            }
            if (current == boundary) {
                return false;
            }
        }
        return false;
    }

    private static String trimTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ".,;:!?".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    /** Accumulates blocks and links while walking the body in reading order. */
    private final class State {
        private final List<ContentBlock> blocks = new ArrayList<>();
        private final Map<String, MutableLink> links = new LinkedHashMap<>();
        private final Map<String, Integer> languageWeights = new LinkedHashMap<>();
        private final StringBuilder text = new StringBuilder();
        private String topHeading;
        private String subHeading;
        private String language;

        void addBlock(String blockText, int headingLevel, String lead) {
            if (blockText.isEmpty()) {
                return;
            }
            String detected = LanguageDetector.detect(blockText);
            if (detected != null) {
                language = detected;
                languageWeights.merge(detected, blockText.length(), Integer::sum);
            }
            if (headingLevel == 1) {
                topHeading = blockText;
                subHeading = null;
            } else if (headingLevel == 2) {
                subHeading = blockText;
            }
            blocks.add(new ContentBlock(blocks.size(), blockText, headingLevel, section(), headingLevel == 0 ? lead : null, language));
            if (headingLevel == 1 && !text.isEmpty()) {
                text.append('\n');
            }
            text.append(blockText).append('\n');
        }

        void addLink(String href, String label) {
            String original = href == null ? "" : href.strip();
            String lower = original.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return;
            }
            String target = unwrapper.unwrap(original);
            String host = host(target);
            if (host == null) {
                return;
            }
            String displayText = label == null || label.isBlank() ? null : truncate(label);
            MutableLink existing = links.get(target);
            if (existing != null) {
                if (existing.displayText == null) {
                    existing.displayText = displayText;
                }
                return;
            }
            links.put(target, new MutableLink(original, target, host, displayText, section(), language));
        }

        private String section() {
            if (topHeading == null) {
                return subHeading;
            }
            return subHeading == null ? topHeading : topHeading + " > " + subHeading;
        }

        AnalyzedContent result() {
            List<ExtractedLink> extracted = new ArrayList<>();
            for (MutableLink link : links.values()) {
                extracted.add(new ExtractedLink(extracted.size(), link.originalUrl, link.targetUrl, link.host,
                        link.displayText, link.section, link.language));
            }
            List<String> languages = languageWeights.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .toList();
            return new AnalyzedContent(text.toString().strip(), List.copyOf(blocks), List.copyOf(extracted), languages);
        }
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String truncate(String value) {
        return value.length() > MAX_LINK_TEXT_LENGTH ? value.substring(0, MAX_LINK_TEXT_LENGTH) : value;
    }

    private static final class MutableLink {
        final String originalUrl;
        final String targetUrl;
        final String host;
        String displayText;
        final String section;
        final String language;

        MutableLink(String originalUrl, String targetUrl, String host, String displayText, String section, String language) {
            this.originalUrl = originalUrl;
            this.targetUrl = targetUrl;
            this.host = host;
            this.displayText = displayText;
            this.section = section;
            this.language = language;
        }
    }
}
