package com.ml.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipUtil {
  suspend fun unzipToDir(zipBytes: ByteArray, outDir: File) = withContext(Dispatchers.IO) {
    if (outDir.exists()) outDir.deleteRecursively()
    outDir.mkdirs()
    ZipInputStream(BufferedInputStream(ByteArrayInputStream(zipBytes))).use { zis ->
      while (true) {
        val entry = zis.nextEntry ?: break

        val outFile = File(outDir, entry.name)

        // Zip Slip protection: ensure the entry stays within outDir
        val outDirPath = outDir.canonicalFile.toPath()
        val outFilePath = outFile.canonicalFile.toPath()
        if (!outFilePath.startsWith(outDirPath)) {
          // Skip suspicious paths like ../../...
          zis.closeEntry()
          continue
        }

        if (entry.isDirectory) outFile.mkdirs()
        else {
          outFile.parentFile?.mkdirs()
          FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
        }
        zis.closeEntry()
      }
    }
  }

  suspend fun zipDirToBytes(dir: File): ByteArray = withContext(Dispatchers.IO) {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(BufferedOutputStream(baos)).use { zos ->
      val base = dir.absolutePath.trimEnd(File.separatorChar) + File.separator
      dir.walkTopDown().forEach { f ->
        if (f.isDirectory) return@forEach
        val rel = f.absolutePath.removePrefix(base).replace(File.separatorChar, '/')
        zos.putNextEntry(ZipEntry(rel))
        FileInputStream(f).use { it.copyTo(zos) }
        zos.closeEntry()
      }
    }
    baos.toByteArray()
  }
}
