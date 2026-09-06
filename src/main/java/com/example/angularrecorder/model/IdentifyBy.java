package com.example.angularrecorder.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record IdentifyBy(
        String preferredStrategy,
        String preferredValue,
        Map<String, String> candidates
) {
    public IdentifyBy {
        candidates = candidates == null ? new LinkedHashMap<>() : new LinkedHashMap<>(candidates);
    }
}
