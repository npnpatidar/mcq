package com.mcqapp

/**
 * The default unit-test application: production wiring, minus the startup
 * install-freshness probe.
 *
 * `app/src/test/resources/robolectric.properties` makes this the application
 * for every Robolectric class, so no class has to opt out one by one. The
 * probe opens the real database and writes `install_first_start_new` into the
 * process-wide DataStore, which would race a test's own fixture.
 *
 * Tests that need a freshness answer construct
 * [com.mcqapp.data.repository.InstallFreshness] themselves with a fixed answer
 * or a database-backed probe. `StartupTest` is the class that opts back into
 * the production startup path, through its own `McqApplication` subclasses.
 */
class TestMcqApplication : McqApplication() {
    override val seedsInstallFreshnessAtStartup: Boolean = false
}
