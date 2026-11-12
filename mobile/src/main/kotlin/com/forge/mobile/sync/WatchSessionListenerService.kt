package com.forge.mobile.sync

import com.forge.core.sync.ForgeWire
import com.forge.mobile.ForgeApplication
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/**
 * Woken by the Data Layer when the watch hands over a finished session
 * (manifest filter: wear://<any node>/forge/session/...), even while the phone
 * app is closed. The session is stored before this returns, so the system
 * can't reclaim the process halfway through.
 */
class WatchSessionListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        // The buffer is only valid during this call: copy what's needed out first.
        val items = events
            .filter { it.type == DataEvent.TYPE_CHANGED }
            .map { it.dataItem.freeze() }
            .filter { it.uri.path.orEmpty().startsWith(ForgeWire.SESSION_PATH_PREFIX) }
            .mapNotNull { item -> item.data?.let { item.uri to it } }
        if (items.isEmpty()) return

        val sync = (application as ForgeApplication).watchSync
        // Wearable callbacks run off the main thread, so blocking here is allowed.
        runBlocking { sync.onSessionItems(items) }
    }
}
