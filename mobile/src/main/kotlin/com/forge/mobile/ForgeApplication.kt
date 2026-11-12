package com.forge.mobile

import android.app.Application
import com.forge.mobile.data.ForgeRepository
import com.forge.mobile.data.db.ForgeDatabase
import com.forge.mobile.data.secure.AndroidKeystoreKeyProvider
import com.forge.mobile.data.secure.HealthProfileStore
import com.forge.mobile.health.HealthConnectWriter
import com.forge.mobile.sync.SessionIngest
import com.forge.mobile.sync.SharedPreferencesPendingMirrors
import com.forge.mobile.sync.WatchSync
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Holds the app-wide singletons: one database, repository and watch link per
 * process. Plain manual wiring; a handful of objects don't justify a DI framework.
 */
class ForgeApplication : Application() {

    /** Work that should finish even if the screen that started it goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository: ForgeRepository by lazy {
        ForgeRepository(
            database = ForgeDatabase.open(this),
            healthProfileStore = HealthProfileStore(
                // noBackupFilesDir: the Keystore key can't be restored elsewhere, so
                // a backed-up copy of this file would be unreadable anyway.
                file = File(noBackupFilesDir, HealthProfileStore.FILE_NAME),
                keys = AndroidKeystoreKeyProvider(),
            ),
        )
    }

    val healthConnect: HealthConnectWriter by lazy { HealthConnectWriter(this) }

    val watchSync: WatchSync by lazy {
        WatchSync(
            repository = repository,
            ingest = SessionIngest(repository, healthConnect, SharedPreferencesPendingMirrors(this)),
            dataClient = Wearable.getDataClient(this),
        )
    }

    /**
     * Collects waiting watch sessions, retries Health Connect writes, and sends
     * the watch fresh plans, in the background.
     */
    fun syncWatch() {
        appScope.launch { watchSync.syncNow() }
    }
}
