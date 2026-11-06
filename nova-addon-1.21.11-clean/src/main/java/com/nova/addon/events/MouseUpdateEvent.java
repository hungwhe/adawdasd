package com.nova.addon.events;

public class MouseUpdateEvent {
    private static final MouseUpdateEvent INSTANCE = new MouseUpdateEvent();

    public double cursorDeltaX;
    public double cursorDeltaY;
    private double defaultCursorDeltaX;
    private double defaultCursorDeltaY;

    public static MouseUpdateEvent get(double cursorDeltaX, double cursorDeltaY) {
        INSTANCE.cursorDeltaX = cursorDeltaX;
        INSTANCE.cursorDeltaY = cursorDeltaY;
        INSTANCE.defaultCursorDeltaX = cursorDeltaX;
        INSTANCE.defaultCursorDeltaY = cursorDeltaY;
        return INSTANCE;
    }

    public double getDefaultDeltaX() {
        return this.defaultCursorDeltaX;
    }

    public double getDefaultDeltaY() {
        return this.defaultCursorDeltaY;
    }
}
