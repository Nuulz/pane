package dev.nuulz.pane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InstallerTest {
    @Test
    void platformNames() {
        assertEquals(Platform.LINUX_AMD64, Platform.of("Linux", "amd64"));
        assertEquals(Platform.MACOS_ARM64, Platform.of("Mac OS X", "aarch64"));
        assertEquals(Platform.WINDOWS_AMD64, Platform.of("Windows 11", "x86_64"));
        assertNull(Platform.of("Linux", "x86"));
        assertEquals("linux_amd64", Platform.LINUX_AMD64.id());
    }

    @Test
    void unpacksFilesFoldersAndLongNames(@TempDir Path dir) throws IOException {
        String longName = "a/" + "x".repeat(120) + ".txt";
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        entry(tar, "a/", '5', new byte[0], 0755);
        entry(tar, "././@LongLink", 'L', (longName + "\0").getBytes(StandardCharsets.UTF_8), 0644);
        entry(tar, "ignored", '0', "long".getBytes(StandardCharsets.UTF_8), 0644);
        entry(tar, "a/run", '0', "#!".getBytes(StandardCharsets.UTF_8), 0755);
        tar.write(new byte[1024]);
        Installer.Tar.extract(new ByteArrayInputStream(tar.toByteArray()), dir, 0, f -> {});
        assertEquals("long", Files.readString(dir.resolve(longName)));
        assertEquals("#!", Files.readString(dir.resolve("a/run")));
        assertTrue(Files.isExecutable(dir.resolve("a/run")));
    }

    @Test
    void refusesPathsOutsideTheFolder(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        entry(tar, "../evil", '0', "x".getBytes(StandardCharsets.UTF_8), 0644);
        tar.write(new byte[1024]);
        assertThrows(IOException.class, () -> Installer.Tar.extract(new ByteArrayInputStream(tar.toByteArray()), dir, 0, f -> {}));
    }

    @Test
    void sha256(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("f");
        Files.writeString(f, "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Installer.sha256(f));
    }

    @Test
    void checksumsInBothFormats() throws IOException {
        String hash = "aaff42ca9a0bf59f2f2590a1262f5facba6db68a90e63a31c926782750610862";
        assertEquals(hash, Installer.parseChecksum(hash + "  windows_amd64.tar.gz\n"));
        String powershell = "\r\nAlgorithm       Hash          Path\r\n---------       ----          ----\r\nSHA256          "
            + hash.toUpperCase() + "       D:\\a\\java-cef\\jcef_build\r\n";
        assertEquals(hash, Installer.parseChecksum(powershell));
        assertThrows(IOException.class, () -> Installer.parseChecksum("nope"));
    }

    private static void entry(ByteArrayOutputStream out, String name, char type, byte[] data, int mode) throws IOException {
        byte[] h = new byte[512];
        put(h, 0, name, 100);
        put(h, 100, String.format("%07o", mode), 8);
        put(h, 124, String.format("%011o", data.length), 12);
        h[156] = (byte) type;
        put(h, 257, "ustar", 6);
        out.write(h);
        out.write(data);
        out.write(new byte[(512 - data.length % 512) % 512]);
    }

    private static void put(byte[] h, int off, String s, int max) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, h, off, Math.min(b.length, max));
    }
}
