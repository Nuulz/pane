package dev.nuulz.pane;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Chromium in your app, off-screen: web pages as pixels you draw yourself, fed with GLFW input.
 *
 * <pre>{@code
 * Pane.configure(PaneConfig.at(dir));
 * Pane.install((step, f) -> ...);      // once, off your render thread: downloads Chromium (~120 MB)
 * Pane.start();                        // on your render thread
 * PaneView page = Pane.open("https://example.com");
 * page.resize(w, h);
 * // every frame: Pane.pump(); then draw page.pixels() when page.version() changed
 * }</pre>
 *
 * Needs LWJGL (GLFW) on the classpath: java-cef's native side reads GLFW's key and button codes.
 */
public final class Pane {
    public enum State { NEW, INSTALLING, INSTALLED, RUNNING, FAILED, STOPPED }

    private static PaneConfig config;
    private static volatile State state = State.NEW;
    private static volatile String failure;
    private static CefApp app;
    private static CefClient client;
    private static final Set<PaneView> views = Collections.newSetFromMap(new WeakHashMap<>());

    private Pane() {}

    public static synchronized void configure(PaneConfig c) {
        if (state != State.NEW) throw new IllegalStateException("configure before install");
        config = c;
    }

    public static State state() { return state; }
    /** Why it failed, for the user. */
    public static String failure() { return failure; }
    public static boolean running() { return state == State.RUNNING; }

    /**
     * Gets Chromium in place (blocking: run it on a worker thread). Fast when it's already there.
     * Returns false on failure, with the reason in {@link #failure()}.
     */
    public static boolean install(Progress progress) {
        Platform platform = Platform.current();
        if (config == null) throw new IllegalStateException("Pane.configure first");
        if (platform == null) return fail("Chromium isn't available for " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        state = State.INSTALLING;
        try {
            new Installer(config, platform).install(progress);
            state = State.INSTALLED;
            return true;
        } catch (IOException | RuntimeException e) {
            return fail(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("interrupted");
        }
    }

    /** Starts Chromium (on the thread that will call {@link #pump()}). Needs {@link #install} first. */
    public static synchronized boolean start() {
        if (state == State.RUNNING) return true;
        if (state != State.INSTALLED) return false;
        Platform platform = Platform.current();
        Path dir = new Installer(config, platform).dir();
        System.setProperty("jcef.path", dir.toString());
        try {
            String[] args = config.switches.toArray(String[]::new);
            if (!CefApp.startup(args)) return fail("Chromium didn't start");
            CefSettings settings = new CefSettings();
            settings.windowless_rendering_enabled = true;
            settings.user_agent_product = config.userAgentProduct;
            if (config.cache) settings.cache_path = settings.root_cache_path = config.cacheDir().toString();
            settings.background_color = settings.new ColorType(255, 255, 255, 255);
            app = CefApp.getInstance(args, settings);
            client = app.createClient();
            hook(client);
        } catch (Throwable t) { // UnsatisfiedLinkError and friends: a broken install
            return fail("Chromium didn't start: " + t);
        }
        if (platform.isMac()) app.macOSTerminationRequestRunnable = Pane::shutdown;
        else Runtime.getRuntime().addShutdownHook(new Thread(Pane::shutdown, "pane-shutdown"));
        state = State.RUNNING;
        return true;
    }

    /**
     * java-cef's native side cancels every popup in off-screen mode, before Java hears of it, so new
     * windows are redirected in the page itself: links with a target and window.open() load in place.
     */
    private static final String SAME_PAGE = """
        (() => {
          if (window.__pane) return; window.__pane = true;
          window.open = (url) => { if (url) location.href = new URL(url, location.href).href; return window; };
          document.addEventListener('click', (e) => {
            const a = e.target && e.target.closest && e.target.closest('a[target]');
            if (!a || !a.href || a.target === '_self' || a.target === '_parent' || a.target === '_top') return;
            e.preventDefault(); location.href = a.href;
          }, true);
        })();""";

    /** Page titles, loading state and history, and links that would open a new window (see [SAME_PAGE]). */
    private static void hook(CefClient client) {
        client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override public void onTitleChange(CefBrowser browser, String title) {
                if (browser instanceof PaneView v) v.title = title == null ? "" : title;
            }
        });
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override public void onLoadingStateChange(CefBrowser browser, boolean loading, boolean back, boolean forward) {
                if (browser instanceof PaneView v) { v.loading = loading; v.canBack = back; v.canForward = forward; }
            }

            @Override public void onLoadEnd(CefBrowser browser, CefFrame frame, int status) {
                frame.executeJavaScript(SAME_PAGE, frame.getURL(), 0);
            }
        });
    }

    /** Chromium's work for now: paints, network replies, timers. Call it every frame while running. */
    public static void pump() {
        if (state == State.RUNNING) app.N_DoMessageLoopWork();
    }

    /** A new page (white until it paints). Size it with {@link PaneView#resize}. */
    public static PaneView open(String url) {
        return open(url, false);
    }

    public static synchronized PaneView open(String url, boolean transparent) {
        if (state != State.RUNNING) throw new IllegalStateException("Pane isn't running (" + state + ")");
        PaneView view = new PaneView(client, config, url, transparent);
        view.setCloseAllowed();
        view.createImmediately();
        views.add(view);
        return view;
    }

    static synchronized void forget(PaneView view) {
        views.remove(view);
    }

    /** Closes every page and stops Chromium. Also runs by itself when the JVM exits. */
    public static synchronized void shutdown() {
        if (state != State.RUNNING) return;
        state = State.STOPPED;
        for (PaneView v : views.toArray(PaneView[]::new)) v.close();
        client.dispose();
        app.dispose();
    }

    private static boolean fail(String why) {
        failure = why;
        state = State.FAILED;
        return false;
    }
}
