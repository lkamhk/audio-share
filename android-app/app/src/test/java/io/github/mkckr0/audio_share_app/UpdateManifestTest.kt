package io.github.mkckr0.audio_share_app

import io.github.mkckr0.audio_share_app.model.UpdateContract
import io.github.mkckr0.audio_share_app.model.UpdateVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    @Test
    fun acceptsOnlyStableVersionFormat() {
        assertTrue(UpdateContract.isValidVersion("0.4.6"))
        assertFalse(UpdateContract.isValidVersion("v0.4.6"))
        assertFalse(UpdateContract.isValidVersion("0.4"))
    }

    @Test
    fun acceptsOnlyHttpsDropboxDownloads() {
        assertTrue(UpdateContract.isAllowedDownloadUrl("https://www.dropbox.com/scl/fi/id/app.apk?dl=1"))
        assertTrue(UpdateContract.isAllowedDownloadUrl("https://dl.dropboxusercontent.com/app.apk"))
        assertFalse(UpdateContract.isAllowedDownloadUrl("http://www.dropbox.com/app.apk"))
        assertFalse(UpdateContract.isAllowedDownloadUrl("https://example.com/app.apk"))
    }

    @Test
    fun convertsP256P1363SignatureToDer() {
        val p1363 = ByteArray(64)
        p1363[0] = 0x80.toByte()
        p1363[32] = 0x01
        val der = UpdateVerifier.p1363ToDer(p1363)

        assertEquals(0x30, der[0].toInt() and 0xff)
        assertEquals(0x02, der[2].toInt() and 0xff)
        assertEquals(33, der[3].toInt() and 0xff)
        assertEquals(0, der[4].toInt())
        assertEquals(0x80, der[5].toInt() and 0xff)
    }
}
