package com.forge.wear.sync

import android.net.Uri
import android.util.Log
import com.forge.core.sync.ForgeWire
import com.forge.core.sync.WatchPlans
import com.forge.core.sync.WireFormatException
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.PutDataRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * The watch's half of the Wearable Data Layer (blueprint 3.6).
 *
 * Plans arrive from the phone at [ForgeWire.PLANS_PATH]. The Data Layer keeps
 * the latest copy on the watch, so it's readable with the phone out of range.
 * Finished sessions go back at [ForgeWire.sessionPath]. A put succeeds once
 * the item is stored on the watch, and the Data Layer delivers it whenever
 * the phone is next reachable.
 */
class WatchLink(private val dataClient: DataClient) {

    /** The latest plans, then every update while collected. Null until the phone has sent any. */
    fun plans(): Flow<WatchPlans?> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { events ->
            // The buffer is only valid during this callback: copy the bytes out.
            val bytes = events
                .filter { it.type == DataEvent.TYPE_CHANGED }
                .map { it.dataItem.freeze() }
                .lastOrNull { it.uri.path == ForgeWire.PLANS_PATH }
                ?.data
            bytes?.let(::decode)?.let { trySend(it) }
        }
        try {
            dataClient.addListener(listener, PLANS_ITEMS, DataClient.FILTER_LITERAL).await()
            trySend(latestPlans())
        } catch (e: Exception) {
            Log.w(TAG, "Wearable Data Layer unavailable", e)
            trySend(null)
        }
        awaitClose { dataClient.removeListener(listener) }
    }

    /** Hands a finished session to the Data Layer for delivery. True once it's stored on the watch. */
    suspend fun sendSession(sessionId: String, payload: ByteArray): Boolean = try {
        val request = PutDataRequest.create(ForgeWire.sessionPath(sessionId)).setData(payload).setUrgent()
        dataClient.putDataItem(request).await()
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't hand session $sessionId to the Data Layer", e)
        false
    }

    private suspend fun latestPlans(): WatchPlans? {
        val buffer = dataClient.getDataItems(PLANS_ITEMS).await()
        val all = try {
            buffer.mapNotNull { item -> item.data?.let(::decode) }
        } finally {
            buffer.release()
        }
        return all.maxByOrNull { it.generatedAt }
    }

    private fun decode(bytes: ByteArray): WatchPlans? = try {
        ForgeWire.decodePlans(bytes)
    } catch (e: WireFormatException) {
        // A newer phone app: keep running on what we had until the watch app updates.
        Log.w(TAG, "Ignoring plans this watch app can't read", e)
        null
    }

    private companion object {
        const val TAG = "WatchLink"

        /** The phone's plans item, from whichever node sent it: wear://<any node>/forge/plans */
        val PLANS_ITEMS: Uri = Uri.Builder()
            .scheme(PutDataRequest.WEAR_URI_SCHEME)
            .authority("*")
            .path(ForgeWire.PLANS_PATH)
            .build()
    }
}
