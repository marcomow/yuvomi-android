package io.github.marcomow.yuvomi.android

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs [block] (signing in, seeding settings) on a background thread and waits for it with the
 * rule's `waitUntil`, which keeps the main thread working while it waits.
 *
 * Not `runBlocking`: a Compose test runs on the main thread with `Dispatchers.Main` routed
 * through it. The app's session launches work on Main (cookie saves, state updates), and a
 * `runBlocking` on that thread can wait for that work forever. The CI watchdog caught
 * RewardsScreenTest.signIn parked in `runBlocking` for over 1000 seconds.
 */
fun ComposeContentTestRule.offMain(timeoutMs: Long = 60_000, block: suspend () -> Unit) {
    val done = AtomicReference<Result<Unit>?>(null)
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { done.set(runCatching { block() }) }
    waitUntil(timeoutMs) { done.get() != null }
    done.get()!!.getOrThrow()
}
