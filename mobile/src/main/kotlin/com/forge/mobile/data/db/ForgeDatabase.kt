package com.forge.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The phone's database, the source of truth (blueprint §4).
 *
 * Version 1. Its schema is exported to mobile/schemas/. Any change bumps the
 * version and ships a migration tested against the previous exported schema.
 * Destructive fallback is never enabled: dropping a user's training history
 * is never an acceptable upgrade path.
 */
@Database(
    entities = [
        WorkoutSessionEntity::class,
        SetEntryEntity::class,
        GoalProfileEntity::class,
        WeighInEntity::class,
        BiaReadingEntity::class,
        TapeMeasurementEntity::class,
        DexaScanEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun goalProfileDao(): GoalProfileDao
    abstract fun weighInDao(): WeighInDao
    abstract fun bodyCompositionDao(): BodyCompositionDao

    companion object {
        const val FILE_NAME = "forge.db"

        fun open(context: Context, fileName: String = FILE_NAME): ForgeDatabase =
            Room.databaseBuilder(context.applicationContext, ForgeDatabase::class.java, fileName).build()
    }
}
