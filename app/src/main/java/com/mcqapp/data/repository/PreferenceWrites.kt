package com.mcqapp.data.repository

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only way production code writes preferences.
 *
 * `DataStore.updateData` runs the caller's transform under the caller's own
 * coroutine context inside the single serialized write actor. A ViewModel
 * write therefore dispatches its transform to `Dispatchers.Main.immediate`, and
 * under Robolectric that means the test's main looper: once a test discards its
 * looper, the actor waits for the transform forever and every later DataStore
 * call in the process times out (the 60 s `clearGradingKeys` stalls and count
 * reads that never arrive). Hopping to IO first keeps the transform off the
 * main thread, so a dead test looper can never strand the shared actor.
 *
 * Semantics are unchanged: the transform receives the same [MutablePreferences]
 * snapshot and the same atomic read-modify-write. Reads (`data.first()`) do not
 * need this.
 *
 * `PreferenceWritesGuardTest` fails the build if `dataStore.edit` appears
 * anywhere in `src/main` outside this file.
 */
internal suspend fun Context.editPreferences(transform: suspend (MutablePreferences) -> Unit) {
    withContext(Dispatchers.IO) { dataStore.edit(transform) }
}
