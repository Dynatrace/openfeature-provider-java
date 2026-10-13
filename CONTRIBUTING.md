# Contributing

## Prerequisites

- Java 11+ (build target), Java 17+ (CI), Java 21 available locally for `jshell`
- Maven 3.8+

## Building

```bash
mvn compile -P '!deploy'
```

## Testing

```bash
mvn test -P '!deploy'
```

Tests use OkHttp `MockWebServer` for HTTP interactions; no network access is required.

### End-to-end tests

Black-box e2e tests drive the real `DynatraceProvider` through the OpenFeature SDK against a
**containerized mock server**, using the shared, cross-provider Gherkin spec.

- **Spec:** the `gherkin/` feature files come from the [`fm-provider-test-harness`](fm-provider-test-harness)
  git submodule (the same spec used by the Java, Go and Python providers). See the submodule for the
  spec and the mock-server endpoint contract.
- **Containerized:** one [Testcontainers](https://testcontainers.com/)-managed mock server emulates
  every component the provider talks to; each scenario resets it for isolation. Requires Docker.
- **Implementation:** [`src/test/java/com/dynatrace/openfeature/e2e`](src/test/java/com/dynatrace/openfeature/e2e)
  — the JUnit Platform Suite runner, Cucumber step definitions, and the mock-server client.

Excluded from the default unit build (`mvn test`). Run with the `e2e` profile, which syncs the
submodule to its pinned commit first:

```bash
mvn test -Pe2e
```

Override the mock-server image via the `mockserver.image` system property (or `MOCKSERVER_IMAGE`
env var); skip the submodule sync while iterating on the spec locally with `-Dtestharness.skipUpdate=true`:

```bash
mvn test -Pe2e -Dmockserver.image=ghcr.io/dynatrace/fm-provider-mock-server:1.4.0
mvn test -Pe2e -Dtestharness.skipUpdate=true
```

## Code style

- Follow existing conventions in the codebase.
- No Lombok; plain Java only.
- Minimize non-test dependencies.
- Keep public API surface minimal. New public methods need a clear reason to exist.
- Log at `DEBUG` for routine operations, `WARN` for recoverable errors, `INFO` for lifecycle events.
- All provider behavior must align with [providers.md](https://github.com/Dynatrace/feature-management-knowledge-base) (sections 1.1, 2.1, 2.3, 2.4, 3.1, 4.1, 6).

## Interactive evaluation with jshell

Use `jshell` to load the provider and its dependencies and run live evaluations against a real CDN endpoint. Useful for exploratory testing and debugging.

### 1. Build the provider JAR

```bash
mvn package -DskipTests -P '!deploy'
```

### 2. Assemble the classpath

```bash
mvn dependency:build-classpath -q -DincludeScope=runtime \
  -Dmdep.outputFile=/tmp/dt-provider-cp.txt -P '!deploy'
```

### 3. Start jshell

```bash
PROVIDER_JAR=$(ls target/dynatrace-openfeature-provider-java-*.jar | grep -v sources)
SLF4J_SIMPLE=$(find ~/.m2 -name 'slf4j-simple-*.jar' | head -1)
DEPS=$(cat /tmp/dt-provider-cp.txt)

jshell --class-path "$PROVIDER_JAR:$DEPS:$SLF4J_SIMPLE"
```

### 4. Initialize the provider (paste into the jshell prompt)

> **Note:** each statement must be on a single line; jshell does not support multiline
> expressions at the interactive prompt.

```java
import com.dynatrace.openfeature.*;
import dev.openfeature.sdk.*;

var options = DynatraceProviderOptions.builder("dt01.server_us_abcdef1234.de848e97a9cc4cc78aae568e65f49a9d_a1b2c3d4e5").cdnBaseUrl("https://cdn.us.fm.dynatracelabs.com/server/").build();
var provider = new DynatraceProvider(options);
OpenFeatureAPI.getInstance().setProviderAndWait(provider);
var client = OpenFeatureAPI.getInstance().getClient();
provider.getState()
```

Expected output: `$n ==> READY`

### 5. Evaluate flags

```java
client.getBooleanValue("your-flag-key", false)
client.getStringValue("your-string-flag", "default")
client.getIntegerValue("your-int-flag", 0)
client.getDoubleValue("your-double-flag", 0.0)
```

Use `getDetails()` variants for full resolution metadata (reason, variant, error code):

```java
client.getBooleanDetails("your-flag-key", false)
```

### 6. Shut down cleanly

```java
OpenFeatureAPI.getInstance().shutdown()
```

## Release

Releases are managed via [release-please](https://github.com/googleapis/release-please). Merge the generated release PR to publish to Maven Central.
