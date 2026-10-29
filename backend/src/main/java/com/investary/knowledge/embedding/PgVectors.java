package com.investary.knowledge.embedding;

/**
 * Text form of pgvector values ({@code [0.1,0.2,...]}), used with {@code cast(:param as vector)} so no
 * driver-specific type is needed.
 */
public final class PgVectors {

    private PgVectors() {
    }

    public static String format(float[] vector) {
        StringBuilder text = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            float value = vector[i];
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding contains a non-finite value");
            }
            text.append(value);
        }
        return text.append(']').toString();
    }

    public static float[] parse(String text) {
        String body = text.strip();
        body = body.substring(1, body.length() - 1);
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].strip());
        }
        return vector;
    }
}
