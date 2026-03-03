package com.ml.app.data

import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object SigV4 {
  private val amzDateFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
  private val dateStampFmt = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

  data class Signed(val headers: Map<String, String>)

  fun sign(
    method: String,
    url: String,
    region: String,
    service: String,
    accessKey: String,
    secretKey: String,
    payloadHashHex: String,
    extraHeaders: Map<String, String> = emptyMap()
  ): Signed {
    val uri = URI(url)
    val host = uri.host
    val canonicalUri = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath
    val canonicalQuery = uri.rawQuery ?: ""

    val now = Instant.now()
    val amzDate = amzDateFmt.format(now)
    val dateStamp = dateStampFmt.format(now)

    val headers = linkedMapOf(
      "host" to host,
      "x-amz-date" to amzDate,
      "x-amz-content-sha256" to payloadHashHex
    )
    for ((k, v) in extraHeaders) headers[k.lowercase()] = v

    val sorted = headers.toSortedMap()
    val canonicalHeaders = buildString {
      for ((k, v) in sorted) append(k.trim()).append(':').append(v.trim()).append('\n')
    }
    val signedHeaders = sorted.keys.joinToString(";")

    val canonicalRequest = listOf(
      method,
      canonicalUri,
      canonicalQuery,
      canonicalHeaders,
      signedHeaders,
      payloadHashHex
    ).joinToString("\n")

    val algorithm = "AWS4-HMAC-SHA256"
    val credentialScope = "$dateStamp/$region/$service/aws4_request"
    val canonicalHash = Crypto.hex(Crypto.sha256Raw(canonicalRequest.toByteArray(Charsets.UTF_8)))

    val stringToSign = listOf(
      algorithm,
      amzDate,
      credentialScope,
      canonicalHash
    ).joinToString("\n")

    val signingKey = getSignatureKey(secretKey, dateStamp, region, service)
    val signature = Crypto.hex(Crypto.hmacSha256(signingKey, stringToSign))
    val authorization = "$algorithm Credential=$accessKey/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

    val out = headers.toMutableMap()
    out["Authorization"] = authorization
    return Signed(out)
  }

  private fun getSignatureKey(secretKey: String, dateStamp: String, regionName: String, serviceName: String): ByteArray {
    val kSecret = ("AWS4$secretKey").toByteArray(Charsets.UTF_8)
    val kDate = Crypto.hmacSha256(kSecret, dateStamp)
    val kRegion = Crypto.hmacSha256(kDate, regionName)
    val kService = Crypto.hmacSha256(kRegion, serviceName)
    return Crypto.hmacSha256(kService, "aws4_request")
  }
}
