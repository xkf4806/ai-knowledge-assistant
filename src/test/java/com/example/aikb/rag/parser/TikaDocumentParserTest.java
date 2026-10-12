package com.example.aikb.rag.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TikaDocumentParserTest {

    private final TikaDocumentParser parser = new TikaDocumentParser();

    @Test
    void parsesMarkdown() {
        ParsedDocument parsed = parser.parse(
                stream("# 年假\n入职满 1 年的员工，每年享有 10 天带薪年假。"), "handbook.md");

        assertThat(parsed.text()).contains("年假").contains("10 天");
        assertThat(parsed.contentType()).contains("text");
    }

    @Test
    void parsesPlainText() {
        ParsedDocument parsed = parser.parse(stream("VPN 账号有效期为 90 天。"), "it-support.txt");

        assertThat(parsed.text()).contains("90 天");
    }

    @Test
    void parsesWordDocument() throws Exception {
        byte[] docx = buildDocx("试用期为 3 个月，试用期薪资为转正后薪资的 90%。");

        ParsedDocument parsed = parser.parse(new ByteArrayInputStream(docx), "handbook.docx");

        assertThat(parsed.text()).contains("试用期").contains("90%");
    }

    @Test
    void parsesPdf() throws Exception {
        byte[] pdf = buildPdf("Annual leave: 10 days per year.");

        ParsedDocument parsed = parser.parse(new ByteArrayInputStream(pdf), "handbook.pdf");

        assertThat(parsed.text()).contains("Annual leave");
    }

    @Test
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> parser.parse(stream("dummy"), "archive.zip"))
                .isInstanceOf(UnsupportedDocumentException.class)
                .hasMessageContaining("zip");
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] buildDocx(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(text);
            document.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildPdf(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(text);
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
