package com.forge.mobile.sync

import android.net.Uri
import android.util.Log
import com.forge.core.sync.ForgeWire
import com.forge.mobile.data.ForgeRepository
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.time.Clock
import java.time.LocalDate

/**
 * The phone's half of the Wearable Data Layer (blueprint 3.6).
 *
 * - [publishPlans] puts the week of plans at [ForgeWire.PLANS_PATH]. The Data
 *   Layer keeps the latest copy on the watch, so the watch has it in the gym
 *   even when the phone is away.
 * - [collectSessions] reads finished sessions the watch left under
 *   [ForgeWire.SESSION_PATH_PREFIX], stores each one and deletes its item.
 *   The listener service does this as items arrive; running it on app open
 *   too catches anything that arrived while the service couldn't run.
 *
 * Without the Wearable API (no watch app paired, no Play services), calls
 * fail and are logged; the phone app works on its own.
 */
class WatchSync(
    private val repository: ForgeRepository,
    private val ingest: SessionIngest,
    private val dataClient: DataClient,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val mutex = Mutex()

    /** Stores any waiting watch sessions, then sends fresh plans. */
    suspend fun syncNow() = mutex.withLock {
        val stored = collectSessionsLocked()
        publishPlansLocked()
        stored
    }

    /** Stores session [items] from a change notification, then sends fresh plans. */
    suspend fun onSessionItems(items: List<Pair<Uri, ByteArray>>) = mutex.withLock {
        items.forEach { (uri, bytes) -> store(uri, bytes) }
        publishPlansLocked()
    }

    suspend fun publishPlans() = mutex.withLock { publishPlansLocked() }

    private suspend fun publishPlansLocked() {
        try {
            val zone = clock.zone
            val plans = repository.plans(LocalDate.now(clock), zone, clock.instant())
            val request = PutDataRequest.create(ForgeWire.PLANS_PATH)
                .setData(ForgeWire.encodePlans(plans))
                .setUrgent()
            dataClient.putDataItem(request).await()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't send plans to the watch", e)
        }
    }

    private suspend fun collectSessionsLocked(): Int {
        val items = try {
            val buffer = dataClient.getDataItems(SESSION_ITEMS, DataClient.FILTER_PREFIX).await()
            try {
                buffer.mapNotNull { item -> item.data?.let { item.uri to it } }
            } finally {
                buffer.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read sessions from the watch", e)
            return 0
        }
        return items.count { (uri, bytes) -> store(uri, bytes) }
    }

    private suspend fun store(uri: Uri, bytes: ByteArray): Boolean {
        if (!ingest.ingest(bytes)) return false
        try {
            // The deletion is the watch's receipt. If it fails, the item is
            // stored again next time, which is harmless.
            dataClient.deleteDataItems(uri).await()
        } catch (e: Exception) {
            Log.w(TAG, "Stored $uri but couldn't clear it", e)
        }
        return true
    }

    private companion object {
        const val TAG = "WatchSync"

        /** Every node's session items: wear://<any node>/forge/session/... */
        val SESSION_ITEMS: Uri = Uri.Builder()
            .scheme(PutDataRequest.WEAR_URI_SCHEME)
            .authority("*")
            .path(ForgeWire.SESSION_PATH_PREFIX)
            .build()
    }
}
