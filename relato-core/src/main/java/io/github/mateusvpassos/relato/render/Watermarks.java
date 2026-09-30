package io.github.mateusvpassos.relato.render;

import io.github.mateusvpassos.relato.ReportException;
import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Base64;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

/** Desenha a marca d'água em todas as páginas, por baixo do conteúdo, com transparência. */
final class Watermarks {
    private Watermarks() {}

    static void apply(byte[] pdf, Node.Watermark w, OutputStream out) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDImageXObject image = null;
            if (w.imageSrc() != null) {
                String b64 = w.imageSrc().substring(w.imageSrc().indexOf(',') + 1);
                image = PDImageXObject.createFromByteArray(doc, Base64.getDecoder().decode(b64), "watermark");
            }
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant((float) w.opacity());
            gs.setStrokingAlphaConstant((float) w.opacity());
            Color color = parseColor(w.color());
            String text = safe(font, w.text());
            for (PDPage page : doc.getPages()) {
                PDRectangle box = page.getMediaBox();
                try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, true, true)) {
                    cs.saveGraphicsState();
                    cs.setGraphicsStateParameters(gs);
                    float cx = box.getWidth() / 2, cy = box.getHeight() / 2;
                    Matrix m = Matrix.getTranslateInstance(cx, cy);
                    m.rotate(Math.toRadians(-w.angle())); // CSS gira no sentido horário; PDF, anti-horário
                    if (image != null) {
                        float iw = box.getWidth() * 0.6f;
                        float ih = iw * image.getHeight() / image.getWidth();
                        cs.transform(m);
                        cs.drawImage(image, -iw / 2, -ih / 2, iw, ih);
                    } else if (!text.isBlank()) {
                        float size = (float) w.size();
                        float tw = font.getStringWidth(text) / 1000 * size;
                        cs.beginText();
                        cs.setFont(font, size);
                        cs.setNonStrokingColor(color);
                        m.translate(-tw / 2, -size * 0.35f);
                        cs.setTextMatrix(m);
                        cs.showText(text);
                        cs.endText();
                    }
                    cs.restoreGraphicsState();
                }
            }
            doc.save(out);
        } catch (IOException e) {
            throw new ReportException("falha ao aplicar marca d'água: " + e.getMessage(), e);
        }
    }

    /** Remove caracteres que a Helvetica padrão não tem (a marca d'água usa fonte base-14). */
    private static String safe(PDType1Font font, String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            try {
                font.encode(String.valueOf(c));
                sb.append(c);
            } catch (IOException | IllegalArgumentException e) {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private static Color parseColor(String c) {
        try {
            if (c != null && c.startsWith("#") && (c.length() == 7 || c.length() == 4)) {
                String h = c.length() == 4 ? "" + c.charAt(1) + c.charAt(1) + c.charAt(2) + c.charAt(2) + c.charAt(3) + c.charAt(3) : c.substring(1);
                return new Color(Integer.parseInt(h, 16));
            }
        } catch (NumberFormatException ignored) {
            // cai no padrão
        }
        return Color.GRAY;
    }
}
