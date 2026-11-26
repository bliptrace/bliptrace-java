package com.bliptrace;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the language-neutral Bliptrace error envelope from a {@link Throwable}.
 * The result is a structured {@code Map<String,Object>} so callers and tests
 * can introspect it without parsing JSON.
 */
public final class Envelope {

    /** RFC 3339 UTC with millisecond precision, e.g. 2026-06-25T09:14:32.521Z. */
    private static final DateTimeFormatter OCCURRED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Envelope() {
    }

    /**
     * @param context already-scrubbed context (request/user/custom)
     * @return the envelope as a structured map
     */
    public static Map<String, Object> build(Throwable t, Config config, Map<String, Object> context) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema_version", "1.0");
        envelope.put("event_id", UUID.randomUUID().toString());
        envelope.put("occurred_at", OCCURRED_AT.format(Instant.now()));

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("name", "java");
        runtime.put("version", System.getProperty("java.version"));
        envelope.put("runtime", runtime);

        Map<String, Object> sdk = new LinkedHashMap<>();
        sdk.put("name", config.sdkName());
        sdk.put("version", config.sdkVersion());
        envelope.put("sdk", sdk);

        envelope.put("environment", config.environment());
        envelope.put("release", config.release());
        envelope.put("level", level(t));

        Map<String, Object> exception = new LinkedHashMap<>();
        exception.put("type", t.getClass().getName());
        exception.put("message", limit(t.getMessage(), config.maxMessage()));
        exception.put("frames", frames(t, config));
        envelope.put("exception", exception);

        envelope.put("context", normaliseContext(context));

        return envelope;
    }

    private static String level(Throwable t) {
        return (t instanceof Error) ? "fatal" : "error";
    }

    private static List<Map<String, Object>> frames(Throwable t, Config config) {
        List<Map<String, Object>> frames = new ArrayList<>();
        StackTraceElement[] trace = t.getStackTrace();
        int max = config.maxFrames();
        for (StackTraceElement el : trace) {
            if (frames.size() >= max) {
                break;
            }
            Map<String, Object> frame = new LinkedHashMap<>();
            String file = el.getFileName() != null ? el.getFileName() : el.getClassName();
            frame.put("file", file);
            frame.put("line", el.getLineNumber() >= 0 ? el.getLineNumber() : 0);
            frame.put("function", el.getClassName() + "." + el.getMethodName());
            frame.put("in_app", inApp(el.getClassName(), config.basePath()));
            frames.add(frame);
        }
        return frames;
    }

    private static boolean inApp(String className, String basePath) {
        if (className == null) {
            return false;
        }
        if (basePath != null && !basePath.isEmpty()) {
            return className.startsWith(basePath);
        }
        return !className.startsWith("java.")
                && !className.startsWith("javax.")
                && !className.startsWith("jdk.")
                && !className.startsWith("sun.");
    }

    /**
     * Ensure {@code context.custom} is always an object, mirroring the PHP/JS SDKs.
     */
    private static Map<String, Object> normaliseContext(Map<String, Object> context) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (context != null) {
            out.putAll(context);
        }
        Object custom = out.get("custom");
        if (!(custom instanceof Map<?, ?>) || ((Map<?, ?>) custom).isEmpty()) {
            out.put("custom", new LinkedHashMap<String, Object>());
        }
        return out;
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
