package com.anita.paintgo.regions

import android.content.Context
import android.util.Log
import org.locationtech.jts.io.WKBReader
import org.locationtech.jts.io.WKBWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

// Binary cache of parsed regions, keyed by source. JTS WKB for geometry, DataInput/Output
// for the surrounding metadata. Cache file is tagged with both the format version and the
// app's versionCode, so updated bundled GeoJSONs (which require an APK update) invalidate
// automatically.
internal object RegionCache {

    // 2: us-states.geojson swapped from a coarse (~68 vertices/state) source to
    // NE 10m admin_1 (~800 vertices/state); USA feature in countries.geojson
    // replaced by the union of those states so the two layers align exactly.
    // Old cache files would otherwise keep serving the stale geometries.
    private const val FORMAT_VERSION = 2
    private const val TAG = "RegionCache"

    // [keep] is consulted per entry against (sourceKey, externalId). Entries it rejects
    // get their WKB skipped — the slow part — so a filtered read of a 250-country file
    // stays cheap. Default keeps everything.
    fun read(
        context: Context,
        sourceKey: String,
        keep: (String, String) -> Boolean = { _, _ -> true },
    ): List<Region>? {
        val file = cacheFile(context, sourceKey) ?: return null
        if (!file.exists()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { dis ->
                if (dis.readInt() != FORMAT_VERSION) return null
                val count = dis.readInt()
                val reader = WKBReader()
                val out = ArrayList<Region>(count)
                repeat(count) {
                    val name = dis.readUTF()
                    val externalId = dis.readUTF()
                    val kindOrd = dis.readByte().toInt()
                    val areaM2 = dis.readDouble()
                    val bboxS = dis.readDouble()
                    val bboxN = dis.readDouble()
                    val bboxW = dis.readDouble()
                    val bboxE = dis.readDouble()
                    val wkbLen = dis.readInt()
                    if (!keep(sourceKey, externalId)) {
                        skipFully(dis, wkbLen)
                        return@repeat
                    }
                    val wkb = ByteArray(wkbLen).also { dis.readFully(it) }
                    out += Region(
                        sourceKey = sourceKey,
                        externalId = externalId,
                        kind = RegionKind.entries[kindOrd],
                        name = name,
                        geometry = reader.read(wkb),
                        areaM2 = areaM2,
                        bboxS = bboxS,
                        bboxN = bboxN,
                        bboxW = bboxW,
                        bboxE = bboxE,
                    )
                }
                out
            }
        } catch (e: Throwable) {
            Log.w(TAG, "$sourceKey: cache read failed ($e); discarding")
            file.delete()
            null
        }
    }

    private fun skipFully(dis: DataInputStream, n: Int) {
        var remaining = n
        while (remaining > 0) {
            val skipped = dis.skipBytes(remaining)
            if (skipped <= 0) {
                // Stream refusing to skip — fall back to reading and discarding.
                dis.readFully(ByteArray(remaining))
                return
            }
            remaining -= skipped
        }
    }

    fun write(context: Context, sourceKey: String, regions: List<Region>) {
        val file = cacheFile(context, sourceKey) ?: return
        file.parentFile?.mkdirs()
        try {
            val writer = WKBWriter()
            DataOutputStream(file.outputStream().buffered()).use { dos ->
                dos.writeInt(FORMAT_VERSION)
                dos.writeInt(regions.size)
                for (r in regions) {
                    dos.writeUTF(r.name)
                    dos.writeUTF(r.externalId)
                    dos.writeByte(r.kind.ordinal)
                    dos.writeDouble(r.areaM2)
                    dos.writeDouble(r.bboxS)
                    dos.writeDouble(r.bboxN)
                    dos.writeDouble(r.bboxW)
                    dos.writeDouble(r.bboxE)
                    val wkb = writer.write(r.geometry)
                    dos.writeInt(wkb.size)
                    dos.write(wkb)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "$sourceKey: cache write failed: $e")
            file.delete()
        }
    }

    private fun cacheFile(context: Context, sourceKey: String): File? {
        val versionCode = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (e: Throwable) {
            return null
        }
        return File(context.filesDir, "regions-cache/$sourceKey.app$versionCode.bin")
    }
}
