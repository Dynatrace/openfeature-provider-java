# Dynatrace OpenFeature Provider for Java

The official [Dynatrace](https://dynatrace.com) [OpenFeature](https://openfeature.dev) provider for Java server-side applications.

The provider evaluates flags in-process: on initialization it fetches your flag configuration from the Dynatrace CDN and keeps it up to date in the background, so evaluations are local and do not make a network call.

> **Status:** Early alpha — not yet ready for production use.

## Installation

```xml
<dependency>
  <groupId>com.dynatrace.openfeature</groupId>
  <artifactId>dynatrace-openfeature-provider-java</artifactId>
  <version>0.5.1-alpha</version> <!-- x-release-please-version -->
</dependency>
```

```groovy
implementation 'com.dynatrace.openfeature:dynatrace-openfeature-provider-java:0.5.1-alpha' // x-release-please-version
```

## Usage

```java
import com.dynatrace.openfeature.DynatraceProvider;
import dev.openfeature.sdk.*;

OpenFeatureAPI api = OpenFeatureAPI.getInstance();
api.setProviderAndWait(new DynatraceProvider(System.getenv("DT_SDK_KEY")));

Client client = api.getClient();
EvaluationContext ctx = new ImmutableContext("user-123");
boolean enabled = client.getBooleanValue("my-flag", false, ctx);

// On shutdown:
api.shutdown();
```

Once the provider is registered, all flag evaluation goes through the OpenFeature API. See the
[OpenFeature Java SDK documentation](https://openfeature.dev/docs/reference/technologies/server/java/)
for evaluation contexts, hooks, events, and the rest of the SDK surface.

## Configuration

Build a `DynatraceProviderOptions` and pass it as the second constructor argument:

```java
DynatraceProviderOptions options = DynatraceProviderOptions.builder()
    .configOrigin("https://cdn.eu.fm.dynatracelabs.com")
    .metricsOrigin("https://metrics.eu.fm.dynatracelabs.com")
    .build();

api.setProviderAndWait(new DynatraceProvider(sdkKey, options));
```

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| `configOrigin(String)` | `String` | derived from SDK key | Override the flag configuration origin. |
| `metricsOrigin(String)` | `String` | derived from SDK key | Override the evaluation metrics ingest origin. |

A runnable example lives in [`examples/local-demo`](examples/local-demo).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Apache 2.0 — see [LICENSE](LICENSE).
