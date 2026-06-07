package com.anita.paintgo.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import com.anita.paintgo.LocationService
import com.anita.paintgo.data.AppDatabase
import com.anita.paintgo.data.PAINTGO_SCHEMA_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
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
 * not against uninstall.
 */
object BackupManager {

    private const val PREFIX = "paintgo-"
    private const val SUFFIX = ".db"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    // Serializes everything that touches the live DB file (create / restore) plus delete,
    // so a backup can't be VACUUMed out of a DB mid-restore, two restores can't interleave,
    // and a file can't be deleted out from under a restore reading it. The UI also disables
    // these actions while one runs; this is the hard backstop.
    private val dbOpMutex = Mutex()

    // External app dir survives reboots and is user-browsable; falls back to internal
    // storage if external isn't mounted (rare, but getExternalFilesDir can return null).
    private fun backupsDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "backups").apply { mkdirs() }
    }

    suspend fun createBackup(context: Context): BackupFile = withContext(Dispatchers.IO) {
        dbOpMutex.withLock {
            val dir = backupsDir(context)
            val file = File(dir, "$PREFIX${LocalDateTime.now().format(STAMP)}$SUFFIX")
            // VACUUM INTO refuses to overwrite an existing file; the second-resolution
            // timestamp makes a same-name collision effectively impossible, but guard anyway.
            if (file.exists()) file.delete()

            val db = AppDatabase.get(context).openHelper.writableDatabase
            // The filename is a bound expression — VACUUM INTO accepts any string expression.
            // Run via query(...).use so the cursor materializes (forces the statement to
            // execute) and is closed.
            db.query(SimpleSQLiteQuery("VACUUM INTO ?", arrayOf(file.absolutePath)))
                .use { it.moveToFirst() }

            BackupFile(file.name, file.length(), file.lastModified())
        }
    }

    suspend fun listBackups(context: Context): List<BackupFile> = withContext(Dispatchers.IO) {
        backupsDir(context).listFiles { f ->
            f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX)
        }.orEmpty()
            .map { BackupFile(it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.lastModified }
    }

    suspend fun deleteBackup(context: Context, name: String): Boolean = withContext(Dispatchers.IO) {
        dbOpMutex.withLock {
            // Name-only API: reject any path separators so callers can't escape the dir.
            if (name.contains('/') || name.contains('\\')) return@withLock false
            val file = File(backupsDir(context), name)
            file.exists() && file.delete()
        }
    }

    /**
     * Replace the live database with the named backup. On [RestoreResult.Success] the
     * caller MUST restart the process — Room and every Compose Flow are still bound to the
     * now-closed old instance.
     *
     * The guarantee: the live DB is only ever replaced — atomically — by a candidate that
     * has already been proven to open cleanly under the real schema. Every failure mode
     * before that atomic step aborts with the live DB byte-for-byte untouched.
     *
     * Pipeline:
     *   1. Refuse while recording (an open write handle would race the swap); reject a
     *      backup whose schema is newer than this build (it'd never migrate forward).
     *   2. `PRAGMA quick_check` the backup read-only — rejects a truncated/corrupt file
     *      that merely has a parseable header.
     *   3. Copy to a temp file IN the databases dir (same filesystem → atomic rename later)
     *      and trial-open it with [AppDatabase.buildStrict] (migrations, NO destructive
     *      fallback). This runs the exact migration path the real reopen will; if it would
     *      drop tables (missing migration / identity-hash mismatch) it THROWS here instead,
     *      and we abort. The trial also migrates the temp forward, so the real reopen is a
     *      no-op clean open.
     *   4. fsync the temp, release Room's handle, and `rename(2)` the temp over paintgo.db.
     *      Rename is atomic: it succeeds (live = new) or fails (live = old), never partial.
     *      No delete-then-rename, so there is never a window with no database.
     */
    suspend fun restoreBackup(context: Context, name: String): RestoreResult =
        withContext(Dispatchers.IO) {
            dbOpMutex.withLock {
                if (name.contains('/') || name.contains('\\')) {
                    return@withLock RestoreResult.Failure("Invalid backup name.")
                }
                // NOTE: this is a snapshot, not a lock. The UI also refuses restore while
                // recording. A DB write the service queued just before stopping could in
                // principle still be in flight here — a narrow, accepted residual risk that
                // closing this fully would need a drain hook on the service.
                if (LocationService.running.value) {
                    return@withLock RestoreResult.Failure("Stop recording before restoring a backup.")
                }
                val src = File(backupsDir(context), name)
                if (!src.exists()) return@withLock RestoreResult.Failure("Backup file not found.")

                val probe = probeBackup(src)
                    ?: return@withLock RestoreResult.Failure("That file isn't a valid database.")
                if (!probe.quickCheckOk) {
                    return@withLock RestoreResult.Failure("This backup is corrupt and can't be restored.")
                }
                if (probe.version > PAINTGO_SCHEMA_VERSION) {
                    return@withLock RestoreResult.Failure(
                        "This backup is from a newer app version (schema ${probe.version} vs " +
                            "$PAINTGO_SCHEMA_VERSION). Update the app first."
                    )
                }

                val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
                val tmp = File(dbFile.parentFile, "${AppDatabase.DB_NAME}.restore-tmp")
                deleteWithSidecars(tmp) // clear any leftover from a prior crashed restore
                try {
                    src.copyTo(tmp, overwrite = true)
                } catch (e: Exception) {
                    deleteWithSidecars(tmp)
                    return@withLock RestoreResult.Failure(
                        "Couldn't read the backup: ${e.message ?: "copy failed"}"
                    )
                }

                // Trial-open the temp under the real migrations WITHOUT destructive fallback.
                // Throws if the schema can't migrate cleanly — caught here, live DB untouched.
                try {
                    val test = AppDatabase.buildStrict(context, tmp.name)
                    try {
                        test.openHelper.writableDatabase // forces open + migration
                        test.ownerDao().getSelf()        // exercise the migrated schema
                    } finally {
                        test.close()
                    }
                } catch (e: Exception) {
                    deleteWithSidecars(tmp)
                    return@withLock RestoreResult.Failure(
                        "This backup isn't compatible with the current app and can't be restored safely."
                    )
                }

                // The temp is now a validated, current-version DB. Make its contents durable,
                // drop the trial's sidecars, release the live handle, then atomically swap.
                fsync(tmp)
                File(tmp.path + "-wal").delete()
                File(tmp.path + "-shm").delete()
                AppDatabase.closeAndReset()

                if (!tmp.renameTo(dbFile)) {
                    // Atomic rename refused — live DB is still intact (rename is all-or-
                    // nothing). Abort without deleting anything live; restart reopens it.
                    deleteWithSidecars(tmp)
                    return@withLock RestoreResult.Failure("Couldn't apply the backup; nothing was changed.")
                }
                // Old live sidecars are now stale against the swapped-in main file.
                File(dbFile.path + "-wal").delete()
                File(dbFile.path + "-shm").delete()
                fsync(dbFile)

                RestoreResult.Success
            }
        }

    private data class BackupProbe(val version: Int, val quickCheckOk: Boolean)

    /** Open [file] read-only to read its schema version and run `PRAGMA quick_check`.
     *  Returns null if it can't be opened as SQLite at all. */
    private fun probeBackup(file: File): BackupProbe? =
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val ok = db.rawQuery("PRAGMA quick_check", null).use { c ->
                    c.moveToFirst() && c.getString(0).equals("ok", ignoreCase = true)
                }
                BackupProbe(db.version, ok)
            }
        } catch (e: Exception) {
            null
        }

    /** Best-effort durability barrier: force the file's bytes + metadata to disk. */
    private fun fsync(file: File) {
        try {
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        } catch (e: Exception) {
            // Durability hardening only — a failure here doesn't compromise correctness.
        }
    }

    private fun deleteWithSidecars(dbFile: File) {
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
    }
}
