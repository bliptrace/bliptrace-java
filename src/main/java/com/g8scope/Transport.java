package com.g8scope;

import java.util.Map;

/** Ships an envelope to the ingest API. Implementations must never throw. */
public interface Transport {

    void send(String endpoint, String key, Map<String, Object> envelope, long timeoutMillis);
}
