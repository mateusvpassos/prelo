package io.github.mateusvpassos.prelo.render;

import io.github.mateusvpassos.prelo.ReportException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleFunction;
import javax.imageio.ImageIO;

/** Gráficos simples (barras, linhas, área, pizza, rosca) desenhados com Java2D em PNG de alta resolução. */
final class Charts {
    private Charts() {}

    static final List<String> PALETTE = List.of("#3574f0", "#e8833a", "#2ea44f", "#d64545", "#8a63d2", "#17a2b8", "#c9a227", "#6c757d",
            "#e83e8c", "#20c997");

    /** Uma série: nome, cor e um valor por categoria. */
    record Series(String name, Color color, double[] values) {}

    /** Escala de desenho: pixels por pt (imagem nítida quando o PDF amplia). */
    private static final float SCALE = 3f;

    static byte[] png(String type, String title, List<String> categories, List<Series> series, double widthPt, double heightPt,
                      boolean legend, boolean showValues, DoubleFunction<String> fmt) {
        int w = (int) Math.max(60, widthPt * SCALE), h = (int) Math.max(40, heightPt * SCALE);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.scale(SCALE, SCALE);
            double W = w / SCALE, H = h / SCALE;
            double top = 4;
            if (title != null && !title.isBlank()) {
                g.setFont(font(Font.BOLD, 9));
                g.setColor(new Color(0x22, 0x22, 0x22));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(title, (float) ((W - fm.stringWidth(title)) / 2), (float) (top + fm.getAscent()));
                top += fm.getHeight() + 4;
            }
            String t = type == null ? "bar" : type.toLowerCase(Locale.ROOT);
            boolean round = t.equals("pie") || t.equals("donut");
            boolean showLegend = legend && (round || series.size() > 1);
            double bottom = H - 4;
            if (showLegend && !round) bottom -= legendHeight(g, series.stream().map(Series::name).toList(), W);
            if (round) {
                Series s = series.get(0);
                double legendW = showLegend ? Math.min(W * 0.42, 140) : 0;
                pie(g, t.equals("donut"), categories, s, 4, top, W - legendW - 8, bottom - top, showValues, fmt);
                if (showLegend) legendVertical(g, categories, colorsFor(categories.size(), series), W - legendW, top + 6, legendW);
            } else {
                switch (t) {
                    case "hbar" -> hbar(g, categories, series, 4, top, W - 8, bottom - top, showValues, fmt);
                    case "line", "area" -> line(g, t.equals("area"), categories, series, 4, top, W - 8, bottom - top, showValues, fmt);
                    default -> bar(g, categories, series, 4, top, W - 8, bottom - top, showValues, fmt);
                }
                if (showLegend) legendHorizontal(g, series, 4, bottom + 4, W - 8);
            }
        } finally {
            g.dispose();
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ReportException("falha ao gerar gráfico: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------ barras

    private static void bar(Graphics2D g, List<String> cats, List<Series> series, double x, double y, double w, double h,
                            boolean values, DoubleFunction<String> fmt) {
        Axis ax = axis(series, g, fmt);
        double left = x + ax.labelWidth + 4, bottom = y + h - 12, top = y + (values ? 8 : 2);
        double plotW = x + w - left, plotH = bottom - top;
        grid(g, ax, left, top, plotW, plotH, true, fmt);
        int n = cats.size(), k = series.size();
        if (n == 0) return;
        double band = plotW / n, groupW = band * 0.72, barW = groupW / k;
        g.setFont(font(Font.PLAIN, 6.5f));
        for (int i = 0; i < n; i++) {
            double gx = left + i * band + (band - groupW) / 2;
            for (int s = 0; s < k; s++) {
                double v = series.get(s).values()[i];
                double y0 = ax.pos(0, top, plotH), y1 = ax.pos(v, top, plotH);
                g.setColor(series.get(s).color());
                g.fill(new java.awt.geom.Rectangle2D.Double(gx + s * barW + 0.4, Math.min(y0, y1), barW - 0.8, Math.abs(y1 - y0)));
                if (values) label(g, fmt.apply(v), gx + s * barW + barW / 2, Math.min(y0, y1) - 1.5, true);
            }
            g.setColor(new Color(0x44, 0x44, 0x44));
            centered(g, ellipsize(g, cats.get(i), band - 2), left + i * band + band / 2, bottom + 8.5);
        }
    }

    private static void hbar(Graphics2D g, List<String> cats, List<Series> series, double x, double y, double w, double h,
                             boolean values, DoubleFunction<String> fmt) {
        Axis ax = axis(series, g, fmt);
        g.setFont(font(Font.PLAIN, 6.5f));
        double catW = 0;
        for (String c : cats) catW = Math.max(catW, g.getFontMetrics().stringWidth(c));
        catW = Math.min(catW, w * 0.35);
        double left = x + catW + 4, top = y + 2, bottom = y + h - 10, right = x + w - (values ? 28 : 4);
        double plotW = right - left, plotH = bottom - top;
        grid(g, ax, left, top, plotW, plotH, false, fmt);
        int n = cats.size(), k = series.size();
        if (n == 0) return;
        double band = plotH / n, groupH = band * 0.72, barH = groupH / k;
        for (int i = 0; i < n; i++) {
            double gy = top + i * band + (band - groupH) / 2;
            for (int s = 0; s < k; s++) {
                double v = series.get(s).values()[i];
                double x0 = ax.posH(0, left, plotW), x1 = ax.posH(v, left, plotW);
                g.setColor(series.get(s).color());
                g.fill(new java.awt.geom.Rectangle2D.Double(Math.min(x0, x1), gy + s * barH + 0.4, Math.abs(x1 - x0), barH - 0.8));
                if (values) {
                    g.setColor(new Color(0x33, 0x33, 0x33));
                    g.drawString(fmt.apply(v), (float) (Math.max(x0, x1) + 2), (float) (gy + s * barH + barH / 2 + 2.3));
                }
            }
            g.setColor(new Color(0x44, 0x44, 0x44));
            String c = ellipsize(g, cats.get(i), catW);
            g.drawString(c, (float) (left - 3 - g.getFontMetrics().stringWidth(c)), (float) (top + i * band + band / 2 + 2.3));
        }
    }

    // ------------------------------------------------------------ linhas

    private static void line(Graphics2D g, boolean area, List<String> cats, List<Series> series, double x, double y, double w, double h,
                             boolean values, DoubleFunction<String> fmt) {
        Axis ax = axis(series, g, fmt);
        double left = x + ax.labelWidth + 4, bottom = y + h - 12, top = y + (values ? 8 : 2);
        double plotW = x + w - left - 4, plotH = bottom - top;
        grid(g, ax, left, top, plotW, plotH, true, fmt);
        int n = cats.size();
        if (n == 0) return;
        // recuo nas pontas para o primeiro/último rótulo e valor não serem cortados
        double pad = n > 1 ? Math.min(plotW * 0.06, 18) : 0;
        double step = n > 1 ? (plotW - 2 * pad) / (n - 1) : 0;
        left += pad;
        plotW -= 2 * pad;
        for (Series s : series) {
            Path2D.Double p = new Path2D.Double();
            for (int i = 0; i < n; i++) {
                double px = left + (n > 1 ? i * step : plotW / 2), py = ax.pos(s.values()[i], top, plotH);
                if (i == 0) p.moveTo(px, py);
                else p.lineTo(px, py);
            }
            if (area && n > 1) {
                Path2D.Double fill = new Path2D.Double(p);
                fill.lineTo(left + (n - 1) * step, ax.pos(0, top, plotH));
                fill.lineTo(left, ax.pos(0, top, plotH));
                fill.closePath();
                g.setColor(new Color(s.color().getRed(), s.color().getGreen(), s.color().getBlue(), 60));
                g.fill(fill);
            }
            g.setColor(s.color());
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(p);
            for (int i = 0; i < n; i++) {
                double px = left + (n > 1 ? i * step : plotW / 2), py = ax.pos(s.values()[i], top, plotH);
                g.fill(new Ellipse2D.Double(px - 1.6, py - 1.6, 3.2, 3.2));
                if (values) label(g, fmt.apply(s.values()[i]), px, py - 3, true);
            }
        }
        g.setStroke(new BasicStroke(1f));
        g.setFont(font(Font.PLAIN, 6.5f));
        g.setColor(new Color(0x44, 0x44, 0x44));
        int every = Math.max(1, (int) Math.ceil(n / Math.max(1.0, plotW / 34)));
        for (int i = 0; i < n; i += every) centered(g, cats.get(i), left + (n > 1 ? i * step : plotW / 2), bottom + 8.5);
    }

    // ------------------------------------------------------------ pizza

    private static void pie(Graphics2D g, boolean donut, List<String> cats, Series s, double x, double y, double w, double h,
                            boolean values, DoubleFunction<String> fmt) {
        double total = 0;
        for (double v : s.values()) total += Math.max(0, v);
        double d = Math.min(w, h) - 4, cx = x + w / 2, cy = y + h / 2;
        List<Color> colors = colorsFor(cats.size(), List.of(s));
        if (total <= 0) {
            g.setColor(new Color(0xdd, 0xdd, 0xdd));
            g.fill(new Ellipse2D.Double(cx - d / 2, cy - d / 2, d, d));
            return;
        }
        double start = 90;
        g.setFont(font(Font.BOLD, 6.5f));
        for (int i = 0; i < cats.size(); i++) {
            double v = Math.max(0, s.values()[i]);
            double ext = v / total * 360;
            g.setColor(colors.get(i));
            g.fill(new Arc2D.Double(cx - d / 2, cy - d / 2, d, d, start, -ext, Arc2D.PIE));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(0.8f));
            g.draw(new Arc2D.Double(cx - d / 2, cy - d / 2, d, d, start, -ext, Arc2D.PIE));
            if (values && ext > 14) {
                double mid = Math.toRadians(start - ext / 2), r = donut ? d * 0.39 : d * 0.32;
                String pct = String.format(Locale.ROOT, "%.0f%%", v / total * 100);
                centered(g, pct, cx + Math.cos(mid) * r, cy - Math.sin(mid) * r + 2.3);
            }
            start -= ext;
        }
        if (donut) {
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(cx - d * 0.28, cy - d * 0.28, d * 0.56, d * 0.56));
            g.setColor(new Color(0x22, 0x22, 0x22));
            g.setFont(font(Font.BOLD, 8));
            centered(g, fmt.apply(total), cx, cy + 3);
        }
    }

