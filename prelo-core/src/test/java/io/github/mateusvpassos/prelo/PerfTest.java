package io.github.mateusvpassos.prelo;

import java.io.OutputStream;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Medição de volume: -Dprelo.perf=true (fora do build normal). */
class PerfTest {
    @Test
    void volume() {
        Assumptions.assumeTrue(Boolean.getBoolean("prelo.perf"), "perf desligado");
        Prelo prelo = Prelo.create();
        for (int n : new int[] {5_000, 20_000, 50_000}) {
            List<PreloTest.Venda> data = PreloTest.vendas(n);
            Runtime rt = Runtime.getRuntime();
            System.gc();
            long t0 = System.nanoTime();
            prelo.report("vendas").data(data).param("periodo", "x").pdf(OutputStream.nullOutputStream());
            long pdfMs = (System.nanoTime() - t0) / 1_000_000;
            long mem = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
            t0 = System.nanoTime();
            prelo.report("vendas").data(data).param("periodo", "x").xlsx(OutputStream.nullOutputStream());
            long xlsxMs = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("PERF %,d linhas: PDF %,d ms | XLSX %,d ms | heap ~%d MB%n", n, pdfMs, xlsxMs, mem);
        }
    }
}
