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

var options = DynatraceProviderOptions.builder("03ee0ef7cd4244bfb8b71fea8f783f53").cdnBaseUrl("https://cdn.us.fm.dynatracelabs.com/server/").build();
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
