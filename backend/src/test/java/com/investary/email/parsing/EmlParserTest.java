package com.investary.email.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.investary.document.TestPdfs;
import com.investary.email.TestEmails;

class EmlParserTest {

    private final EmlParser parser = new EmlParser();

    @Test
    void parsesHtmlOnlyNewsletterWithEncodedVietnameseSubject() throws Exception {
        ParsedEmail email = parse(TestEmails.fixture("tcbs-style-newsletter.eml"));

        assertThat(email.subject()).isEqualTo("Cơ hội đầu tư | 28/10/2026");
        assertThat(email.senderAddress()).isEqualTo("research@tcbs.com.vn");
        assertThat(email.senderName()).isEqualTo("Research Desk");
        assertThat(email.recipients()).containsExactly("investor@example.com");
        assertThat(email.messageId()).isEqualTo("<synthetic-0001@example.com>");
        assertThat(email.sentAt()).isEqualTo(Instant.parse("2026-10-28T12:14:40Z"));
        assertThat(email.htmlBody()).contains("Báo cáo phân tích mới");
        assertThat(email.textBody()).isNull();
        assertThat(email.attachments()).isEmpty();
        assertThat(email.problems()).isEmpty();
    }

    @Test
    void parsesPlainTextEmail() throws Exception {
        byte[] raw = TestEmails.builder().subject("Bản tin sáng").text("VN-Index: Tăng 0,5%.\nhttps://www.ssi.com.vn/report.pdf").build();

        ParsedEmail email = parse(raw);

        assertThat(email.subject()).isEqualTo("Bản tin sáng");
        assertThat(email.textBody()).contains("VN-Index: Tăng 0,5%.");
        assertThat(email.htmlBody()).isNull();
    }

    @Test
    void parsesMultipartAlternativeWithBothBodies() throws Exception {
        byte[] raw = TestEmails.builder().text("Plain version").html("<p>HTML version</p>").build();

        ParsedEmail email = parse(raw);

        assertThat(email.textBody()).isEqualTo("Plain version");
        assertThat(email.htmlBody()).contains("HTML version");
    }

    @Test
    void decodesBase64AndQuotedPrintableHeaders() throws Exception {
        String raw = """
                From: =?UTF-8?B?Q8O0bmcgdHkgQ2jhu6luZyBraG/DoW4=?= <a@example.com>
                To: investor@example.com
                Subject: =?UTF-8?B?QsOhbyBjw6FvIHRo4buLIHRyxrDhu51uZw==?=
                Date: Wed, 28 Oct 2026 08:00:00 +0700
                Content-Type: text/plain; charset=utf-8
                Content-Transfer-Encoding: quoted-printable

                Kh=E1=BB=91i ngo=E1=BA=A1i b=C3=A1n r=C3=B2ng.
                """;

        ParsedEmail email = parse(raw.getBytes(StandardCharsets.US_ASCII));

        assertThat(email.senderName()).isEqualTo("Công ty Chứng khoán");
        assertThat(email.subject()).isEqualTo("Báo cáo thị trường");
        assertThat(email.textBody()).contains("Khối ngoại bán ròng.");
        assertThat(email.sentAt()).isEqualTo(Instant.parse("2026-10-28T01:00:00Z"));
    }

    @Test
    void extractsPdfAttachmentSeparatelyFromBody() throws Exception {
        byte[] pdf = TestPdfs.pdf("attachment");
        byte[] raw = TestEmails.builder().text("See attached report").attachment("Báo cáo VNM.pdf", "application/pdf", pdf).build();

        ParsedEmail email = parse(raw);

        assertThat(email.textBody()).isEqualTo("See attached report");
        assertThat(email.attachments()).singleElement().satisfies(a -> {
            assertThat(a.filename()).isEqualTo("Báo cáo VNM.pdf");
            assertThat(a.contentType()).isEqualTo("application/pdf");
            assertThat(a.content()).isEqualTo(pdf);
            assertThat(a.size()).isEqualTo(pdf.length);
            assertThat(a.sha256()).hasSize(64);
            assertThat(a.inline()).isFalse();
        });
    }

    @Test
    void unknownCharsetIsReportedAsPartialProblem() throws Exception {
        String raw = """
                From: a@example.com
                Subject: Test
                Content-Type: text/plain; charset=x-unknown-charset

                Body text
                """;

        ParsedEmail email = parse(raw.getBytes(StandardCharsets.US_ASCII));

        assertThat(email.textBody()).isEqualTo("Body text\n");
        assertThat(email.problems()).anyMatch(p -> p.contains("x-unknown-charset"));
    }

    @Test
    void brokenMultipartKeepsHeadersAndReportsProblem() throws Exception {
        String raw = """
                From: a@example.com
                Subject: Broken
                Content-Type: multipart/mixed; boundary="missing"

                no boundary here at all
                """;

        ParsedEmail email = parse(raw.getBytes(StandardCharsets.US_ASCII));

        assertThat(email.subject()).isEqualTo("Broken");
        assertThat(email.problems()).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "just some text without headers", "%PDF-1.4 binary-ish content", "Subject: no sender\n\nbody"})
    void rejectsInputThatIsNotAnEmail(String raw) {
        assertThatThrownBy(() -> parse(raw.getBytes(StandardCharsets.ISO_8859_1)))
                .isInstanceOf(MalformedEmailException.class);
    }

    private ParsedEmail parse(byte[] raw) throws MalformedEmailException {
        return parser.parse(new ByteArrayInputStream(raw));
    }
}
