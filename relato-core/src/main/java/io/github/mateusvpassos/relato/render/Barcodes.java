package io.github.mateusvpassos.relato.render;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import io.github.mateusvpassos.relato.ReportException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/** Códigos de barras e QR (ZXing) como PNG. */
final class Barcodes {
    private Barcodes() {}

    private static final Map<String, BarcodeFormat> FORMATS = Map.of(
            "qr", BarcodeFormat.QR_CODE,
            "code128", BarcodeFormat.CODE_128,
            "code39", BarcodeFormat.CODE_39,
            "ean13", BarcodeFormat.EAN_13,
            "ean8", BarcodeFormat.EAN_8,
            "upca", BarcodeFormat.UPC_A,
            "itf", BarcodeFormat.ITF,
            "datamatrix", BarcodeFormat.DATA_MATRIX,
            "pdf417", BarcodeFormat.PDF_417);

    static boolean isTwoDimensional(String format) {
        String f = format.toLowerCase(Locale.ROOT);
        return f.equals("qr") || f.equals("datamatrix");
    }

    static byte[] png(String value, String format) {
        BarcodeFormat f = FORMATS.get(format.toLowerCase(Locale.ROOT));
        if (f == null) throw new ReportException("formato de código desconhecido '" + format + "' (use: " + String.join(", ", FORMATS.keySet()) + ")");
        boolean twoD = isTwoDimensional(format);
        try {
            // largura mínima; o PDF escala sem perder nitidez porque os módulos são pixels inteiros
            BitMatrix m = new MultiFormatWriter().encode(value, f, twoD ? 0 : 0, twoD ? 0 : 60,
                    Map.of(EncodeHintType.MARGIN, twoD ? 1 : 4, EncodeHintType.CHARACTER_SET, "UTF-8"));
            int scale = twoD ? 8 : 3;
            BufferedImage img = new BufferedImage(m.getWidth() * scale, m.getHeight() * (twoD ? scale : 1), BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    boolean on = m.get(x / scale, twoD ? y / scale : y);
                    img.setRGB(x, y, on ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (WriterException | IllegalArgumentException e) {
            throw new ReportException("valor inválido para " + format + ": '" + value + "' (" + e.getMessage() + ")", e);
        } catch (IOException e) {
            throw new ReportException("falha ao gerar código: " + e.getMessage(), e);
        }
    }
}
