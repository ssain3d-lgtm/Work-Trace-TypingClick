package dev.worktrace.tiktrace.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RawPayload::class], version = 1, exportSchema = true)
abstract class CaptureDatabase : RoomDatabase() {

    abstract fun rawPayloads(): RawPayloadDao

    companion object {
        private const val NAME = "capture.db"

        @Volatile
        private var instance: CaptureDatabase? = null

        fun get(context: Context): CaptureDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(context.applicationContext, CaptureDatabase::class.java, NAME)
                    .build()
                    .also { instance = it }
            }
    }
}
