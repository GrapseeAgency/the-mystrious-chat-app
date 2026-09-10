package app.pulse.android

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wave-0 launch smoke — MainActivity reaches RESUMED and hands back a live
 * activity instance. Dependency-light on purpose (androidx.test:core-ktx +
 * ext-junit); the Hilt graph (repository, DB, socket, vault) must come up
 * cleanly or launch itself fails.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {

    @Test
    fun mainActivityLaunchesAndResumes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertNotNull(activity) }
        }
    }
}
