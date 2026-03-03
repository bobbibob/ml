package com.ml.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class R2Client(context: Context) {
  private val http = OkHttpClient()
  private val secrets = Secrets.load(context)

  private val endpoint = secrets.endpoint.trimEnd('/')
  private val bucket = secrets.bucket
  private val accessKey = secrets.accessKey
  private val secretKey = secrets.secretKey
  private val region = secrets.region
  private val objectKey = secrets.objectKey.trimStart('/')

  private fun objectUrl(): String = "$endpoint/$bucket/$objectKey"

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

  suspend fun uploadPackZip(bytes: ByteArray) = withContext(Dispatchers.IO) {
    val url = objectUrl()
    val payloadHash = "UNSIGNED-PAYLOAD"
    val signed = SigV4.sign(
      "PUT", url, region, "s3", accessKey, secretKey, payloadHash,
      extraHeaders = mapOf("content-type" to "application/zip")
    )

    val body = bytes.toRequestBody("application/zip".toMediaType())
    val req = Request.Builder().url(url).put(body).apply {
      for ((k, v) in signed.headers) header(k, v)
    }.build()

    http.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
      val msg = resp.body?.string()?.take(800) ?: ""
      throw IllegalStateException("Upload failed: HTTP ${resp.code} ${resp.message} $msg")
    }
    }
  }
}
