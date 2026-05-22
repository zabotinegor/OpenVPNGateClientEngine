package de.blinkt.openvpn.core

import android.app.PendingIntent
import android.content.Intent
import de.blinkt.openvpn.activities.DisconnectVPN
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OpenVPNServiceNotificationSyncTest {

    class TestOpenVPNService : OpenVPNService() {
        var bringTaskToFront = false

        override fun bringExistingAppTaskToFront(): Boolean {
            return bringTaskToFront
        }
    }

    @Before
    fun setUp() {
        GlobalPreferences.setInstance(false, false, false)
    }

    @After
    fun tearDown() {
        GlobalPreferences.setInstance(false, false, false)
    }

    @Test
    fun graphPendingIntentTargetsLauncherActivity() {
        val service = Robolectric.buildService(OpenVPNService::class.java).create().get()

        val pendingIntent = ReflectionHelpers.callInstanceMethod<PendingIntent>(service, "getGraphPendingIntent")
        val savedIntent = Shadows.shadowOf(pendingIntent).savedIntent

        assertNotNull(savedIntent)
        assertEquals(Intent.ACTION_MAIN, savedIntent.action)
        assertEquals(service.packageName, savedIntent.`package`)
        assertTrue(savedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(savedIntent.component?.className == OpenVPNService::class.java.name)
    }

    @Test
    fun onStartCommandOpenAppStartsLauncherWhenNoTaskExists() {
        val service = Robolectric.buildService(OpenVPNService::class.java).create().get()

        service.onStartCommand(Intent(service, OpenVPNService::class.java).apply {
            action = "de.blinkt.openvpn.OPEN_VPN_APP"
        }, 0, 1)

        val startedActivity = Shadows.shadowOf(service).nextStartedActivity
        assertNotNull(startedActivity)
        assertEquals(Intent.ACTION_MAIN, startedActivity.action)
        assertEquals(service.packageName, startedActivity.`package`)
        assertTrue(startedActivity.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun onStartCommandOpenAppFallsBackToLauncherWhenMoveToFrontFails() {
        val service = Robolectric.buildService(TestOpenVPNService::class.java).create().get()
        service.bringTaskToFront = false

        service.onStartCommand(Intent(service, OpenVPNService::class.java).apply {
            action = "de.blinkt.openvpn.OPEN_VPN_APP"
        }, 0, 1)

        val startedActivity = Shadows.shadowOf(service).nextStartedActivity
        assertNotNull(startedActivity)
        assertEquals(Intent.ACTION_MAIN, startedActivity.action)
        assertEquals(service.packageName, startedActivity.`package`)
    }

    @Test
    fun onStartCommandDisconnectDoesNotLaunchActivity() {
        val service = Robolectric.buildService(OpenVPNService::class.java).create().get()

        service.onStartCommand(Intent(service, OpenVPNService::class.java).apply {
            action = OpenVPNService.DISCONNECT_VPN
        }, 0, 1)

        assertNull(Shadows.shadowOf(service).nextStartedActivity)
    }

    @Test
    fun disconnectPendingIntentUsesDisconnectActivityWhenForceConnected() {
        GlobalPreferences.setInstance(false, true, false)
        val service = Robolectric.buildService(OpenVPNService::class.java).create().get()

        val pendingIntent = ReflectionHelpers.callInstanceMethod<PendingIntent>(service, "getDisconnectPendingIntent")
        val savedIntent = Shadows.shadowOf(pendingIntent).savedIntent

        assertNotNull(savedIntent)
        assertEquals(DisconnectVPN::class.java.name, savedIntent.component?.className)
        assertNull(savedIntent.action)
    }
}
