package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression for the SEND-intent replay bug: Android recreates MainActivity on
 * a config change and redelivers the *same* SEND intent. Handling it in
 * `onCreate` unconditionally re-copies the file, re-requests the import and
 * pushes `import/direct` again — losing typed Import input and yanking the
 * learner out of Study/Test. A failed copy also re-shows its snackbar.
 *
 * The decision lives in [SendIntentRouter], tested here without launching an
 * Activity, Compose, or Room. Each callback below stands for one
 * `SharedImport.request`/`requestError` call (one shared-import request).
 */
class SendIntentReplayTest {

    private fun routerWith(requests: MutableList<Int>) = SendIntentRouter { requests += 1 }

    @Test
    fun rotationWithSameIntentRequestsSharedImportOnlyOnce() {
        val requests = mutableListOf<Int>()
        val router = routerWith(requests)

        // Cold start with a SEND intent: handled exactly once.
        router.onActivityCreate(isSend = true, isRecreation = false)
        // Config change: a new Activity instance with a non-null saved-state
        // Bundle and the same SEND intent re-delivered by Android.
        router.onActivityCreate(isSend = true, isRecreation = true)

        assertEquals(1, requests.size)
    }

    @Test
    fun processDeathRecreationWithSendIsIntentionallyNotRehandled() {
        val requests = mutableListOf<Int>()
        // A fresh Activity instance after process death also arrives with a
        // non-null savedInstanceState (and the same SEND intent re-delivered),
        // but no one-shot guard survives the dead process. It is deliberately
        // still not handled: re-handling would re-copy the file and re-launch
        // the import after the learner already resumed. Dropping the SEND here
        // is the accepted trade-off of handling a SEND once per task.
        val router = routerWith(requests)

        router.onActivityCreate(isSend = true, isRecreation = true)

        assertEquals(0, requests.size)
    }

    @Test
    fun repeatedOnCreateNeverRequestsMoreThanOnce() {
        val requests = mutableListOf<Int>()
        val router = routerWith(requests)

        // Even a second `onCreate` that looks fresh (null saved state) cannot
        // request twice: the one-shot guard pins it to the first handle.
        router.onActivityCreate(isSend = true, isRecreation = false)
        router.onActivityCreate(isSend = true, isRecreation = false)

        assertEquals(1, requests.size)
    }

    @Test
    fun distinctSendViaOnNewIntentStillRequests() {
        val requests = mutableListOf<Int>()
        val router = routerWith(requests)

        router.onActivityCreate(isSend = true, isRecreation = false)
        // A subsequent, legitimate share arrives through onNewIntent and must
        // still produce a request (not be swallowed by the one-shot guard).
        router.onNewIntent(isSend = true)

        assertEquals(2, requests.size)
    }

    @Test
    fun newIntentWithoutSendActionIsIgnored() {
        val requests = mutableListOf<Int>()
        val router = routerWith(requests)

        router.onNewIntent(isSend = false)

        assertEquals(0, requests.size)
    }

    @Test
    fun coldStartWithoutSendNeverRequestsButALaterShareStillDoes() {
        val requests = mutableListOf<Int>()
        val router = routerWith(requests)

        router.onActivityCreate(isSend = false, isRecreation = false)
        assertEquals(0, requests.size)

        router.onNewIntent(isSend = true)
        assertEquals(1, requests.size)
    }
}
