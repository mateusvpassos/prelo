package io.github.mateusvpassos.relato;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Lê recursos referenciados pelos templates (imagens). */
@FunctionalInterface
public interface ResourceResolver {
    /** Bytes do recurso ou {@code null} se não existe. */
    byte[] read(String location) throws IOException;

    /** {@code classpath:x} pelo classloader dado; caminhos relativos a partir de {@code baseDir} (pode ser nulo). */
    static ResourceResolver standard(ClassLoader loader, Path baseDir) {
        return location -> {
            if (location.startsWith("classpath:")) {
                String p = location.substring("classpath:".length());
                while (p.startsWith("/")) p = p.substring(1);
                ClassLoader cl = loader != null ? loader : Thread.currentThread().getContextClassLoader();
                try (InputStream in = cl.getResourceAsStream(p)) {
                    return in == null ? null : in.readAllBytes();
                }
            }
            String p = location.startsWith("file:") ? location.substring(5) : location;
            Path path = Path.of(p);
            if (!path.isAbsolute() && baseDir != null) path = baseDir.resolve(p);
            return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
        };
    }

    static byte[] readOrThrow(ResourceResolver r, String location) {
        try {
            byte[] b = r.read(location);
            if (b == null) throw new ReportException("recurso não encontrado: " + location);
            return b;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