    // ------------------------------------------------------------ eixo, grade, legenda

    private record Axis(double min, double max, double step, double labelWidth) {
        double pos(double v, double top, double plotH) {
            return top + plotH - (v - min) / (max - min) * plotH;
        }

        double posH(double v, double left, double plotW) {
            return left + (v - min) / (max - min) * plotW;
        }
    }

    private static Axis axis(List<Series> series, Graphics2D g, DoubleFunction<String> fmt) {
        double lo = 0, hi = 0;
        for (Series s : series) for (double v : s.values()) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        if (hi == lo) hi = lo + 1;
        double step = nice((hi - lo) / 5);
        double min = Math.floor(lo / step) * step, max = Math.ceil(hi / step) * step;
        g.setFont(font(Font.PLAIN, 6f));
        double lw = 0;
        for (double v = min; v <= max + step / 2; v += step) lw = Math.max(lw, g.getFontMetrics().stringWidth(fmt.apply(v)));
        return new Axis(min, max, step, lw);
    }

    private static double nice(double raw) {
        double exp = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / exp;
        double n = f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10;
        return n * exp;
    }

    private static void grid(Graphics2D g, Axis ax, double left, double top, double plotW, double plotH, boolean vertical,
                             DoubleFunction<String> fmt) {
        g.setFont(font(Font.PLAIN, 6f));
        g.setStroke(new BasicStroke(0.4f));
        for (double v = ax.min; v <= ax.max + ax.step / 2; v += ax.step) {
            g.setColor(Math.abs(v) < ax.step / 1000 ? new Color(0x99, 0x99, 0x99) : new Color(0xe3, 0xe6, 0xea));
            String lbl = fmt.apply(v);
            if (vertical) {
                double py = ax.pos(v, top, plotH);
                g.draw(new java.awt.geom.Line2D.Double(left, py, left + plotW, py));
                g.setColor(new Color(0x77, 0x77, 0x77));
                g.drawString(lbl, (float) (left - 3 - g.getFontMetrics().stringWidth(lbl)), (float) (py + 2));
            } else {
                double px = ax.posH(v, left, plotW);
                g.draw(new java.awt.geom.Line2D.Double(px, top, px, top + plotH));
                g.setColor(new Color(0x77, 0x77, 0x77));
                centered(g, lbl, px, top + plotH + 7);
            }
        }
        g.setStroke(new BasicStroke(1f));
    }

