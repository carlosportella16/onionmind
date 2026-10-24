package com.onionmind.acceptance;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * Executable acceptance spec for Fases 1-4 (change integration-tests-cucumber-smoke).
 * Runs inside the normal {@code ./gradlew test}, so it counts toward CI and coverage.
 * Smoke features live under {@code features/smoke} with their own suite and glue.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/fase1")
@SelectClasspathResource("features/fase2")
@SelectClasspathResource("features/fase3")
@SelectClasspathResource("features/fase4")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.onionmind.acceptance")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "summary")
class AcceptanceSuite {
}
