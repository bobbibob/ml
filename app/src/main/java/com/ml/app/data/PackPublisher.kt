package com.ml.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object PackPublisher {
  suspend fun publish(ctx: Context) = withContext(Dispatchers.IO) {
    // merged DB должен быть актуален
    PackDbSync.refreshMergedDb(ctx)

    val packDir = PackPaths.packDir(ctx)
    val mergedDb = PackDbSync.mergedDbFile(ctx)

    require(packDir.exists()) { "packDir missing: ${packDir.absolutePath}" }
    require(mergedDb.exists() && mergedDb.length() > 0L) { "merged DB missing: ${mergedDb.absolutePath}" }

    val tmpDir = File(ctx.cacheDir, "pack_publish_tmp")
    if (tmpDir.exists()) tmpDir.deleteRecursively()
    tmpDir.mkdirs()

    // копируем всё из packDir
    packDir.copyRecursively(tmpDir, overwrite = true)

    // заменяем data.sqlite на merged db
    val outDb = File(tmpDir, "data.sqlite")
    mergedDb.copyTo(outDb, overwrite = true)

    // zip -> file
    val zipBytes = ZipUtil.zipDirToBytes(tmpDir)
    val zipFile = File(ctx.cacheDir, "database_pack.zip")
    FileOutputStream(zipFile).use { it.write(zipBytes) }

    // upload
    R2Client(ctx).uploadPackZip(zipFile)

    // cleanup
    try { tmpDir.deleteRecursively() } catch (_: Throwable) {}
    try { zipFile.delete() } catch (_: Throwable) {}
  }
}
