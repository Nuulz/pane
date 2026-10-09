package dev.nuulz.pane;

import java.util.Locale;

/** The machine Chromium runs on, named like the download builds: {@code linux_amd64}, {@code macos_arm64}... */
public enum Platform {
    LINUX_AMD64, LINUX_ARM64, WINDOWS_AMD64, WINDOWS_ARM64, MACOS_AMD64, MACOS_ARM64;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isLinux() { return this == LINUX_AMD64 || this == LINUX_ARM64; }
    public boolean isWindows() { return this == WINDOWS_AMD64 || this == WINDOWS_ARM64; }
    public boolean isMac() { return this == MACOS_AMD64 || this == MACOS_ARM64; }

    /** This machine, or null when no build exists for it. */
    public static Platform current() {
        return of(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static Platform of(String os, String arch) {
        String o = os.toLowerCase(Locale.ROOT);
        String a = arch.toLowerCase(Locale.ROOT);
        boolean arm = a.equals("aarch64") || a.equals("arm64");
        boolean x64 = a.equals("amd64") || a.equals("x86_64");
        if (!arm && !x64) return null;
        if (o.contains("linux")) return arm ? LINUX_ARM64 : LINUX_AMD64;
        if (o.contains("win")) return arm ? WINDOWS_ARM64 : WINDOWS_AMD64;
        if (o.contains("mac") || o.contains("darwin")) return arm ? MACOS_ARM64 : MACOS_AMD64;
        return null;
    }
}
