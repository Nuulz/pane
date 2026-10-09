package dev.nuulz.pane;

/** What {@link Pane#install} is doing, for a progress bar: a step name and 0..1, or -1 when unknown. */
@FunctionalInterface
public interface Progress {
    void update(String step, double fraction);
}
