package com.mcqapp

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Fails a test that overruns [seconds] and dumps every thread's stack first,
 * so a deadlock is named in the CI log instead of silently wedging the test
 * worker. The test body still runs on the calling thread (Robolectric cares
 * which thread that is); only the watchdog lives elsewhere.
 */
class HangWatchdogRule(private val seconds: Long) : TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val testThread = Thread.currentThread()
                val watchdog = Thread({
                    try {
                        Thread.sleep(seconds * 1000)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    System.err.println(
                        "HANG-DUMP ${description.className}#${description.methodName} " +
                            "overran ${seconds}s; interrupting"
                    )
                    Thread.getAllStackTraces().forEach { (thread, stack) ->
                        System.err.println("--- ${thread.name} [${thread.state}]")
                        stack.take(30).forEach { System.err.println("    at $it") }
                    }
                    System.err.flush()
                    testThread.interrupt()
                }, "hang-watchdog")
                watchdog.isDaemon = true
                watchdog.start()
                try {
                    base.evaluate()
                } finally {
                    watchdog.interrupt()
                }
            }
        }
}
