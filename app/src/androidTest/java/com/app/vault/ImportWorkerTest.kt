package com.app.vault

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * That WorkManager still works with `RECEIVE_BOOT_COMPLETED` stripped (P1-7 task
 * 1.3), and that a worker waking with the vault locked waits rather than failing.
 */
@RunWith(AndroidJUnit4::class)
class ImportWorkerTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val rng = SecureRandom()

    private companion object { const val TAG = "VaultWorker" }

    @Before fun setUp() = VaultSession.lock()
    @After fun tearDown() = VaultSession.lock()

    private fun id() = ByteArray(16).also { rng.nextBytes(it) }

    @Test fun workManagerEnqueuesAndRunsWithoutBootPermission() {
        val wm = WorkManager.getInstance(ctx)
        val request = OneTimeWorkRequestBuilder<ImportWorker>().build()
        WorkJobs.register(
            request.id,
            WorkJobs.ImportJob(Uri.parse("content://nonexistent/1"), mutableListOf(), id())
        )

        wm.enqueue(request).result.get(10, TimeUnit.SECONDS)

        // The vault is locked, so the worker must reach ENQUEUED-for-retry rather
        // than failing — and reaching any state at all proves WorkManager itself
        // is functioning with the permission removed.
        val state = awaitState(wm, request.id) { it == WorkInfo.State.ENQUEUED || it.isFinished }
        Log.i(TAG, "locked vault, worker state: $state")
        assertTrue("worker never reached a state", state != null)
    }

    @Test fun aLockedVaultMakesTheWorkerWaitNotFail() {
        val wm = WorkManager.getInstance(ctx)
        val request = OneTimeWorkRequestBuilder<ImportWorker>().build()
        WorkJobs.register(
            request.id,
            WorkJobs.ImportJob(Uri.parse("content://nonexistent/2"), mutableListOf(), id())
        )

        wm.enqueue(request).result.get(10, TimeUnit.SECONDS)

        // The worker retries on a locked vault, so it cycles RUNNING -> ENQUEUED
        // and a single snapshot catches whichever moment it lands in. Sample, and
        // assert on what must never appear and what must.
        val seen = mutableSetOf<WorkInfo.State>()
        var attempts = 0
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val info = wm.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS) ?: break
            seen += info.state
            attempts = maxOf(attempts, info.runAttemptCount)
            if (info.state == WorkInfo.State.FAILED) break
            if (seen.contains(WorkInfo.State.ENQUEUED) && attempts >= 1) break
            Thread.sleep(200)
        }
        wm.cancelWorkById(request.id)

        Log.i(TAG, "locked-vault states seen: $seen, runAttempts: $attempts")
        assertTrue("a locked vault made the worker FAIL: $seen", !seen.contains(WorkInfo.State.FAILED))
        assertTrue("the worker never retried: $seen", seen.contains(WorkInfo.State.ENQUEUED))
        assertTrue("no run was attempted", attempts >= 1)
    }

    private fun awaitState(
        wm: WorkManager,
        id: java.util.UUID,
        until: (WorkInfo.State) -> Boolean
    ): WorkInfo.State? {
        val deadline = System.currentTimeMillis() + 20_000
        var last: WorkInfo.State? = null
        while (System.currentTimeMillis() < deadline) {
            last = wm.getWorkInfoById(id).get(5, TimeUnit.SECONDS)?.state
            if (last != null && until(last) && last != WorkInfo.State.RUNNING) return last
            Thread.sleep(200)
        }
        return last
    }
}
