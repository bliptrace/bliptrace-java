package com.g8scope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side PII scrub: recursively redact configured keys (case-insensitive)
 * before anything is sent. Recurses into nested Maps and Lists.
 */
public final class Scrubber {

    private static final String REDACTED = "[REDACTED]";

    private final List<String> redact;

    public Scrubber(List<String> keys) {
        this.redact = new ArrayList<>();
        if (keys != null) {
            for (String k : keys) {
                if (k != null) {
                    this.redact.add(k.toLowerCase());
                }
            }
        }
    }

    /** Scrub a context map, returning a redacted copy. Never throws. */
    public Map<String, Object> scrub(Map<String, Object> data) {
        if (data == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (key != null && redact.contains(key.toLowerCase())) {
                out.put(key, REDACTED);
            } else {
                out.put(key, scrubValue(value));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object scrubValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object k = e.getKey();
                typed.put(k == null ? null : String.valueOf(k), e.getValue());
            }
            return scrub((Map<String, Object>) (Map<?, ?>) typed);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(scrubValue(item));
            }
            return out;
        }
        return value;
    }
}
