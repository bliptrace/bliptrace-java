package com.bliptrace;

import java.util.Collections;
import java.util.Map;

/**
 * The runtime Java client: decide whether to capture, build + scrub the envelope,
 * and ship it. Framework adapters call {@link #capture} directly, or split
 * {@link #buildEnvelope}/{@link #sendEnvelope} to defer the send.
 *
 * <p>The capture path NEVER throws into the host application.</p>
 */
public final class Client {

    private final Config config;
    private final Transport transport;
    private final Scrubber scrubber;

    public Client(Config config) {
        this(config, new HttpTransport(), new Scrubber(config.scrubKeys()));
    }

    public Client(Config config, Transport transport) {
        this(config, transport, new Scrubber(config.scrubKeys()));
    }

    public Client(Config config, Transport transport, Scrubber scrubber) {
        this.config = config;
        this.transport = transport != null ? transport : new HttpTransport();
        this.scrubber = scrubber != null ? scrubber : new Scrubber(config.scrubKeys());
    }

    /** Capture and ship immediately. Guard-wrapped — never throws into your app. */
    public void capture(Throwable t) {
        capture(t, Collections.emptyMap());
    }

    /** Capture and ship immediately. Guard-wrapped — never throws into your app. */
    public void capture(Throwable t, Map<String, Object> context) {
        try {
            Map<String, Object> envelope = buildEnvelope(t, context);
            if (envelope != null) {
                sendEnvelope(envelope);
            }
        } catch (Throwable ignored) {
            // never break the app
        }
    }

    /**
     * Build the envelope (scrubbing context), or {@code null} when capture is
     * disabled, unconfigured, or the throwable is ignored.
     */
    public Map<String, Object> buildEnvelope(Throwable t, Map<String, Object> context) {
        try {
            if (!config.enabled() || config.key() == null || config.key().isEmpty()) {
                return null;
            }
            if (shouldIgnore(t)) {
                return null;
            }
            return Envelope.build(t, config, scrubber.scrub(context));
        } catch (Throwable ignored) {
            return null; // never break the app
        }
    }

    public void sendEnvelope(Map<String, Object> envelope) {
        try {
            if (config.key() != null && !config.key().isEmpty()) {
                transport.send(config.endpoint(), config.key(), envelope, config.timeoutMillis());
            }
        } catch (Throwable ignored) {
            // never break the app
        }
    }

    private boolean shouldIgnore(Throwable t) {
        String type = t.getClass().getName();
        for (String ignored : config.ignoreExceptions()) {
            if (type.equals(ignored)) {
                return true;
            }
        }
        return false;
    }
}
