package de.blinkt.openvpn.core

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the management-independent teardown of the native openvpn process.
 *
 * The "native process" is a real, long-lived OS child process (not a mock), so these tests prove
 * that the process is actually gone and that the process thread then reports LEVEL_NOTCONNECTED -
 * the exact condition the app waits for after a failed management stop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OpenVPNForceStopTest {

    /** Records the process-exit callbacks without touching job-scheduler state absent in the sandbox. */
    class RecordingService : OpenVPNService() {
        val exited = CopyOnWriteArrayList<OpenVPNThread>()
        val stopped = AtomicInteger()

        override fun onOpenVPNProcessExited(thread: OpenVPNThread) {
            exited.add(thread)
            super.onOpenVPNProcessExited(thread)
        }

        override fun openvpnStopped() {
            stopped.incrementAndGet()
        }
    }

    private fun newService(): RecordingService =
        Robolectric.buildService(RecordingService::class.java).create().get()

    private val started = CopyOnWriteArrayList<Thread>()
    private val levels = CopyOnWriteArrayList<ConnectionStatus>()
    private val listener = object : VpnStatus.StateListener {
        override fun updateState(
            state: String?, logmessage: String?, localizedResId: Int,
            level: ConnectionStatus?, intent: Intent?
        ) {
            level?.let { levels.add(it) }
        }

        override fun setConnectedVPN(uuid: String?) = Unit
    }

    @Before
    fun setUp() {
        GlobalPreferences.setInstance(false, false, false)
        VpnStatus.addStateListener(listener)
    }

    @After
    fun tearDown() {
        VpnStatus.removeStateListener(listener)
        // The registry of live processes is static: leave nothing behind for the next test.
        OpenVPNThread.liveProcesses().forEach { it.requestTermination(); it.killProcess() }
        started.forEach { it.interrupt() }
        started.forEach { it.join(5_000) }
    }

    /** A child process that stays alive for a minute unless it is terminated. */
    private fun sleeperArgv(): Array<String> {
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val argv = if (windows) arrayOf("ping", "-n", "60", "127.0.0.1")
        else arrayOf("sleep", "60")
        val found = System.getenv("PATH").orEmpty().split(File.pathSeparator).any {
            File(it, argv[0]).exists() || File(it, argv[0] + ".exe").exists()
        }
        assumeTrue("needs a ${argv[0]} binary on PATH", found)
        return argv
    }

    private fun newProcess(service: OpenVPNService): Pair<OpenVPNThread, Thread> {
        val runnable = OpenVPNThread(service, sleeperArgv(), "/nonexistent", System.getProperty("java.io.tmpdir"))
        val thread = Thread(runnable, "TestOpenVPNProcessThread")
        thread.isDaemon = true
        started.add(thread)
        thread.start()
        waitUntil("process started") { runnable.isProcessAlive }
        return runnable to thread
    }

    private fun waitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for: $what")
            Thread.sleep(25)
        }
    }

    @Test
    fun requestTermination_killsLiveProcessWithoutManagementAndReportsNotConnected() {
        val service = newService()
        val (process, thread) = newProcess(service)

        assertTrue("A live process must be signalled", process.requestTermination())

        waitUntil("process thread exit") { !thread.isAlive }
        assertFalse(process.isProcessAlive)
        assertTrue(
            "The process thread must confirm teardown with LEVEL_NOTCONNECTED, got $levels",
            levels.contains(ConnectionStatus.LEVEL_NOTCONNECTED)
        )
        waitUntil("exit callbacks") { service.exited.contains(process) && service.stopped.get() == 1 }
    }

    @Test
    fun requestTermination_isIdempotentAndReportsFalseOnceTheProcessIsGone() {
        val service = newService()
        val (process, thread) = newProcess(service)

        assertTrue(process.requestTermination())
        process.requestTermination() // a repeated request must not throw
        waitUntil("process thread exit") { !thread.isAlive }

        assertFalse("Nothing left to signal", process.requestTermination())
        assertFalse("Nothing left to kill", process.killProcess())
    }

    @Test
    fun requestTermination_beforeProcessStartDestroysItAsSoonAsItStarts() {
        val service = newService()
        val runnable = OpenVPNThread(service, sleeperArgv(), "/nonexistent", System.getProperty("java.io.tmpdir"))

        assertTrue("An unstarted process still has to be terminated", runnable.requestTermination())
        val thread = Thread(runnable, "TestOpenVPNProcessThread").apply { isDaemon = true }
        started.add(thread)
        thread.start()

        waitUntil("process thread exit") { !thread.isAlive }
        assertFalse(runnable.isProcessAlive)
    }

    @Test
    fun killProcess_escalationKillsAProcessThatIgnoredTheFirstRequest() {
        val service = newService()
        val (process, thread) = newProcess(service)

        // Escalation without a preceding request models a process that survived SIGTERM.
        assertTrue(process.reserveForceKill())
        assertFalse("The escalation is reserved once per process", process.reserveForceKill())
        assertTrue(process.killProcess())

        waitUntil("process thread exit") { !thread.isAlive }
        assertFalse(process.isProcessAlive)
    }

    @Test
    fun processPid_readsTheNativePidOfAChildProcess() {
        val service = newService()
        val (process, _) = newProcess(service)

        assertTrue("A live child must expose a positive pid", process.nativePidForTest() > 0)
    }

    @Test
    fun killProcess_endsAStoppedProcessThatCannotActOnSigterm() {
        assumeTrue("needs POSIX kill", !System.getProperty("os.name").orEmpty().lowercase().contains("win"))
        val service = newService()
        val (process, thread) = newProcess(service)
        val pid = process.nativePidForTest()
        assertEquals(0, ProcessBuilder("kill", "-STOP", pid.toString()).start().waitFor())

        // SIGTERM stays pending on a stopped process, so the first request cannot end it.
        process.requestTermination()
        Thread.sleep(300)
        assertTrue("A stopped process must outlive SIGTERM", process.isProcessAlive)

        assertTrue(process.killProcess())
        waitUntil("process thread exit") { !thread.isAlive }
        assertFalse(process.isProcessAlive)
    }

    @Test
    fun forceStopVPN_withoutAnySession_reportsNoSessionAndNotConnected() {
        val service = newService()
        levels.clear()

        assertEquals(OpenVPNService.FORCE_STOP_NO_SESSION, service.forceStopVPN())
        assertTrue(
            "Stale CONNECTED state must be reconciled with LEVEL_NOTCONNECTED, got $levels",
            levels.contains(ConnectionStatus.LEVEL_NOTCONNECTED)
        )
        // Idempotent.
        assertEquals(OpenVPNService.FORCE_STOP_NO_SESSION, service.forceStopVPN())
    }

    @Test
    fun forceStopVPN_terminatesProcessWhenManagementIsMissing() {
        val service = newService()
        val (process, thread) = newProcess(service)
        ReflectionHelpers.setField(service, "mCurrentOpenVPNProcess", process)
        ReflectionHelpers.setField(service, "mProcessThread", thread)
        // mManagement stays null: the production state where stopVPN(false) returns false.
        assertFalse(service.stopVPN(false))
        levels.clear()

        assertEquals(OpenVPNService.FORCE_STOP_REQUESTED, service.forceStopVPN())

        waitUntil("process thread exit") { !thread.isAlive }
        assertFalse(process.isProcessAlive)
        assertTrue(levels.contains(ConnectionStatus.LEVEL_NOTCONNECTED))
        // A second request after exit is a harmless no-op.
        service.forceStopVPN()
    }

    @Test
    fun forceStopVPN_whileASessionIsStarting_doesNotClaimIdle() {
        val service = newService()
        ReflectionHelpers.setField(service, "mStartPendingUntilMs", Long.MAX_VALUE / 2)
        levels.clear()

        assertEquals(OpenVPNService.FORCE_STOP_UNSUPPORTED, service.forceStopVPN())
        assertFalse(
            "A starting session must not be reported as terminally stopped, got $levels",
            levels.contains(ConnectionStatus.LEVEL_NOTCONNECTED)
        )
    }

    @Test
    fun forceStopVPN_onFreshServiceInstanceStillTerminatesAnOrphanedProcess() {
        val original = newService()
        val (process, thread) = newProcess(original)
        // The original service instance is gone (recreated); the process outlived it.
        val fresh = newService()
        levels.clear()

        assertEquals(OpenVPNService.FORCE_STOP_REQUESTED, fresh.forceStopVPN())

        waitUntil("orphaned process thread exit") { !thread.isAlive }
        assertFalse(process.isProcessAlive)
        assertTrue(levels.contains(ConnectionStatus.LEVEL_NOTCONNECTED))
    }

    @Test
    fun onDestroy_terminatesProcessesOwnedByAPredecessorInstance() {
        val original = newService()
        val (process, thread) = newProcess(original)
        val fresh = newService()

        fresh.onDestroy()

        waitUntil("orphaned process thread exit") { !thread.isAlive }
        assertFalse(process.isProcessAlive)
    }

    @Test
    fun replacedProcessIsMarkedSoItsExitDoesNotReportNotConnected() {
        val service = newService()
        val (process, thread) = newProcess(service)
        ReflectionHelpers.setField(service, "mCurrentOpenVPNProcess", process)
        ReflectionHelpers.setField(service, "mProcessThread", thread)
        levels.clear()

        // Production replacement path: marks the outgoing process, then terminates it.
        ReflectionHelpers.callInstanceMethod<Unit>(
            service, "stopOldOpenVPNProcess",
            ReflectionHelpers.ClassParameter.from(OpenVPNManagement::class.java, null),
            ReflectionHelpers.ClassParameter.from(Runnable::class.java, null)
        )

        waitUntil("process thread exit") { !thread.isAlive }
        assertFalse(process.isProcessAlive)
        assertEquals("A replaced session must not publish NOTCONNECTED", 0, service.stopped.get())
    }

    @Test
    fun staleKillEscalationNeverTouchesAReplacementSession() {
        val service = newService()
        val (oldProcess, oldThread) = newProcess(service)
        ReflectionHelpers.setField(service, "mCurrentOpenVPNProcess", oldProcess)
        ReflectionHelpers.setField(service, "mProcessThread", oldThread)

        assertEquals(OpenVPNService.FORCE_STOP_REQUESTED, service.forceStopVPN())
        // A new connection replaces the session before the SIGKILL grace period elapses.
        val (newProcess, newThread) = newProcess(service)
        ReflectionHelpers.setField(service, "mCurrentOpenVPNProcess", newProcess)
        ReflectionHelpers.setField(service, "mProcessThread", newThread)

        waitUntil("old process thread exit") { !oldThread.isAlive }
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)

        assertTrue("The replacement session must survive the stale escalation", newProcess.isProcessAlive)
        assertTrue(newThread.isAlive)
    }
}
