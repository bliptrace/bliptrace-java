# bliptrace-java

The runtime **Java SDK** for [g8scope](https://scope.g8suite.com) — a central,
multi-tenant, runtime-agnostic error-capture platform. The SDK builds a
language-neutral **error envelope** from a `Throwable` and ships it to the g8scope
ingest API.

- **Zero runtime dependencies** — uses only the JDK (`java.net.http.HttpClient`)
  and a tiny built-in JSON encoder. No Jackson, no Gson.
- **Never throws into your app** — the capture path swallows every `Throwable`.
- **PII scrubbing built in** — sensitive context keys are redacted before anything
  leaves the process.

Requires **Java 17+**.

## Installation

Maven coordinates:

```xml
<dependency>
    <groupId>com.g8scope</groupId>
    <artifactId>g8scope</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Usage

```java
import com.g8scope.Client;
import com.g8scope.Config;

import java.util.List;
import java.util.Map;

Config config = Config.builder()
        .key(System.getenv("G8SCOPE_INGEST_KEY"))   // ingest key — resolves your project server-side
        .endpoint("https://ingest.scope.g8suite.com")
        .environment("production")
        .release("my-app@2026.06.21")
        .basePath("com.example.myapp")               // marks in-app stack frames
        .scrubKeys(List.of("password", "token", "authorization"))
        .build();

Client client = new Client(config);

try {
    riskyOperation();
} catch (Throwable t) {
    client.capture(t, Map.of(
            "user", Map.of("id", "u_123"),
            "request", Map.of("method", "POST", "url", "/checkout")
    ));
}
```

### Deferring the send

Adapters that want to ship the envelope *after* the HTTP response has flushed can
split the two steps:

```java
Map<String, Object> envelope = client.buildEnvelope(t, context);
if (envelope != null) {
    client.sendEnvelope(envelope);
}
```

## How project resolution works

The **`project` is never put in the payload.** It is resolved **server-side** from
your ingest key and stamped by the API. This is an anti-spoof guarantee — a client
cannot claim to be another tenant. Keep your ingest key out of source control.

## Configuration

| Option              | Default                               | Description                                            |
|---------------------|---------------------------------------|--------------------------------------------------------|
| `key`               | `null`                                | Ingest key. Capture is a no-op without it.             |
| `endpoint`          | `https://ingest.scope.g8suite.com`    | Ingest base URL. `/v1/ingest` is appended.             |
| `environment`       | `production`                          | e.g. `production`, `staging`, `local`.                 |
| `release`           | `null`                                | Release marker, e.g. `my-app@2026.06.21`.              |
| `timeoutMillis`     | `3000`                                | HTTP timeout in milliseconds.                          |
| `maxFrames`         | `30`                                  | Maximum stack frames captured.                         |
| `maxMessage`        | `2000`                                | Exception message is truncated to this length.         |
| `ignoreExceptions`  | `[]`                                  | Fully-qualified class names to skip.                   |
| `scrubKeys`         | `[]`                                  | Context keys to redact (case-insensitive, recursive).  |
| `basePath`          | `""`                                  | In-app package prefix used to mark `in_app` frames.    |
| `enabled`           | `true`                                | Master switch.                                         |
| `sdkName`           | `g8scope-java`                        | Reported SDK name.                                     |
| `sdkVersion`        | `1.0.0`                               | Reported SDK version.                                  |

## The envelope

`Envelope.build(...)` returns a `Map<String,Object>` matching the g8scope v1 wire
contract: `schema_version`, `event_id` (UUID v4), `occurred_at` (RFC 3339 UTC,
millisecond precision), `runtime` (`{name:"java", version}`), `sdk`, `environment`,
`release`, `level` (`Error` subclasses map to `fatal`, otherwise `error`),
`exception` (`{type, message, frames}`) and the scrubbed `context`.

## Testing

```bash
mvn -q -B test
```

## Licence

MIT — see [LICENSE](LICENSE).
