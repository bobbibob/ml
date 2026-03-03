package com.ml.app.data

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Crypto {
  fun sha256Hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    .let { _ -> bytesToHex(MessageDigest.getInstance("SHA-256").digest(bytes)) }

  private fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

  fun sha256Raw(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

  fun hmacSha256(key: ByteArray, data: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data.toByteArray(Charsets.UTF_8))
  }

  fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
