package com.mcqapp

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Terminates a test that overruns [seconds] and dumps every thread's stack
 * first, so a deadlock is named in the CI log instead of silently wedging the
 * test worker. The test body still runs on the calling thread (Robolectric
 * cares which thread that is); only the watchdog lives elsewhere.
 *
 * `TimeoutRule` is deliberately not enough here: coroutine cancellation does
 * not interrupt an uninterruptible monitor or native SQLite wait. After
 * dumping the stacks this rule interrupts the test thread, then halts the
 * worker if the wait still has not returned. The halt is intentionally hard;
 * a live native wait would otherwise keep Gradle's worker alive until the
 * task-level timeout.
 */
class HangWatchdogRule(private val seconds: Long) : TestRule {

    init {
        require(seconds > 0) { "watchdog timeout must be positive" }
    }

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val testThread = Thread.currentThread()
                val watchdog = startWatchdog(
                    owner = testThread,
                    seconds = seconds,
                    description = "${description.className}#${description.methodName}"
                )
                try {
                    base.evaluate()
                } finally {
                    watchdog.interrupt()
                }
            }
        }

    companion object {
        /**
         * Runs [block] with the same hard watchdog used by the JUnit rule.
         *
         * Robolectric may create a custom `Application` before a method rule
         * is effective. Startup setup therefore uses this entry point around
         * its own `onCreate` work, so that setup cannot bypass the diagnostic
         * and hard-stop behaviour.
         */
        fun <T> guarded(
            seconds: Long,
            description: String,
            block: () -> T
        ): T {
            require(seconds > 0) { "watchdog timeout must be positive" }
            val owner = Thread.currentThread()
            val watchdog = startWatchdog(owner, seconds, description)
            return try {
                block()
            } finally {
                watchdog.interrupt()
            }
        }

        private fun startWatchdog(
            owner: Thread,
            seconds: Long,
            description: String
        ): Thread = Thread({
            try {
                Thread.sleep(seconds * 1000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            System.err.println(
                "HANG-DUMP $description overran ${seconds}s; interrupting " +
                    "thread ${owner.name}"
            )
            Thread.getAllStackTraces().forEach { (thread, stack) ->
                System.err.println("--- ${thread.name} [${thread.state}]")
                stack.take(30).forEach { System.err.println("    at $it") }
            }
            System.err.flush()
            owner.interrupt()
            // A monitor or native wait may ignore the interrupt; kill the
            // worker rather than let it hold the job for Gradle's task cap.
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
            }
            Runtime.getRuntime().halt(70)
        }, "hang-watchdog")
            .apply {
                isDaemon = true
                start()
            }
    }
}
