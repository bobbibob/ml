package com.ml.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class RemotePackMeta(
  val etag: String?,
  val lastModified: String?,
  val contentLength: Long?
)

class R2Client(context: Context) {
  private val http = OkHttpClient()
  private val secrets = Secrets.load(context)

  private val endpoint = secrets.endpoint.trimEnd(/)
  private val bucket = secrets.bucket
  private val accessKey = secrets.accessKey
  private val secretKey = secrets.secretKey
  private val region = secrets.region
  private val objectKey = secrets.objectKey.trimStart(/)

  private fun objectUrl(): String = "$endpoint/$bucket/$objectKey"

  suspend fun headPack(): RemotePackMeta = withContext(Dispatchers.IO) {
    val url = objectUrl()
    val payloadHash = "UNSIGNED-PAYLOAD"
    val signed = SigV4.sign("HEAD", url, region, "s3", accessKey, secretKey, payloadHash)

    val req = Request.Builder().url(url).head().apply {
      for ((k, v) in signed.headers) header(k, v)
    }.build()

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
    val url = objectUrl()
    val payloadHash = "UNSIGNED-PAYLOAD"
    val signed = SigV4.sign("GET", url, region, "s3", accessKey, secretKey, payloadHash)

    val req = Request.Builder().url(url).get().apply {
      for ((k, v) in signed.headers) header(k, v)
    }.build()

    http.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
        val msg = resp.body?.string()?.take(800) ?: ""
        throw IllegalStateException("Download failed: HTTP ${resp.code} ${resp.message} $msg")
      }
      resp.body?.bytes() ?: throw IllegalStateException("Empty body")
    }
  }
}
