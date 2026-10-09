# pane

Chromium inside your Java app, off-screen. Web pages come out as pixels you draw yourself, and you feed them GLFW input. It was made for the in-game browser of [jukz](https://github.com/Nuulz/jukz), a Minecraft mod, but nothing here depends on Minecraft.

```java
Pane.configure(PaneConfig.at(dir.resolve("pane")).userAgentProduct("mygame"));

// Once, on a worker thread: downloads Chromium for this platform (~120 MB) and checks it.
Pane.install((step, fraction) -> showProgress(step, fraction));

// Then, on your render thread:
Pane.start();
PaneView page = Pane.open("https://example.com");
page.resize(width, height);              // physical pixels

// Every frame:
Pane.pump();
if (page.version() != lastVersion) upload(page.pixels(), page.width(), page.height()); // BGRA
// Mouse and keys, with GLFW codes:
page.mouseMove(x, y);  page.mouseDown(x, y, button, mods);  page.typed(c, mods);  page.wheel(x, y, notches, mods);
```

What you get:

- **The page as a BGRA buffer** (`pixels()`), plus an open dropdown on top of it (`popupPixels()` at `popupBounds()`). Re-upload when `version()` changes.
- **Input in GLFW codes**: double and triple clicks, drags, the wheel (shift scrolls sideways), typed characters, and the usual shortcuts (Ctrl+R, Alt+←/→, Ctrl+plus/minus/0).
- **The mouse cursor**: a hand over links and a text bar over fields, if you pass your GLFW window to `PaneConfig.glfwWindow`.
- **Popups stay in the page**: a link that would open a new window opens in the same view, so no native windows ever appear.
- **The same-origin policy stays on**: pages can take payments safely.
- **An installer**: it downloads Chromium from our copy at `chromium.nuulm.com`, verifies its SHA-256 and unpacks it to `home/<platform>/`, then skips all of that on later runs. You can point it at another copy with the same layout using `PaneConfig.mirror`, for example `PaneConfig.CINEMAMOD_MIRROR`.

## Getting it

With [JitPack](https://jitpack.io/#Nuulz/pane):

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { implementation("com.github.Nuulz:pane:v0.1.1") }
```

LWJGL (`lwjgl` and `lwjgl-glfw`) has to be on the classpath, because java-cef's native side reads GLFW's codes. Minecraft and most GLFW apps already ship it. It needs Java 21+.

Platforms: Linux, Windows and macOS, each on x64 and arm64, as far as there are Chromium builds for them.

## Credits

- [java-cef](https://github.com/chromiumembedded/java-cef) (BSD), the Java binding for the [Chromium Embedded Framework](https://bitbucket.org/chromiumembedded/cef). It's included as is in `jcef/`, in the version from [CinemaMod's fork](https://github.com/CinemaMod/java-cef), which reads GLFW input.
- The Chromium builds downloaded at runtime are [CinemaMod](https://github.com/CinemaMod)'s builds of that fork, copied unchanged to `chromium.nuulm.com`.
- The idea of drawing Chromium inside Minecraft comes from [MCEF](https://github.com/CinemaMod/mcef). pane is its own code (none of MCEF is in here), so it's MIT.

## License

MIT, see `LICENSE`. `jcef/` keeps its BSD license (`jcef/LICENSE.txt`).
