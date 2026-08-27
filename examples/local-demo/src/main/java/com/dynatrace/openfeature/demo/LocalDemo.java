package com.dynatrace.openfeature.demo;

import com.dynatrace.openfeature.DynatraceProvider;
import com.dynatrace.openfeature.DynatraceProviderOptions;
import com.sun.net.httpserver.HttpServer;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

/**
 * Local smoke-test for the Dynatrace OpenFeature Java provider.
 *
 * Mirrors the web provider's react-app example: evaluates the same flags
 * (simple-boolean, simple-string, simple-number, simple-object,
 * code-default-flag, code-default-flag-omitted, user-tier-flag,
 * email-based-feature, region-config, region-config-api-origin)
 * under both the targeted and non-targeted evaluation context.
 *
 * Run via:
 *   cd examples/local-demo
 *   cp .env.example .env   # edit DT_SDK_KEY if using a real key
 *   mvn compile exec:java
 *
 * (build + install the provider first: mvn install -DskipTests -Dgpg.skip=true from repo root)
 */
public final class LocalDemo {

    // Mirrors evaluationContexts.ts in the web react-app example
    static final ImmutableContext TARGETED_CTX = new ImmutableContext(
            "targeted-user",
            Map.of(
                    "email",  new Value("test@dynatrace.com"),
                    "plan",   new Value("enterprise"),
                    "region", new Value("eu")
            ));

    static final ImmutableContext NOT_TARGETED_CTX = new ImmutableContext(
            "not-targeted-user",
            Map.of(
                    "email",  new Value("not-targeted@example.com"),
                    "plan",   new Value("free"),
                    "region", new Value("unknown")
            ));

    public static void main(String[] args) throws Exception {
        String sdkKey = loadSdkKey();
        System.out.println("Using SDK key: " + redactKey(sdkKey) + "\n");
        
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        try {

            DynatraceProviderOptions options = DynatraceProviderOptions.builder()
                    .configOrigin("https://cdn.eu.fm.dynatracelabs.com")
                    .metricsOrigin("https://metrics.eu.fm.dynatrace.com")
                    .metricsFlushInterval(Duration.ofSeconds(1)) // suppress periodic flushes
                    .build();

            System.out.println("Initializing provider...");
            api.setProviderAndWait(new DynatraceProvider(sdkKey, options));
            System.out.println("Provider ready.\n");

            Client client = api.getClient();

            printSection("TARGETED CONTEXT", TARGETED_CTX, client);
            System.out.println();
            printSection("NOT-TARGETED CONTEXT", NOT_TARGETED_CTX, client);

        } finally {
            System.out.println("Shutting down provider...");
            Thread.sleep(Duration.ofSeconds(5).toMillis());
            api.shutdown();
            System.out.println("Provider shut down");
        }
    }

    // -------------------------------------------------------------------------
    // SDK key loading
    // -------------------------------------------------------------------------

    /**
     * Resolution order:
     *   1. DT_SDK_KEY environment variable
     *   2. DT_SDK_KEY entry in .env (read from the working directory)
     *   3. The default test key from .env.example (works with the local server)
     */
    static String loadSdkKey() throws IOException {
        String fromEnvVar = System.getenv("DT_SDK_KEY");
        if (fromEnvVar != null && !fromEnvVar.isBlank()) {
            return fromEnvVar.strip();
        }
        Path dotEnv = Paths.get(System.getProperty("user.dir", "."), ".env");
        if (Files.exists(dotEnv)) {
            for (String line : Files.readAllLines(dotEnv)) {
                line = line.strip();
                if (line.startsWith("#") || !line.contains("=")) continue;
                int eq = line.indexOf('=');
                String key   = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if ("DT_SDK_KEY".equals(key) && !value.isBlank()) {
                    return value;
                }
            }
        }
        throw new IllegalStateException("DT_SDK_KEY not found in .env");
    }

    /** Redacts everything after the last dot so the secret portion is not logged. */
    private static String redactKey(String key) {
        int lastDot = key.lastIndexOf('.');
        return lastDot > 0 ? key.substring(0, lastDot) + ".<redacted>" : "<redacted>";
    }

    // -------------------------------------------------------------------------
    // Flag evaluation
    // -------------------------------------------------------------------------

