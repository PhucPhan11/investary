package com.investary.notebook.application;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small JSON helpers for the {@code jsonb} string-list columns. */
final class NotebookJson {

    private NotebookJson() {
    }

    static String write(JsonMapper json, Object value) {
        return json.writeValueAsString(value);
    }

    static List<String> strings(JsonMapper json, String array) {
        List<String> result = new ArrayList<>();
        if (array == null || array.isBlank()) {
            return result;
        }
        for (JsonNode node : json.readTree(array)) {
            result.add(node.asString(""));
        }
        return result;
    }

    static JsonNode tree(JsonMapper json, String value) {
        return value == null ? null : json.readTree(value);
    }
}
