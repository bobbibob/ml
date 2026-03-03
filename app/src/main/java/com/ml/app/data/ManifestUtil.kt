package com.ml.app.data

import org.json.JSONObject
import java.io.File
import java.time.Instant

object ManifestUtil {
  fun readVersion(manifestFile: File): Int {
    val j = JSONObject(manifestFile.readText(Charsets.UTF_8))
    return j.optInt("version", 0)
  }

  fun ensureExists(manifestFile: File) {
    if (manifestFile.exists()) return
    val j = JSONObject()
    j.put("pack_id", java.util.UUID.randomUUID().toString())
    j.put("version", 0)
    j.put("updated_at", Instant.now().toString())
    j.put("updated_by", "ml-app")
    j.put("db_hash", "")
    j.put("images_hash", "")
    manifestFile.parentFile?.mkdirs()
    manifestFile.writeText(j.toString(2), Charsets.UTF_8)
  }

  fun computeDbHash(dbFile: File): String =
    Crypto.hex(Crypto.sha256Raw(dbFile.readBytes()))

  fun computeImagesHash(imagesDir: File): String {
    if (!imagesDir.exists()) return ""
    val lines = imagesDir.walkTopDown()
      .filter { it.isFile }
      .map {
        val rel = it.relativeTo(imagesDir.parentFile!!).path.replace(File.separatorChar, '/')
        val sha = Crypto.hex(Crypto.sha256Raw(it.readBytes()))
        "$rel|$sha\n"
      }
      .sorted()
      .joinToString("")
    return Crypto.hex(Crypto.sha256Raw(lines.toByteArray(Charsets.UTF_8)))
  }

  fun bumpVersionAndUpdate(manifestFile: File, updatedBy: String, dbHash: String, imagesHash: String): Int {
    val j = JSONObject(manifestFile.readText(Charsets.UTF_8))
    val ver = j.optInt("version", 0) + 1
    j.put("version", ver)
    j.put("updated_at", Instant.now().toString())
    j.put("updated_by", updatedBy)
    j.put("db_hash", dbHash)
    j.put("images_hash", imagesHash)
    manifestFile.writeText(j.toString(2), Charsets.UTF_8)
    return ver
  }
}
