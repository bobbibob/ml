package com.ml.app.data

import com.ml.app.BuildConfig

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject

data class RemotePackMeta(
  val etag: String?,
  val lastModified: String?,
  val contentLength: Long?
)

data class RemotePackVersionMeta(
  val version: Int,
  val etag: String?
)

class R2Client(context: Context) {
  private val http = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(240, TimeUnit.SECONDS)
    .writeTimeout(240, TimeUnit.SECONDS)
    .callTimeout(300, TimeUnit.SECONDS)
    .build()

  private val secrets = Secrets.load(context)

  private fun workerPackUrl(): String =
    BuildConfig.TASKS_API_BASE_URL + "pack_download"

  private fun workerPackMetaUrl(): String =
    BuildConfig.TASKS_API_BASE_URL + "pack_meta"

  private fun stripTrailingSlashes(s: String): String {
    var x = s
    while (x.endsWith("/")) x = x.substring(0, x.length - 1)
    return x
  }

  private fun stripLeadingSlashes(s: String): String {
    var x = s
    while (x.startsWith("/")) x = x.substring(1)
    return x
  }

  private val endpoint: String = stripTrailingSlashes(secrets.endpoint)
  private val bucket: String = secrets.bucket
  private val accessKey: String = secrets.accessKey
  private val secretKey: String = secrets.secretKey
  private val region: String = secrets.region
  private val objectKey: String = stripLeadingSlashes(secrets.objectKey)

  private fun objectUrl(): String = "$endpoint/$bucket/$objectKey"

  suspend fun headPack(): RemotePackMeta = withContext(Dispatchers.IO) {
    val req = Request.Builder()
      .url(workerPackUrl())
      .head()
      .build()

    http.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
        val msg = resp.body?.string()?.take(800) ?: ""
        throw IllegalStateException("HEAD failed: HTTP ${resp.code} ${resp.message} $msg")
      }
      RemotePackMeta(
        etag = resp.header("ETag")?.trim(),
        lastModified = resp.header("Last-Modified")?.trim(),
        contentLength = resp.header("Content-Length")?.toLongOrNull()
      )
    }
  }

  suspend fun downloadPackZip(): ByteArray = withContext(Dispatchers.IO) {
    val req = Request.Builder()
      .url(workerPackUrl())
      .get()
      .build()

    http.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
        val msg = resp.body?.string()?.take(800) ?: ""
        throw IllegalStateException("Download failed: HTTP ${resp.code} ${resp.message} $msg")
      }
      resp.body?.bytes() ?: throw IllegalStateException("Empty body")
    }
  }

  suspend fun fetchPackVersionMeta(): RemotePackVersionMeta = withContext(Dispatchers.IO) {
    val req = Request.Builder()
      .url(workerPackMetaUrl())
      .get()
      .build()

    http.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
        val msg = resp.body?.string()?.take(800) ?: ""
        throw IllegalStateException("pack_meta failed: HTTP ${resp.code} ${resp.message} $msg")
      }

      val body = resp.body?.string() ?: throw IllegalStateException("Empty body")
      val json = JSONObject(body)
      if (!json.optBoolean("ok", false)) {
        throw IllegalStateException(json.optString("error", "pack_meta not ok"))
      }

      RemotePackVersionMeta(
        version = json.optInt("version", 0),
        etag = json.optString("etag", null)
      )
    }
  }


  suspend fun uploadPackZip(zipFile: File) = withContext(Dispatchers.IO) {
    require(zipFile.exists()) { "ZIP not found: ${zipFile.absolutePath}" }

    val url = objectUrl()
    val payloadHash = "UNSIGNED-PAYLOAD"

    val signed = SigV4.sign(
      method = "PUT",
      url = url,
      region = region,
      service = "s3",
      accessKey = accessKey,
      secretKey = secretKey,
      payloadHashHex = payloadHash,
      extraHeaders = mapOf("content-type" to "application/zip")
    )

    val body = zipFile.asRequestBody("application/zip".toMediaType())

    val req = Request.Builder()
      .url(url)
      .put(body)
      .apply {
        for ((k, v) in signed.headers) header(k, v)
      }
      .build()

    http.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
        val msg = resp.body?.string()?.take(1200) ?: ""
        throw IllegalStateException("PUT failed: HTTP ${resp.code} ${resp.message} $msg")
      }
    }
  }
}
