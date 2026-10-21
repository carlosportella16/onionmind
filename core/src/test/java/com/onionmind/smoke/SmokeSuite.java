package com.onionmind.smoke;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.OBJECT_FACTORY_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * GET-only smoke against an already running instance — no Spring context, no containers.
 * Excluded from {@code test}; run with {@code ./gradlew smokeTest} (or scripts/smoke.sh).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/smoke")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.onionmind.smoke")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, summary")
// cucumber-spring is on the test classpath and would demand a Spring context; plain glue here.
@ConfigurationParameter(key = OBJECT_FACTORY_PROPERTY_NAME, value = "io.cucumber.core.backend.DefaultObjectFactory")
class SmokeSuite {
}