    private static double legendHeight(Graphics2D g, List<String> names, double w) {
        return names.size() > 1 ? 12 : 0;
    }

    private static void legendHorizontal(Graphics2D g, List<Series> series, double x, double y, double w) {
        if (series.size() < 2) return;
        g.setFont(font(Font.PLAIN, 6.5f));
        FontMetrics fm = g.getFontMetrics();
        double total = 0;
        for (Series s : series) total += 10 + fm.stringWidth(s.name()) + 8;
        double cx = x + Math.max(0, (w - total) / 2);
        for (Series s : series) {
            g.setColor(s.color());
            g.fill(new java.awt.geom.Rectangle2D.Double(cx, y + 1, 6, 6));
            g.setColor(new Color(0x44, 0x44, 0x44));
            g.drawString(s.name(), (float) (cx + 8.5), (float) (y + 6.6));
            cx += 10 + fm.stringWidth(s.name()) + 8;
        }
    }

    private static void legendVertical(Graphics2D g, List<String> cats, List<Color> colors, double x, double y, double w) {
        g.setFont(font(Font.PLAIN, 6.5f));
        for (int i = 0; i < cats.size(); i++) {
            double ly = y + i * 10;
            g.setColor(colors.get(i));
            g.fill(new java.awt.geom.Rectangle2D.Double(x, ly, 6, 6));
            g.setColor(new Color(0x44, 0x44, 0x44));
            g.drawString(ellipsize(g, cats.get(i), w - 12), (float) (x + 8.5), (float) (ly + 5.6));
        }
    }