    private static void printSection(String label, ImmutableContext ctx, Client client) {
        System.out.printf("=== %s ===%n", label);
        System.out.printf("  targetingKey=%-20s email=%-30s plan=%-12s region=%s%n",
                ctx.getTargetingKey(),
                strAttr(ctx, "email"),
                strAttr(ctx, "plan"),
                strAttr(ctx, "region"));
        System.out.println();

        printHeader();

        // Static flags
        printBoolRow(client.getBooleanDetails("simple-boolean",          false,     ctx));
        printStrRow( client.getStringDetails( "simple-string",           "",        ctx));
        printIntRow( client.getIntegerDetails("simple-number",           0,         ctx));
        printObjRow( client.getObjectDetails( "simple-object",           new Value(), ctx));

        System.out.println();

        // Code-default flags (both exist in flag-config.json)
        printBoolRow(client.getBooleanDetails("code-default-flag",         false, ctx));
        printBoolRow(client.getBooleanDetails("code-default-flag-omitted", false, ctx));

        System.out.println();

        // Context-driven flags
        printStrRow( client.getStringDetails( "user-tier-flag",           "unknown", ctx));
        printBoolRow(client.getBooleanDetails("email-based-feature",      false,     ctx));
        printObjRow( client.getObjectDetails( "region-config",            new Value(), ctx));
        // not present in flag-config.json — returns FLAG_NOT_FOUND / code default
        printStrRow( client.getStringDetails( "region-config-api-origin", "",        ctx));
    }

    private static void printHeader() {
        System.out.printf("%-32s %-8s %-42s %-20s %s%n",
                "Flag", "Type", "Value", "Variant", "Reason");
        System.out.println("-".repeat(110));
    }

    private static void printBoolRow(FlagEvaluationDetails<Boolean> d) {
        printRow(d.getFlagKey(), "boolean", String.valueOf(d.getValue()), d.getVariant(), d.getReason(), errName(d));
    }

    private static void printStrRow(FlagEvaluationDetails<String> d) {
        printRow(d.getFlagKey(), "string", d.getValue(), d.getVariant(), d.getReason(), errName(d));
    }

    private static void printIntRow(FlagEvaluationDetails<Integer> d) {
        printRow(d.getFlagKey(), "integer", String.valueOf(d.getValue()), d.getVariant(), d.getReason(), errName(d));
    }

    private static void printObjRow(FlagEvaluationDetails<Value> d) {
        printRow(d.getFlagKey(), "object", valueToString(d.getValue()), d.getVariant(), d.getReason(), errName(d));
    }

    /** Recursively converts a Value to a human-readable string. */
    private static String valueToString(Value v) {
        if (v == null) return "null";
        Boolean bool = v.asBoolean();
        if (bool != null) return String.valueOf(bool);
        String str = v.asString();
        if (str != null) return "\"" + str + "\"";
        Integer i = v.asInteger();
        if (i != null) return String.valueOf(i);
        Double d = v.asDouble();
        if (d != null) return String.valueOf(d);
        dev.openfeature.sdk.Structure struct = v.asStructure();
        if (struct != null) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Value> entry : struct.asMap().entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getKey()).append(": ").append(valueToString(entry.getValue()));
                first = false;
            }
            return sb.append("}").toString();
        }
        return v.toString();
    }

    private static void printRow(String key, String type, String value, String variant, String reason, String errorCode) {
        String truncated = value != null && value.length() > 40 ? value.substring(0, 37) + "..." : value;
        String variantOrDash = variant != null ? variant : "–";
        String reasonStr     = reason  != null ? reason  : "–";
        if (errorCode != null) {
            reasonStr = reasonStr + " [" + errorCode + "]";
        }
        System.out.printf("%-32s %-8s %-42s %-20s %s%n", key, type, truncated, variantOrDash, reasonStr);
    }

    private static String strAttr(ImmutableContext ctx, String key) {
        Value v = ctx.getValue(key);
        return v != null ? v.asString() : "";
    }

    private static String errName(FlagEvaluationDetails<?> d) {
        return d.getErrorCode() != null ? d.getErrorCode().name() : null;
    }

    // -------------------------------------------------------------------------
    // Local HTTP server
    // -------------------------------------------------------------------------

    private static String loadFlagConfig() throws IOException {
        // exec:java sets user.dir to workingDirectory (${project.basedir}); use it explicitly
        // because Paths.get("relative") resolves against the OS cwd, which Java cannot change.
        Path p = Paths.get(System.getProperty("user.dir", "."), "flag-config.json");
        if (Files.exists(p)) {
            return Files.readString(p);
        }
        try (var is = LocalDemo.class.getClassLoader().getResourceAsStream("flag-config.json")) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("flag-config.json not found next to pom.xml");
    }
}
