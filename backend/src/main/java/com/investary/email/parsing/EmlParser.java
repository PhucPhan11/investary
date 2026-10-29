package com.investary.email.parsing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.investary.common.util.Hashing;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;

/**
 * Decodes EML files with Angus Mail. Header and body decoding is lenient: problems in individual
 * parts are collected instead of failing the whole import, so the original can still be kept and
 * reprocessed later. Only input that is not recognisable as an email is rejected.
 */
@Component
public class EmlParser {

    private static final int MAX_DEPTH = 10;

    private final Session session;

    public EmlParser() {
        Properties props = new Properties();
        props.setProperty("mail.mime.address.strict", "false");
        props.setProperty("mail.mime.decodetext.strict", "false");
        props.setProperty("mail.mime.decodefilename", "true");
        props.setProperty("mail.mime.parameters.strict", "false");
        props.setProperty("mail.mime.allowutf8", "true");
        this.session = Session.getInstance(props);
    }

    public ParsedEmail parse(InputStream raw) throws MalformedEmailException {
        MimeMessage message;
        try {
            message = new MimeMessage(session, raw);
            if (!looksLikeEmail(message)) {
                throw new MalformedEmailException("The file does not contain email headers");
            }
        } catch (MessagingException e) {
            throw new MalformedEmailException("The file could not be parsed as an email", e);
        }

        List<String> problems = new ArrayList<>();
        Bodies bodies = new Bodies();
        try {
            walk(message, bodies, problems, 0);
        } catch (MessagingException | IOException | RuntimeException e) {
            problems.add("Message structure could not be read: " + e.getMessage());
        }

        Sender sender = sender(message, problems);
        return new ParsedEmail(
                header(message, "Message-ID"),
                sender.address(),
                sender.name(),
                recipients(message, problems),
                subject(message, problems),
                sentAt(message, problems),
                bodies.html,
                bodies.text,
                List.copyOf(bodies.attachments),
                List.copyOf(problems));
    }

    private static boolean looksLikeEmail(MimeMessage message) throws MessagingException {
        boolean hasFrom = StringUtils.hasText(message.getHeader("From", ","));
        boolean hasOther = StringUtils.hasText(message.getHeader("Date", ","))
                || StringUtils.hasText(message.getHeader("Subject", ","))
                || StringUtils.hasText(message.getHeader("Message-ID", ","));
        return hasFrom && hasOther;
    }

    private void walk(Part part, Bodies bodies, List<String> problems, int depth) throws MessagingException, IOException {
        if (depth > MAX_DEPTH) {
            problems.add("MIME nesting deeper than " + MAX_DEPTH + " levels was ignored");
            return;
        }
        String contentType = baseType(part);
        if (contentType.startsWith("multipart/")) {
            Object content = part.getContent();
            if (content instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    BodyPart child = multipart.getBodyPart(i);
                    try {
                        walk(child, bodies, problems, depth + 1);
                    } catch (MessagingException | IOException | RuntimeException e) {
                        problems.add("Part " + (i + 1) + " of " + contentType + " could not be read: " + e.getMessage());
                    }
                }
            }
            return;
        }

        boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || StringUtils.hasText(filename(part));
        if (!isAttachment && contentType.equals("text/html") && bodies.html == null) {
            bodies.html = text(part, contentType, problems);
        } else if (!isAttachment && contentType.equals("text/plain") && bodies.text == null) {
            bodies.text = text(part, contentType, problems);
        } else {
            bodies.attachments.add(attachment(part, contentType, bodies.attachments.size()));
        }
    }

    private static String text(Part part, String contentType, List<String> problems) throws MessagingException, IOException {
        byte[] bytes;
        try (InputStream in = part.getInputStream()) {
            bytes = in.readAllBytes();
        }
        String charset = charset(part);
        if (charset != null) {
            try {
                return new String(bytes, MimeUtility.javaCharset(charset));
            } catch (UnsupportedEncodingException e) {
                problems.add("Unsupported charset '" + charset + "' in " + contentType + " part; decoded as UTF-8");
            }
        }
        return decodeUtf8OrLatin1(bytes);
    }

    /** Parts without a usable charset are usually UTF-8 in practice; fall back to Latin-1 when they are not. */
    private static String decodeUtf8OrLatin1(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private static ParsedAttachment attachment(Part part, String contentType, int position) throws MessagingException, IOException {
        byte[] bytes;
        try (InputStream in = part.getInputStream()) {
            bytes = in.readAllBytes();
        }
        String contentId = part instanceof jakarta.mail.internet.MimePart mime ? mime.getContentID() : null;
        boolean inline = Part.INLINE.equalsIgnoreCase(part.getDisposition())
                || (part.getDisposition() == null && contentId != null);
        return new ParsedAttachment(position, filename(part), contentType, inline, contentId, bytes.length, Hashing.sha256(bytes), bytes);
    }

    private static String filename(Part part) {
        try {
            String name = part.getFileName();
            return StringUtils.hasText(name) ? MimeUtility.decodeText(name).strip() : null;
        } catch (MessagingException | UnsupportedEncodingException | RuntimeException e) {
            return null;
        }
    }

    private static String baseType(Part part) throws MessagingException {
        String raw = part.getContentType();
        if (raw == null) {
            return "text/plain";
        }
        try {
            return new ContentType(raw).getBaseType().toLowerCase(Locale.ROOT);
        } catch (MessagingException | RuntimeException e) {
            int semicolon = raw.indexOf(';');
            return (semicolon >= 0 ? raw.substring(0, semicolon) : raw).strip().toLowerCase(Locale.ROOT);
        }
    }

    private static String charset(Part part) throws MessagingException {
        String raw = part.getContentType();
        if (raw == null) {
            return null;
        }
        try {
            String charset = new ContentType(raw).getParameter("charset");
            return StringUtils.hasText(charset) ? charset.strip() : null;
        } catch (MessagingException | RuntimeException e) {
            return null;
        }
    }

    private static Sender sender(MimeMessage message, List<String> problems) {
        try {
            Address[] from = message.getFrom();
            if (from != null && from.length > 0 && from[0] instanceof InternetAddress address) {
                String email = address.getAddress();
                return new Sender(email == null ? null : email.toLowerCase(Locale.ROOT), blankToNull(address.getPersonal()));
            }
        } catch (MessagingException | RuntimeException e) {
            problems.add("From header could not be parsed");
        }
        return new Sender(null, blankToNull(decodedHeader(message, "From")));
    }

    private static List<String> recipients(MimeMessage message, List<String> problems) {
        List<String> result = new ArrayList<>();
        for (Message.RecipientType type : List.of(Message.RecipientType.TO, Message.RecipientType.CC)) {
            try {
                Address[] addresses = message.getRecipients(type);
                if (addresses != null) {
                    for (Address address : addresses) {
                        result.add(address instanceof InternetAddress internet ? internet.toUnicodeString() : address.toString());
                    }
                }
            } catch (MessagingException | RuntimeException e) {
                problems.add(type + " header could not be parsed");
                String raw = decodedHeader(message, type == Message.RecipientType.TO ? "To" : "Cc");
                if (raw != null) {
                    result.add(raw);
                }
            }
        }
        return List.copyOf(result);
    }

    private static String subject(MimeMessage message, List<String> problems) {
        try {
            return blankToNull(message.getSubject());
        } catch (MessagingException | RuntimeException e) {
            problems.add("Subject header could not be decoded");
            return header(message, "Subject");
        }
    }

    private static java.time.Instant sentAt(MimeMessage message, List<String> problems) {
        try {
            var date = message.getSentDate();
            return date == null ? null : date.toInstant();
        } catch (MessagingException | RuntimeException e) {
            problems.add("Date header could not be parsed");
            return null;
        }
    }

    private static String header(MimeMessage message, String name) {
        try {
            return blankToNull(message.getHeader(name, ","));
        } catch (MessagingException e) {
            return null;
        }
    }

    private static String decodedHeader(MimeMessage message, String name) {
        String raw = header(message, name);
        if (raw == null) {
            return null;
        }
        try {
            return MimeUtility.decodeText(MimeUtility.unfold(raw));
        } catch (UnsupportedEncodingException e) {
            return raw;
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private record Sender(String address, String name) {
    }

    private static final class Bodies {
        String html;
        String text;
        final List<ParsedAttachment> attachments = new ArrayList<>();
    }
}
