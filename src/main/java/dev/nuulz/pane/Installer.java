package dev.nuulz.pane;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Puts Chromium (the java-cef natives for this platform) in {@code home/<platform>/}: downloads the
 * tar.gz, checks it against the published SHA-256 and unpacks it. Skips all of it when the installed
 * copy already matches that checksum.
 */
final class Installer {
    private final PaneConfig config;
    private final Platform platform;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    Installer(PaneConfig config, Platform platform) {
        this.config = config;
        this.platform = platform;
    }

    Path dir() { return config.home.resolve(platform.id()); }
    private Path stamp() { return config.home.resolve(platform.id() + ".sha256"); }
    private String url(String suffix) {
        return config.mirror + "/java-cef-builds/" + PaneConfig.JAVA_CEF_COMMIT + "/" + platform.id() + ".tar.gz" + suffix;
    }

    /** True when Chromium is in place, downloading it first if needed. */
    void install(Progress progress) throws IOException, InterruptedException {
        progress.update("Checking", -1);
        String expected = fetchChecksum();
        if (Files.isDirectory(dir()) && Files.exists(stamp()) && Files.readString(stamp()).trim().equals(expected)) return;

        Files.createDirectories(config.home);
        Path archive = config.home.resolve(platform.id() + ".tar.gz.part");
        download(archive, progress);
        progress.update("Verifying", -1);
        String actual = sha256(archive);
        if (!actual.equals(expected)) {
            Files.deleteIfExists(archive);
            throw new IOException("Chromium download is corrupt (sha256 " + actual + ", expected " + expected + ")");
        }
        progress.update("Unpacking", 0);
        deleteTree(dir());
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(archive)), 1 << 16)) {
            Tar.extract(in, config.home, Files.size(archive), f -> progress.update("Unpacking", f));
        }
        Files.delete(archive);
        Files.writeString(stamp(), expected);
        progress.update("Done", 1);
    }

    private String fetchChecksum() throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url(".sha256"))).timeout(Duration.ofSeconds(30)).build(),
            HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("No Chromium build for " + platform.id() + " (HTTP " + r.statusCode() + ")");
        return parseChecksum(r.body());
    }

    /** The first 64-hex-digit hash in a .sha256 file: sha256sum's "hash  name" or Windows' PowerShell table. */
    static String parseChecksum(String body) throws IOException {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b[0-9a-fA-F]{64}\\b").matcher(body);
        if (!m.find()) throw new IOException("Unreadable Chromium checksum");
        return m.group().toLowerCase(java.util.Locale.ROOT);
    }

    private void download(Path to, Progress progress) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url(""))).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) throw new IOException("Chromium download failed (HTTP " + r.statusCode() + ")");
        long total = r.headers().firstValueAsLong("content-length").orElse(-1);
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                done += n;
                progress.update("Downloading", total > 0 ? (double) done / total : -1);
            }
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    /** A small ustar reader: files, folders, symlinks, GNU long names and pax paths. Enough for these builds. */
    static final class Tar {
        interface Fraction { void update(double f); }

        static void extract(InputStream in, Path into, long compressedSize, Fraction fraction) throws IOException {
            Path root = into.toAbsolutePath().normalize();
            byte[] header = new byte[512];
            String longName = null;
            long read = 0;
            while (true) {
                if (!readFully(in, header)) return;
                read += 512;
                if (isZero(header)) return;
                String name = longName != null ? longName : name(header);
                longName = null;
                char type = (char) header[156];
                long size = octal(header, 124, 12);
                int mode = (int) octal(header, 100, 8);
                if (type == 'L' || type == 'x') {
                    String data = new String(readBytes(in, size), StandardCharsets.UTF_8);
                    longName = type == 'L' ? data.replace("\0", "") : paxPath(data);
                    read += padded(size);
                    continue;
                }
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) throw new IOException("Unsafe path in archive: " + name);
                switch (type) {
                    case '5' -> Files.createDirectories(target);
                    case '2' -> {
                        Files.createDirectories(target.getParent());
                        Files.deleteIfExists(target);
                        Files.createSymbolicLink(target, Path.of(cString(header, 157, 100)));
                    }
                    case '0', '\0' -> {
                        Files.createDirectories(target.getParent());
                        try (OutputStream out = Files.newOutputStream(target)) { copy(in, out, size); }
                        skip(in, padded(size) - size);
                        if ((mode & 0100) != 0) executable(target);
                    }
                    default -> skip(in, padded(size));
                }
                if (type == '0' || type == '\0') read += padded(size);
                if (compressedSize > 0) fraction.update(Math.min(1, read / (compressedSize * 2.6))); // ~2.6x unpacked
            }
        }

        private static String name(byte[] h) {
            String prefix = cString(h, 345, 155);
            String name = cString(h, 0, 100);
            return prefix.isEmpty() ? name : prefix + "/" + name;
        }

        private static String paxPath(String data) {
            for (String line : data.split("\n")) {
                int eq = line.indexOf(" path=");
                if (eq >= 0) return line.substring(eq + 6);
            }
            return null;
        }

        static String cString(byte[] b, int off, int len) {
            int end = off;
            while (end < off + len && b[end] != 0) end++;
            return new String(b, off, end - off, StandardCharsets.UTF_8);
        }

        static long octal(byte[] b, int off, int len) {
            long v = 0;
            for (int i = off; i < off + len; i++) {
                byte c = b[i];
                if (c == 0 || c == ' ') { if (v != 0) break; else continue; }
                v = v * 8 + (c - '0');
            }
            return v;
        }

        private static long padded(long size) { return (size + 511) & ~511L; }

        private static boolean isZero(byte[] b) {
            for (byte x : b) if (x != 0) return false;
            return true;
        }

        private static boolean readFully(InputStream in, byte[] b) throws IOException {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) { if (off == 0) return false; throw new EOFException(); }
                off += n;
            }
            return true;
        }

        private static byte[] readBytes(InputStream in, long size) throws IOException {
            byte[] data = new byte[(int) size];
            if (!readFully(in, data) && size > 0) throw new EOFException();
            skip(in, padded(size) - size);
            return data;
        }

        private static void copy(InputStream in, OutputStream out, long size) throws IOException {
            byte[] buf = new byte[1 << 16];
            while (size > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, size));
                if (n < 0) throw new EOFException();
                out.write(buf, 0, n);
                size -= n;
            }
        }

        private static void skip(InputStream in, long n) throws IOException {
            while (n > 0) {
                long s = in.skip(n);
                if (s <= 0) { if (in.read() < 0) throw new EOFException(); s = 1; }
                n -= s;
            }
        }

        private static void executable(Path file) {
            try {
                Set<PosixFilePermission> p = Files.getPosixFilePermissions(file);
                p.add(PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(file, p);
            } catch (UnsupportedOperationException | IOException ignored) {
                file.toFile().setExecutable(true); // Windows: no POSIX permissions, nothing to do
            }
        }
    }
}
