package com.yodesla.omniverse.android.update

import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import com.yodesla.omniverse.core.update.Sha256
import com.yodesla.omniverse.core.update.UpdateManifest
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Supply release.apk and old-build.apk as ignored test assets on a migrated device. */
class UpdateIdentityDeviceTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
        override fun getPackageName(): String = "com.yodesla.omniverse"
    }

    private fun fixture(name: String): Pair<ApkInstaller, UpdateManifest> {
        assumeTrue("Supply ignored signed APK fixtures", context.assets.list("")!!.contains(name))
        val hash = context.assets.open(name).use { Sha256.hex(it.source()) }
        val client = object : HttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = object : HttpResponse {
                override val status = 200
                override val headers = emptyMap<String, String>()
                override val body = context.assets.open(name).source().buffer()
                override fun close() = body.close()
            }
        }
        return ApkInstaller(context, client) to UpdateManifest(52, "0.1.51", "https://example.invalid/update.apk", hash)
    }

    @Test fun migratedInstallationAcceptsOrdinaryReleaseWithSameCurrentSigner(): Unit = runBlocking {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        assumeTrue("Run on the installation migrated from the legacy key", info.signingInfo!!.signingCertificateHistory.size > 1)
        val (installer, manifest) = fixture("release.apk")
        val verified = installer.download(manifest) {}
        assertTrue(verified.length() > 0)
        verified.delete()
    }

    @Test fun legacyCertificateIsNotAcceptedAsTheCurrentReleaseSigner() = runBlocking {
        val (installer, manifest) = fixture("old-build.apk")
        try {
            installer.download(manifest) {}
            fail("An old certificate in the installed history must not authorize old-key APKs")
        } catch (expected: UpdateIdentityException) {
            // Checksum passed; identity validation rejected the old signer.
        }
    }
}