    /** Pizza: uma cor por categoria (a cor da série vale só se houver uma categoria). */
    private static List<Color> colorsFor(int n, List<Series> series) {
        List<Color> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) out.add(color(PALETTE.get(i % PALETTE.size())));
        return out;
    }

    static Color color(String hex) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            return new Color(Integer.parseInt(h, 16));
        } catch (RuntimeException e) {
            return Color.GRAY;
        }
    }

    private static void label(Graphics2D g, String s, double cx, double baseline, boolean dark) {
        g.setFont(font(Font.PLAIN, 6f));
        g.setColor(new Color(0x33, 0x33, 0x33));
        centered(g, s, cx, baseline);
    }

    private static void centered(Graphics2D g, String s, double cx, double baseline) {
        g.drawString(s, (float) (cx - g.getFontMetrics().stringWidth(s) / 2.0), (float) baseline);
    }

    private static String ellipsize(Graphics2D g, String s, double max) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= max) return s;
        String t = s;
        while (t.length() > 1 && fm.stringWidth(t + "…") > max) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    private static Font font(int style, float size) {
        return new Font(Font.SANS_SERIF, style, 1).deriveFont(style, size);
    }

    /** Converte tamanhos CSS simples (mm, cm, pt, px, in) para pt. */
    static double toPt(String css, double def) {
        if (css == null || css.isBlank()) return def;
        String s = css.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("mm")) return Double.parseDouble(s.substring(0, s.length() - 2)) * 72 / 25.4;
            if (s.endsWith("cm")) return Double.parseDouble(s.substring(0, s.length() - 2)) * 72 / 2.54;
            if (s.endsWith("in")) return Double.parseDouble(s.substring(0, s.length() - 2)) * 72;
            if (s.endsWith("px")) return Double.parseDouble(s.substring(0, s.length() - 2)) * 0.75;
            if (s.endsWith("pt")) return Double.parseDouble(s.substring(0, s.length() - 2));
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
