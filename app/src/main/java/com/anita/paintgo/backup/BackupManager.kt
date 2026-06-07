package com.anita.paintgo.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import com.anita.paintgo.LocationService
import com.anita.paintgo.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One backup file on disk: a self-contained SQLite snapshot of paintgo.db. */
data class BackupFile(val name: String, val sizeBytes: Long, val lastModified: Long)

/** Outcome of a restore attempt. On [Success] the caller must restart the process so the
 *  app rebinds to the swapped-in database; [Failure.reason] is user-facing. */
sealed interface RestoreResult {
    data object Success : RestoreResult
    data class Failure(val reason: String) : RestoreResult
}

/**
 * Manual, on-device backups of the Room database.
 *
 * A backup is produced with SQLite's `VACUUM INTO`, which writes a single consistent,
 * defragmented snapshot of the live DB in one statement — it folds in any pending WAL
 * content and doesn't need the database closed, so it's safe to run even mid-recording.
 * (`VACUUM INTO` needs SQLite ≥ 3.27; API 29 ships 3.28, our min SDK.)
 *
 * Files live in [backupsDir] — app-specific external storage. No runtime permission is
 * needed there at any SDK level and it's browsable in a file manager, but it IS removed
 * when the app is uninstalled, so this protects against in-app data loss / corruption,
 * not against uninstall. Restore is a planned follow-up; this object only creates, lists,
 * and deletes for now.
 */
object BackupManager {

    private const val PREFIX = "paintgo-"
    private const val SUFFIX = ".db"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    // External app dir survives reboots and is user-browsable; falls back to internal
    // storage if external isn't mounted (rare, but getExternalFilesDir can return null).
    private fun backupsDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "backups").apply { mkdirs() }
    }

    suspend fun createBackup(context: Context): BackupFile = withContext(Dispatchers.IO) {
        val dir = backupsDir(context)
        val file = File(dir, "$PREFIX${LocalDateTime.now().format(STAMP)}$SUFFIX")
        // VACUUM INTO refuses to overwrite an existing file; the second-resolution
        // timestamp makes a same-name collision effectively impossible, but guard anyway.
        if (file.exists()) file.delete()

        val db = AppDatabase.get(context).openHelper.writableDatabase
        // The filename is a bound expression — VACUUM INTO accepts any string expression.
        // Run via query(...).use so the cursor materializes (forces the statement to
        // execute) and is closed.
        db.query(SimpleSQLiteQuery("VACUUM INTO ?", arrayOf(file.absolutePath))).use { it.moveToFirst() }

        BackupFile(file.name, file.length(), file.lastModified())
    }

    suspend fun listBackups(context: Context): List<BackupFile> = withContext(Dispatchers.IO) {
        backupsDir(context).listFiles { f ->
            f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX)
        }.orEmpty()
            .map { BackupFile(it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.lastModified }
    }

    suspend fun deleteBackup(context: Context, name: String): Boolean = withContext(Dispatchers.IO) {
        // Name-only API: reject any path separators so callers can't escape the dir.
        if (name.contains('/') || name.contains('\\')) return@withContext false
        val file = File(backupsDir(context), name)
        file.exists() && file.delete()
    }

    /**
     * Replace the live database with the named backup. On [RestoreResult.Success] the
     * caller MUST restart the process — Room and every Compose Flow are still bound to the
     * now-closed old instance.
     *
     * Order is chosen so a failure never leaves the app worse off than before:
     *   1. Refuse while recording (an open write handle would race the swap) and reject a
     *      backup whose schema is newer than this build (reopening it would trip Room's
     *      destructive fallback and wipe the very data we restored).
     *   2. Validate the backup opens as SQLite *before* touching the live DB.
     *   3. Stage the copy to a temp file, then rename it over paintgo.db (rename is atomic
     *      on the same filesystem), and drop the stale -wal/-shm. A failed copy aborts with
     *      the live DB untouched.
     * An older backup is fine: Room runs its migrations forward on the next open.
     */
    suspend fun restoreBackup(context: Context, name: String): RestoreResult =
        withContext(Dispatchers.IO) {
            if (name.contains('/') || name.contains('\\')) {
                return@withContext RestoreResult.Failure("Invalid backup name.")
            }
            if (LocationService.running.value) {
                return@withContext RestoreResult.Failure("Stop recording before restoring a backup.")
            }
            val src = File(backupsDir(context), name)
            if (!src.exists()) return@withContext RestoreResult.Failure("Backup file not found.")

            val backupVersion = try {
                SQLiteDatabase.openDatabase(src.path, null, SQLiteDatabase.OPEN_READONLY)
                    .use { it.version }
            } catch (e: Exception) {
                return@withContext RestoreResult.Failure("That file isn't a valid database.")
            }

            val currentVersion = AppDatabase.get(context).openHelper.readableDatabase.version
            if (backupVersion > currentVersion) {
                return@withContext RestoreResult.Failure(
                    "This backup is from a newer app version (schema $backupVersion vs $currentVersion). Update the app first."
                )
            }

            val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
            val tmp = File(dbFile.parentFile, "${AppDatabase.DB_NAME}.restore-tmp")
            try {
                src.copyTo(tmp, overwrite = true)
            } catch (e: Exception) {
                tmp.delete()
                return@withContext RestoreResult.Failure("Couldn't read the backup: ${e.message ?: "copy failed"}")
            }

            // Past the point of no failure-without-loss: release Room's handle, swap the
            // file, clear sidecars. Rename over the existing file is atomic on Linux; fall
            // back to delete-then-rename if the platform refuses the overwrite.
            AppDatabase.closeAndReset()
            if (!tmp.renameTo(dbFile)) {
                dbFile.delete()
                tmp.renameTo(dbFile)
            }
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()

            RestoreResult.Success
        }
}
