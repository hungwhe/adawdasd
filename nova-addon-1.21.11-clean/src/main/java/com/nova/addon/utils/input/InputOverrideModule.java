package com.nova.addon.utils.input;

public interface InputOverrideModule {
    default boolean shouldUnsprint() {
        return false;
    }

    default boolean shouldStopForward() {
        return false;
    }
}
