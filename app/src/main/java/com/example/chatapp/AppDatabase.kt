package com.example.chatapp

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.chatapp.util.SafeLog

@Database(
    entities = [ChatEntity::class, MessageEntity::class],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "chat_database"
                )
                .addMigrations(MIGRATION_1_3, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .build()
                INSTANCE = instance
                instance
            }
        }

        private fun SupportSQLiteDatabase.hasColumn(tableName: String, columnName: String): Boolean {
            query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex != -1 && cursor.getString(nameIndex) == columnName) {
                        return true
                    }
                }
            }
            return false
        }

        private fun SupportSQLiteDatabase.addColumnIfMissing(
            tableName: String,
            columnName: String,
            definition: String
        ) {
            if (!hasColumn(tableName, columnName)) {
                execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $definition")
            }
        }

        val MIGRATION_1_3 = object : androidx.room.migration.Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("chats", "isPinned", "INTEGER NOT NULL DEFAULT 0")
                db.addColumnIfMissing("chats", "lastUpdated", "INTEGER NOT NULL DEFAULT 0")
                db.addColumnIfMissing("chats", "summary", "TEXT NOT NULL DEFAULT ''")

                db.execSQL(
                    "UPDATE chats SET lastUpdated = timestamp " +
                        "WHERE lastUpdated IS NULL OR lastUpdated = 0"
                )
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("chats", "summary", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("chats", "ownerKey", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("messages", "syncId", "TEXT NOT NULL DEFAULT ''")
                // Для старых строк создаем стабильный syncId без миграции данных на сервере.
                db.query("SELECT id FROM messages WHERE syncId = ''").use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getInt(0)
                        val uuid = java.util.UUID.randomUUID().toString()
                        db.execSQL("UPDATE messages SET syncId = '$uuid' WHERE id = $id")
                    }
                }
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("chats", "isDeleted", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("messages", "attachmentData", "TEXT")
                db.addColumnIfMissing("messages", "attachmentMimeType", "TEXT")
                db.addColumnIfMissing("messages", "attachmentFileName", "TEXT")
                db.addColumnIfMissing("messages", "attachmentContext", "TEXT")
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("messages", "updatedAt", "INTEGER NOT NULL DEFAULT 0")
                db.addColumnIfMissing("messages", "isDeleted", "INTEGER NOT NULL DEFAULT 0")
                db.addColumnIfMissing("messages", "editRevision", "INTEGER NOT NULL DEFAULT 0")

                db.execSQL("UPDATE messages SET updatedAt = timestamp WHERE updatedAt = 0")
                db.query("SELECT id FROM messages WHERE syncId = ''").use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getInt(0)
                        val uuid = java.util.UUID.randomUUID().toString()
                        db.execSQL("UPDATE messages SET syncId = '$uuid' WHERE id = $id")
                    }
                }

                db.query(
                    """
                    SELECT chatId, role, substr(content, 1, 80) AS contentSnippet, COUNT(*) AS duplicateCount
                    FROM messages
                    WHERE isDeleted = 0
                    GROUP BY chatId, role, content, (timestamp / 5000)
                    HAVING COUNT(*) > 1
                    """
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        SafeLog.w(
                            "AppDatabase",
                            "Potential legacy duplicate messages detected for manual review: " +
                                "chatId=${cursor.getString(0)}, role=${cursor.getString(1)}, " +
                                "count=${cursor.getInt(3)}"
                        )
                    }
                }

                db.execSQL(
                    """
                    DELETE FROM messages
                    WHERE syncId != ''
                      AND id NOT IN (
                          SELECT keep.id
                          FROM messages AS keep
                          WHERE keep.syncId = messages.syncId
                          ORDER BY keep.editRevision DESC, keep.updatedAt DESC, keep.id DESC
                          LIMIT 1
                      )
                    """
                )

                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatId ON messages(chatId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_messages_syncId ON messages(syncId)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_chatId_isDeleted_timestamp " +
                        "ON messages(chatId, isDeleted, timestamp)"
                )
            }
        }
    }
}
