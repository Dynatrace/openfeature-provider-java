package com.dynatrace.openfeature.e2e;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * <p>The shared, cross-provider Gherkin spec lives in the {@code fm-provider-test-harness} git
 * submodule at the repo root (under its {@code gherkin/} directory); the Maven build copies it onto
 * the test classpath as {@code gherkin/} (see the test resource in {@code pom.xml}), so Cucumber
 * discovers it via the {@code gherkin} package selector — independent of the working directory,
 * which behaves the same in IDEs and Maven.
 *
 * <p>Excluded from the default unit-test run; execute with {@code mvn test -Pe2e}. Requires a
 * running Docker daemon (Testcontainers launches the mock-server image).
 */
@Suite
@IncludeEngines("cucumber")
@SelectPackages("gherkin")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.dynatrace.openfeature.e2e")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, summary")
// Exclude scenarios by Gherkin tag with a Cucumber tag expression. Overridable on the command line
// via -Dcucumber.filter.tags="..." (e.g. "not (@polling or @rate-limit)").
// @ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "not @polling")
public class RunCdnE2ETest {
}
