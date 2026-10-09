package dev.nuulz.pane;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.cef.misc.CefCursorType;
import org.cef.handler.CefScreenInfo;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * One web page, rendered off-screen. Chromium paints into {@link #pixels()} (BGRA, {@link #width()} x
 * {@link #height()}, rows packed); draw it however your engine likes and re-upload when {@link #version()}
 * changes. Input uses GLFW's codes (keys, mods, mouse buttons), in physical pixels from the page's corner.
 *
 * <p>Everything here runs on the thread that calls {@link Pane#pump()}.
 */
public final class PaneView extends CefBrowserOsr {
    // Chromium's mouse button masks, in modifiers while a button is held (drags).
    private static final int LEFT_HELD = 0x10, MIDDLE_HELD = 0x20, RIGHT_HELD = 0x40;
    private static final int DOUBLE_CLICK_MS = 400, DOUBLE_CLICK_PX = 4;

    private final PaneConfig config;

    private ByteBuffer pixels;
    private int width, height;
    private volatile int version;

    private ByteBuffer popup;
    private Rectangle popupBounds;
    private boolean popupShown;

    private int held;
    private int lastButton = -1, clicks;
    private long lastClickAt;
    private int lastX, lastY;

    volatile String title = "";
    volatile boolean loading;
    volatile boolean canBack, canForward;
    private int cursor = -1;

    PaneView(CefClient client, PaneConfig config, String url, boolean transparent) {
        super(client, url, transparent, null);
        this.config = config;
    }

    // ---- the picture -----------------------------------------------------------------------------

    /** The page, BGRA; null until the first paint. */
    public ByteBuffer pixels() { return pixels; }
    public int width() { return width; }
    public int height() { return height; }
    /** Changes on every paint (page or dropdown). */
    public int version() { return version; }

    /** An open dropdown (a {@code <select>}), BGRA, to draw over the page at {@link #popupBounds()}; else null. */
    public ByteBuffer popupPixels() { return popupShown ? popup : null; }
    public Rectangle popupBounds() { return popupBounds; }

    @Override
    public void onPaint(CefBrowser browser, boolean isPopup, Rectangle[] dirty, ByteBuffer buffer, int w, int h) {
        if (dirty.length == 0) return;
        if (isPopup) {
            if (popup == null || popup.capacity() < w * h * 4) popup = ByteBuffer.allocateDirect(w * h * 4);
            MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), MemoryUtil.memAddress(popup), (long) w * h * 4);
            popupShown = true;
        } else {
            if (pixels == null || w != width || h != height) {
                pixels = ByteBuffer.allocateDirect(w * h * 4);
                width = w;
                height = h;
                dirty = new Rectangle[]{new Rectangle(0, 0, w, h)};
            }
            long from = MemoryUtil.memAddress(buffer), to = MemoryUtil.memAddress(pixels);
            for (Rectangle r : dirty) {
                int cols = Math.min(r.width, w - r.x);
                for (int y = r.y; y < Math.min(r.y + r.height, h); y++) {
                    long at = ((long) y * w + r.x) * 4;
                    MemoryUtil.memCopy(from + at, to + at, (long) cols * 4);
                }
            }
        }
        version++;
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        super.onPopupShow(browser, show);
        if (!show) { popupShown = false; version++; }
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        super.onPopupSize(browser, size);
        popupBounds = size;
    }

    /** The page's size in physical pixels (Chromium re-paints at it). */
    public void resize(int w, int h) {
        browser_rect_.setBounds(0, 0, Math.max(1, w), Math.max(1, h));
        wasResized(Math.max(1, w), Math.max(1, h));
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo info) {
        super.getScreenInfo(browser, info);
        info.device_scale_factor = 1;
        return true;
    }

    // ---- input (GLFW codes) ----------------------------------------------------------------------

    public void mouseMove(int x, int y) {
        lastX = x; lastY = y;
        sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, x, y, 0, 0, held));
    }

    /** The pointer left the page (hover effects end). */
    public void mouseLeave() {
        sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_EXIT, lastX, lastY, 0, 0, held));
    }

    /** [button] is GLFW's: 0 left, 1 right, 2 middle. Double and triple clicks are counted here. */
    public void mouseDown(int x, int y, int button, int mods) {
        long now = System.currentTimeMillis();
        boolean again = button == lastButton && now - lastClickAt < DOUBLE_CLICK_MS
            && Math.abs(x - lastX) <= DOUBLE_CLICK_PX && Math.abs(y - lastY) <= DOUBLE_CLICK_PX;
        clicks = again ? Math.min(clicks + 1, 3) : 1;
        lastButton = button; lastClickAt = now; lastX = x; lastY = y;
        held |= heldMask(button);
        sendMouseEvent(new CefMouseEvent(GLFW.GLFW_PRESS, x, y, clicks, nativeButton(button), mods | held));
    }

    public void mouseUp(int x, int y, int button, int mods) {
        held &= ~heldMask(button);
        sendMouseEvent(new CefMouseEvent(GLFW.GLFW_RELEASE, x, y, clicks, nativeButton(button), mods | held));
    }

    /** [amount] in wheel notches, positive is up; shift scrolls sideways. */
    public void wheel(int x, int y, double amount, int mods) {
        sendMouseWheelEvent(new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, x, y, amount, mods));
    }

    /** A key went down. Ctrl+R reloads, Alt+arrows go back and forward, Ctrl+plus/minus/0 zoom. */
    public void keyDown(int key, int scancode, int mods) {
        if (shortcut(key, mods)) return;
        sendKey(GLFW.GLFW_PRESS, key, scancode, mods);
    }

    public void keyUp(int key, int scancode, int mods) {
        sendKey(GLFW.GLFW_RELEASE, key, scancode, mods);
    }

    /** A character typed (after the keyboard layout): what ends up in a text field. */
    public void typed(char c, int mods) {
        if (c == 0) return;
        CefKeyEvent e = new CefKeyEvent(GLFW.GLFW_REPEAT, 0, c, mods); // java-cef's native side reads REPEAT as "char"
        sendKeyEvent(e);
    }

    public void focus(boolean on) {
        setFocus(on);
    }

    private void sendKey(int action, int key, int scancode, int mods) {
        char c = key >= 32 && key < 127 ? (char) key : (char) 0;
        CefKeyEvent e = new CefKeyEvent(action, key, c, mods);
        e.scancode = scancode;
        sendKeyEvent(e);
    }

    private boolean shortcut(int key, int mods) {
        boolean ctrl = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        boolean alt = (mods & GLFW.GLFW_MOD_ALT) != 0;
        if (ctrl && key == GLFW.GLFW_KEY_R) { reload(); return true; }
        if (alt && key == GLFW.GLFW_KEY_LEFT) { back(); return true; }
        if (alt && key == GLFW.GLFW_KEY_RIGHT) { forward(); return true; }
        if (ctrl && (key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD)) { setZoomLevel(Math.min(getZoomLevel() + 0.5, 5)); return true; }
        if (ctrl && (key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT)) { setZoomLevel(Math.max(getZoomLevel() - 0.5, -5)); return true; }
        if (ctrl && key == GLFW.GLFW_KEY_0) { setZoomLevel(0); return true; }
        return false;
    }

    /** java-cef's native side takes GLFW 0/1/2 as left/middle/right, so right and middle swap here. */
    static int nativeButton(int glfw) {
        return switch (glfw) {
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> GLFW.GLFW_MOUSE_BUTTON_RIGHT;
            default -> glfw;
        };
    }

    private static int heldMask(int glfw) {
        return switch (glfw) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> LEFT_HELD;
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> RIGHT_HELD;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> MIDDLE_HELD;
            default -> 0;
        };
    }

    // ---- page state ------------------------------------------------------------------------------

    public String url() { return getURL(); }
    public String title() { return title; }
    public boolean loading() { return loading; }
    public void load(String url) { loadURL(url); }
    /** History, as of the last load (cheaper than asking Chromium every frame). */
    public boolean hasBack() { return canBack; }
    public boolean hasForward() { return canForward; }
    public void back() { if (canBack) goBack(); }
    public void forward() { if (canForward) goForward(); }

    // ---- cursor ----------------------------------------------------------------------------------

    private static final Map<Integer, Long> CURSORS = new HashMap<>();

    @Override
    public boolean onCursorChange(CefBrowser browser, int type) {
        long window = config.window.getAsLong();
        if (window == 0 || type == cursor) return true;
        cursor = type;
        CefCursorType t = CefCursorType.fromId(type);
        if (t == CefCursorType.NONE) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
        } else {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            GLFW.glfwSetCursor(window, CURSORS.computeIfAbsent(t.glfwId, GLFW::glfwCreateStandardCursor));
        }
        return true;
    }

    /** Back to the arrow (call when the page loses the pointer, e.g. your screen closes). */
    public void resetCursor() {
        long window = config.window.getAsLong();
        cursor = -1;
        if (window != 0) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            GLFW.glfwSetCursor(window, 0);
        }
    }

    /** Closes the page for good. */
    public void close() {
        resetCursor();
        Pane.forget(this);
        pixels = null;
        popup = null;
        super.close(true);
    }
}
