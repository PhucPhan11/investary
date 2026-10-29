package com.investary.email;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;

import jakarta.activation.DataHandler;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

/**
 * Builds synthetic EML files for tests. No real messages or personal data are used.
 */
public final class TestEmails {

    private static final Session SESSION = Session.getInstance(new Properties());

    private TestEmails() {
    }

    public static byte[] fixture(String name) {
        try (InputStream in = TestEmails.class.getResourceAsStream("/eml/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String from = "Research Desk <research@tcbs.com.vn>";
        private String to = "investor@example.com";
        private String subject = "Synthetic research update";
        private String messageId = "<synthetic-" + System.nanoTime() + "@example.com>";
        private String text;
        private String html;
        private Date sentAt = new Date(1793189680000L);
        private final MimeMultipart attachments = new MimeMultipart("mixed");
        private boolean hasAttachments;

        public Builder from(String value) {
            this.from = value;
            return this;
        }

        public Builder subject(String value) {
            this.subject = value;
            return this;
        }

        public Builder messageId(String value) {
            this.messageId = value;
            return this;
        }

        public Builder text(String value) {
            this.text = value;
            return this;
        }

        public Builder sentAt(java.time.Instant value) {
            this.sentAt = Date.from(value);
            return this;
        }

        public Builder html(String value) {
            this.html = value;
            return this;
        }

        public Builder attachment(String filename, String contentType, byte[] content) {
            try {
                MimeBodyPart part = new MimeBodyPart();
                part.setDataHandler(new DataHandler(new ByteArrayDataSource(content, contentType)));
                part.setFileName(filename);
                part.setDisposition(MimeBodyPart.ATTACHMENT);
                attachments.addBodyPart(part);
                hasAttachments = true;
                return this;
            } catch (MessagingException e) {
                throw new IllegalStateException(e);
            }
        }

        public byte[] build() {
            try {
                MimeMessage message = new MimeMessage(SESSION);
                message.setFrom(new InternetAddress(from));
                message.setRecipients(MimeMessage.RecipientType.TO, to);
                message.setSubject(subject, StandardCharsets.UTF_8.name());
                message.setSentDate(sentAt);

                if (hasAttachments) {
                    MimeMultipart mixed = new MimeMultipart("mixed");
                    mixed.addBodyPart(body());
                    for (int i = 0; i < attachments.getCount(); i++) {
                        mixed.addBodyPart(attachments.getBodyPart(i));
                    }
                    message.setContent(mixed);
                } else if (text != null && html != null) {
                    message.setContent(alternative());
                } else if (html != null) {
                    message.setText(html, StandardCharsets.UTF_8.name(), "html");
                } else {
                    message.setText(text == null ? "" : text, StandardCharsets.UTF_8.name());
                }
                message.saveChanges();
                message.setHeader("Message-ID", messageId);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                message.writeTo(out);
                return out.toByteArray();
            } catch (MessagingException | IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private MimeMultipart alternative() throws MessagingException {
            MimeMultipart alternative = new MimeMultipart("alternative");
            MimeBodyPart plain = new MimeBodyPart();
            plain.setText(text, StandardCharsets.UTF_8.name());
            MimeBodyPart rich = new MimeBodyPart();
            rich.setText(html, StandardCharsets.UTF_8.name(), "html");
            alternative.addBodyPart(plain);
            alternative.addBodyPart(rich);
            return alternative;
        }

        private MimeBodyPart body() throws MessagingException {
            MimeBodyPart part = new MimeBodyPart();
            if (text != null && html != null) {
                part.setContent(alternative());
            } else if (html != null) {
                part.setText(html, StandardCharsets.UTF_8.name(), "html");
            } else {
                part.setText(text == null ? "" : text, StandardCharsets.UTF_8.name());
            }
            return part;
        }
    }
}
