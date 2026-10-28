package io.github.mateusvpassos.prelo.render;

import io.github.mateusvpassos.prelo.ReportException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/** Pós-processamento do PDF com PDFBox: marca d'água, metadados e proteção por senha/permissões. */
final class PdfPost {
    private PdfPost() {}

    static boolean needed(Node.Document doc) {
        return doc.watermark() != null || doc.meta() != null;
    }

    static void apply(byte[] pdf, Node.Document doc, OutputStream out) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            apply(d, doc);
            d.save(out);
        } catch (IOException e) {
            throw new ReportException("falha ao finalizar o PDF: " + e.getMessage(), e);
        }
    }

    /** Aplica no documento aberto (o chamador salva). A criptografia vale no {@code save}. */
    static void apply(PDDocument d, Node.Document doc) throws IOException {
        if (doc.watermark() != null) Watermarks.applyTo(d, doc.watermark());
        Node.PdfMeta m = doc.meta();
        PDDocumentInformation info = d.getDocumentInformation();
        if (doc.title() != null && !doc.title().isBlank()) info.setTitle(doc.title());
        info.setProducer("prelo");
        if (m == null) return;
        if (m.author() != null) info.setAuthor(m.author());
        if (m.subject() != null) info.setSubject(m.subject());
        if (m.keywords() != null) info.setKeywords(m.keywords());
        if (m.creator() != null) info.setCreator(m.creator());
        if (!m.secured()) return;
        AccessPermission ap = new AccessPermission();
        ap.setCanPrint(m.allowPrint());
        ap.setCanPrintFaithful(m.allowPrint());
        ap.setCanExtractContent(m.allowCopy());
        ap.setCanExtractForAccessibility(true);
        ap.setCanModify(m.allowModify());
        ap.setCanModifyAnnotations(m.allowModify());
        ap.setCanFillInForm(m.allowModify());
        ap.setCanAssembleDocument(m.allowModify());
        String owner = m.ownerPassword() == null || m.ownerPassword().isBlank() ? UUID.randomUUID().toString() : m.ownerPassword();
        StandardProtectionPolicy policy = new StandardProtectionPolicy(owner, m.userPassword() == null ? "" : m.userPassword(), ap);
        policy.setEncryptionKeyLength(256);
        d.protect(policy);
    }
}
