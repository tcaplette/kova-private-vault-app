package com.app.vault

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * P1-8 task 1.8 — the runtime half of the `ACCESS_NETWORK_STATE` strip.
 *
 * `:app:check<Variant>WorkConstraints` proves nothing *builds* a network constraint.
 * This proves nothing *enqueued* carries one, which is a different claim: a
 * constraint could arrive through a code path the class scan cannot see.
 *
 * What is being guarded against is silent, and that is the whole point.
 * WorkManager does not throw without the permission — `getActiveNetworkState`
 * catches the `SecurityException` and returns `NetworkState(isConnected=false)`
 * permanently, so constrained work never becomes eligible. No crash, no error
 * log, an import stuck in `ENQUEUED` for good.
 */
@RunWith(AndroidJUnit4::class)
class WorkConstraintsTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val rng = SecureRandom()

    private companion object { const val TAG = "VaultWorkConstraints" }

    @Before fun setUp() = VaultSession.lock()

    @After
    fun tearDown() {
        WorkManager.getInstance(ctx).cancelAllWork()
        VaultSession.lock()
    }

    private fun id() = ByteArray(16).also { rng.nextBytes(it) }

    @Test
    fun noEnqueuedWorkCarriesANetworkRequirement() {
        val wm = WorkManager.getInstance(ctx)

        // Enqueue the app's own request shape. Without this the query below would
        // inspect nothing and pass vacuously, which is the failure mode this
        // whole family of checks exists to avoid.
        val request = OneTimeWorkRequestBuilder<ImportWorker>().build()
        WorkJobs.register(
            request.id,
            WorkJobs.ImportJob(Uri.parse("content://nonexistent/c"), mutableListOf(), id())
        )
        wm.enqueue(request).result.get(10, TimeUnit.SECONDS)

        val infos = wm.getWorkInfos(
            WorkQuery.fromStates(
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED
            )
        ).get(10, TimeUnit.SECONDS)

        Log.i(TAG, "inspected ${infos.size} work spec(s)")
        assertTrue("nothing was inspected — the assertion would be vacuous", infos.isNotEmpty())

        val offenders = infos.filter {
            it.constraints.requiredNetworkType != NetworkType.NOT_REQUIRED
        }
        offenders.forEach {
            Log.e(TAG, "work ${it.id} requires ${it.constraints.requiredNetworkType}")
        }
        assertTrue(
            "work requiring a network can never run with ACCESS_NETWORK_STATE stripped, and " +
                "WorkManager will not say so — it will simply never start: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theAppsOwnRequestDeclaresNoConstraintAtAll() {
        val request = OneTimeWorkRequestBuilder<ImportWorker>().build()
        WorkJobs.register(
            request.id,
            WorkJobs.ImportJob(Uri.parse("content://nonexistent/d"), mutableListOf(), id())
        )

        val constraints = request.workSpec.constraints
        Log.i(TAG, "import request constraints: network=${constraints.requiredNetworkType}")
        assertEquals(NetworkType.NOT_REQUIRED, constraints.requiredNetworkType)
        assertTrue("charging", !constraints.requiresCharging())
        assertTrue("device idle", !constraints.requiresDeviceIdle())
    }
}
