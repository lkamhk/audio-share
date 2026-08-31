package io.github.mkckr0.audio_share_app.model

import android.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

@Serializable
data class UpdateManifest(
    val version: String,
    @SerialName("pub_date") val publishedAt: String,
    val url: String,
    val signature: String,
    val notes: String = "",
)

object UpdateContract {
    const val ENDPOINT =
        "https://nfqkislweudltvckonog.supabase.co/functions/v1/audio-share-update"
    const val CHANNEL = "audio-share-android-stable"
    const val TARGET = "android"
    const val ARCHITECTURE = "universal"

    private val versionPattern = Regex("^\\d+\\.\\d+\\.\\d+$")

    fun isValidVersion(value: String): Boolean = versionPattern.matches(value)

    fun isValidSignature(value: String): Boolean = try {
        Base64.decode(value, Base64.DEFAULT).size == 64
    } catch (_: IllegalArgumentException) {
        false
    }

    fun isAllowedDownloadUrl(value: String): Boolean {
        return try {
            val uri = URI(value)
            val host = uri.host?.lowercase() ?: return false
            uri.scheme.equals("https", ignoreCase = true) &&
                (host == "dropbox.com" || host.endsWith(".dropbox.com") ||
                    host == "dropboxusercontent.com" || host.endsWith(".dropboxusercontent.com"))
        } catch (_: Exception) {
            false
        }
    }
}

object UpdateVerifier {
    fun verify(file: File, signatureBase64: String): Boolean {
        return try {
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.decode(UpdatePublicKey.SPKI_BASE64, Base64.DEFAULT))
            )
            val p1363 = Base64.decode(signatureBase64, Base64.DEFAULT)
            if (p1363.size != 64) {
                false
            } else {
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(publicKey)
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        verifier.update(buffer, 0, count)
                    }
                }
                verifier.verify(p1363ToDer(p1363))
            }
        } catch (_: Exception) {
            false
        }
    }

    internal fun p1363ToDer(signature: ByteArray): ByteArray {
        require(signature.size == 64) { "A P-256 P1363 signature must contain 64 bytes" }
        val r = toDerInteger(signature.copyOfRange(0, 32))
        val s = toDerInteger(signature.copyOfRange(32, 64))
        val bodyLength = 2 + r.size + 2 + s.size
        return byteArrayOf(0x30, bodyLength.toByte(), 0x02, r.size.toByte()) + r +
            byteArrayOf(0x02, s.size.toByte()) + s
    }

    private fun toDerInteger(value: ByteArray): ByteArray {
        var first = 0
        while (first < value.lastIndex && value[first] == 0.toByte()) first++
        val unsigned = value.copyOfRange(first, value.size)
        return if ((unsigned[0].toInt() and 0x80) != 0) byteArrayOf(0) + unsigned else unsigned
    }
}
