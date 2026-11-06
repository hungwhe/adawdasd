package com.nova.addon.utils.rotation;

import java.util.concurrent.ThreadLocalRandom;

public final class AngleUtils {
    private AngleUtils() {
    }

    public static double randomDouble(double min, double max) {
        return ThreadLocalRandom.current().nextDouble() * (max - min) + min;
    }

    public static float randomFloat(float min, float max) {
        return (float) (Math.random() * (double) (max - min) + (double) min);
    }
}
