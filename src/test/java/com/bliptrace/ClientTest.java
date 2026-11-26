package com.bliptrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientTest {

    /** A fake transport that records envelopes instead of hitting the network. */
    static final class FakeTransport implements Transport {
        final List<Map<String, Object>> sent = new ArrayList<>();

        @Override
        public void send(String endpoint, String key, Map<String, Object> envelope, long timeoutMillis) {
            sent.add(envelope);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    void buildsAndShipsAValidEnvelope() {
        FakeTransport t = new FakeTransport();
        Config config = Config.builder()
                .key("k")
                .endpoint("http://api")
                .basePath("com.bliptrace")
                .build();

        new Client(config, t).capture(new RuntimeException("boom"));

        assertEquals(1, t.sent.size());
        Map<String, Object> env = t.sent.get(0);

        assertEquals("1.0", env.get("schema_version"));
        assertTrue(((String) env.get("event_id")).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"),
                "event_id should be a UUID");
        assertNotNull(env.get("occurred_at"));

        Map<String, Object> runtime = map(env.get("runtime"));
        assertEquals("java", runtime.get("name"));
        assertNotNull(runtime.get("version"));

        Map<String, Object> sdk = map(env.get("sdk"));
        assertEquals("bliptrace-java", sdk.get("name"));
        assertEquals("1.0.0", sdk.get("version"));

        assertEquals("error", env.get("level"));

        Map<String, Object> exception = map(env.get("exception"));
        assertEquals("java.lang.RuntimeException", exception.get("type"));
        assertEquals("boom", exception.get("message"));

        List<?> frames = (List<?>) exception.get("frames");
        assertFalse(frames.isEmpty(), "frames should be present");
        Map<String, Object> frame = map(frames.get(0));
        assertTrue(frame.containsKey("file"));
        assertTrue(frame.containsKey("line"));
        assertTrue(frame.containsKey("function"));
        assertTrue(frame.containsKey("in_app"));
        assertInstanceOf(Boolean.class, frame.get("in_app"));
        // The throwing frame is this test class, under the configured basePath.
        assertEquals(true, frame.get("in_app"));
    }

    @Test
    void marksAnErrorAsFatal() {
        FakeTransport t = new FakeTransport();
        new Client(Config.builder().key("k").build(), t)
                .capture(new StackOverflowError("deep"));
        assertEquals("fatal", t.sent.get(0).get("level"));
    }

    @Test
    void neverPutsProjectInPayload() {
        FakeTransport t = new FakeTransport();
        new Client(Config.builder().key("k").build(), t).capture(new RuntimeException("x"));
        assertFalse(t.sent.get(0).containsKey("project"));
    }

    @Test
    void ignoresConfiguredExceptionTypes() {
        FakeTransport t = new FakeTransport();
        Config config = Config.builder()
                .key("k")
                .ignoreExceptions(List.of("java.lang.IllegalStateException"))
                .build();
        new Client(config, t).capture(new IllegalStateException("noise"));
        assertTrue(t.sent.isEmpty());
    }

    @Test
    void doesNothingWithoutAKeyOrWhenDisabled() {
        FakeTransport t = new FakeTransport();
        new Client(Config.builder().key(null).build(), t).capture(new Exception("x"));
        new Client(Config.builder().key("k").enabled(false).build(), t).capture(new Exception("x"));
        assertTrue(t.sent.isEmpty());
    }

    @Test
    void scrubsSensitiveContextBeforeSending() {
        FakeTransport t = new FakeTransport();
        Config config = Config.builder().key("k").scrubKeys(List.of("password")).build();

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("password", "hunter2");
        user.put("id", "7");
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("user", user);

        new Client(config, t).capture(new Exception("x"), context);

        Map<String, Object> ctx = map(t.sent.get(0).get("context"));
        Map<String, Object> outUser = map(ctx.get("user"));
        assertEquals("[REDACTED]", outUser.get("password"));
        assertEquals("7", outUser.get("id"));
    }

    @Test
    void scrubsCaseInsensitivelyAndInsideLists() {
        FakeTransport t = new FakeTransport();
        Config config = Config.builder().key("k").scrubKeys(List.of("token")).build();

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("TOKEN", "abc");
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("items", List.of(nested));

        new Client(config, t).capture(new Exception("x"), context);

        Map<String, Object> ctx = map(t.sent.get(0).get("context"));
        List<?> items = (List<?>) ctx.get("items");
        assertEquals("[REDACTED]", map(items.get(0)).get("TOKEN"));
    }

    @Test
    void neverThrowsEvenIfContextIsWeird() {
        FakeTransport t = new FakeTransport();
        Client client = new Client(Config.builder().key("k").build(), t);

        Map<String, Object> weird = new LinkedHashMap<>();
        weird.put("callback", (Runnable) () -> {});
        weird.put("nullVal", null);

        assertDoesNotThrow(() -> client.capture(new Exception("x"), weird));
        assertEquals(1, t.sent.size());
    }

    @Test
    void serialisesToValidJson() {
        Map<String, Object> env = Envelope.build(
                new RuntimeException("boom \"quoted\"\n"),
                Config.builder().key("k").build(),
                new LinkedHashMap<>());
        String json = Json.encode(env);
        assertTrue(json.startsWith("{") && json.endsWith("}"));
        assertTrue(json.contains("\"schema_version\":\"1.0\""));
        assertTrue(json.contains("\\\"quoted\\\""), "quotes should be escaped");
    }
}
