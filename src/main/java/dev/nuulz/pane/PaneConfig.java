package dev.nuulz.pane;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Where pane keeps Chromium and how it starts it. Only [home] is required:
 * <pre>{@code
 * Pane.configure(PaneConfig.at(gameDir.resolve("pane")).userAgentProduct("mygame"));
 * }</pre>
 */
public final class PaneConfig {
    /** The java-cef build this library's Java side matches: the natives must come from the same commit. */
    public static final String JAVA_CEF_COMMIT = "059facfe231b9c032d5a6a12911e10fd5a287842";
    /** Our builds (R2) of github.com/Nuulz/java-cef (BSD): one tar.gz per platform plus its .sha256. */
    public static final String DEFAULT_MIRROR = "https://chromium.nuulm.com";

    final Path home;
    String mirror = System.getProperty("pane.mirror", DEFAULT_MIRROR);
    String userAgentProduct = "pane";
    boolean cache = true;
    final List<String> switches = new ArrayList<>(List.of("--autoplay-policy=no-user-gesture-required", "--no-first-run"));
    LongSupplier window = () -> 0L;

    private PaneConfig(Path home) {
        this.home = home.toAbsolutePath();
    }

    /** Chromium goes in {@code home/<platform>/}, its cache in {@code home/cache/}. */
    public static PaneConfig at(Path home) {
        return new PaneConfig(home);
    }

    /** Another host with the same layout as {@link #DEFAULT_MIRROR} (e.g. your own copy). */
    public PaneConfig mirror(String url) {
        this.mirror = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return this;
    }

    /** Appended to Chromium's user agent ("... Chrome/x pane"). */
    public PaneConfig userAgentProduct(String product) {
        this.userAgentProduct = product;
        return this;
    }

    /** Keep cookies and logins between runs (default on). */
    public PaneConfig cache(boolean keep) {
        this.cache = keep;
        return this;
    }

    /** An extra Chromium command-line switch, e.g. {@code --disable-gpu}. */
    public PaneConfig chromiumSwitch(String sw) {
        switches.add(sw);
        return this;
    }

    /** The GLFW window, for mouse cursors (hovering a link shows a hand). Optional. */
    public PaneConfig glfwWindow(LongSupplier window) {
        this.window = window;
        return this;
    }

    public Path home() { return home; }
    Path cacheDir() { return home.resolve("cache"); }
}
