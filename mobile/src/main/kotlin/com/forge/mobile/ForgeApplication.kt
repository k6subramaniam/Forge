package com.forge.mobile

import android.app.Application
import com.forge.mobile.data.ForgeRepository
import com.forge.mobile.data.db.ForgeDatabase
import com.forge.mobile.data.secure.AndroidKeystoreKeyProvider
import com.forge.mobile.data.secure.HealthProfileStore
import java.io.File

/**
 * Holds the app-wide singletons: one database and one repository per process.
 * Plain manual wiring; three objects don't justify a DI framework.
 */
class ForgeApplication : Application() {

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
}
