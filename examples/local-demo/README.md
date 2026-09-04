# Dynatrace OpenFeature – Java Local Demo

A standalone Maven app that wires up the **local build** of
`dynatrace-openfeature-provider-java` with the OpenFeature Java SDK, starts a
local HTTP server to serve `flag-config.json`, then prints evaluation results
for both a targeted and a non-targeted context — mirroring the web provider's
`react-app` example.

---

## Prerequisites

- JDK 11+
- Maven 3.6+

---

## 1 – Build the provider

From the **repo root**:

```bash
mvn install -DskipTests -Dgpg.skip=true
```

This installs the provider into your local Maven repository, which this demo
depends on.

> Re-run whenever you change provider source so the demo picks up the latest
> code.

---

## 2 – Configure your SDK key

```bash
cp .env.example .env
```

Edit `.env` and set `DT_SDK_KEY` to your Dynatrace server SDK key
(`dt01.server_<region>_…`).

The default value in `.env.example` is a valid-format test key that works
offline with the bundled `flag-config.json` — no real Dynatrace account needed.

SDK key resolution order:

1. `DT_SDK_KEY` environment variable
2. `DT_SDK_KEY` in `.env` (read from the working directory)
3. Built-in test key (default)

---

## 3 – Run

```bash
cd examples/local-demo
mvn compile exec:java
```

---

## What the demo demonstrates

| File               | Purpose                                                                                                                       |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `LocalDemo.java`   | Starts a local HTTP server, registers `DynatraceProvider` via `configOrigin`, evaluates all flags, and prints a results table |
| `flag-config.json` | Local flag configuration in flagd format — same flags as the web `react-app` example                                          |
| `.env.example`     | Template for `DT_SDK_KEY`                                                                                                     |

### Flags evaluated

| Flag key                    | Type    | Targeting                      |
|-----------------------------|---------|--------------------------------|
| `simple-boolean`            | boolean | context-driven                 |
| `simple-string`             | string  | context-driven                 |
| `simple-number`             | integer | context-driven                 |
| `simple-object`             | object  | context-driven                 |
| `code-default-flag`         | boolean | context-driven                 |
| `code-default-flag-omitted` | boolean | context-driven                 |
| `user-tier-flag`            | string  | context-driven                 |
| `email-based-feature`       | boolean | context-driven                 |
| `region-config`             | object  | context-driven                 |
| `region-config-api-origin`  | string  | not in config → FLAG_NOT_FOUND |

Targeting matches when `email=test@dynatrace.com`, `plan=enterprise`, and
`region=eu` — the **targeted context**. Any other values hit the default
variant — the **non-targeted context**.
