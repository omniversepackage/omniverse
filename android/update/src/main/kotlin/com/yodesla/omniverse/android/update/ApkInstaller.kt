package com.yodesla.omniverse.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.requireSuccess
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.core.update.Sha256
import com.yodesla.omniverse.core.update.UpdateManifest
import java.io.File
import java.io.FileOutputStream
import okio.source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads, verifies and hands off the APK for sideloading (PLAN.md P3.7).
 * Kept thin on purpose: no unit tests here, the logic lives in the core module.
 */
open class ApkInstaller(
    private val context: Context,
    private val http: HttpClient,
) {
    private val updatesDir: File get() = File(context.cacheDir, "updates")

    /**
     * Streams the APK to cacheDir/updates/<versionCode>.apk (older files there are deleted first)
     * and verifies its SHA-256. Progress 0f..1f comes from Content-Length when the server sends one.
     * A checksum mismatch deletes the file and throws [SourceException.BadResponse].
     */
    open suspend fun download(manifest: UpdateManifest, onProgress: (Float) -> Unit): File =
        // Blocking file + socket IO: never on the caller's (usually Main) thread.
        withContext(Dispatchers.IO) { downloadBlocking(manifest, onProgress) }

    private suspend fun downloadBlocking(manifest: UpdateManifest, onProgress: (Float) -> Unit): File {
        val target = File(updatesDir, "${manifest.versionCode}.apk")
        if (!updatesDir.exists() && !updatesDir.mkdirs()) throw SourceException.Network("Cannot create $updatesDir")
        updatesDir.listFiles()?.filter { it != target && it.name.endsWith(".apk") }?.forEach { it.delete() }

        val response = http.get(manifest.apkUrl).requireSuccess(manifest.apkUrl)
        val source = response.body
        var written = 0L
        val total = response.headers["content-length"]?.toLongOrNull()?.takeIf { it > 0 } ?: -1L

        val sink = FileOutputStream(target)
        try {
            response.use {
                sink.use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val n = source.read(buffer)
                        if (n == -1) break
                        out.write(buffer, 0, n)
                        written += n
                        if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } catch (e: java.io.IOException) {
            target.delete()
            throw SourceException.Network("APK download failed", e)
        }
        onProgress(1f)

        val actual = sha256Of(target)
        if (!Sha256.matches(manifest.sha256, actual)) {
            target.delete()
            throw SourceException.BadResponse("Update file is damaged")
        }
        if (!isSameAppAndSigner(target)) {
            target.delete()
            throw SourceException.BadResponse("Update file is not a genuine Omniverse build")
        }
        return target
    }

    /**
     * Defence in depth on top of the checksum: the file must be THIS app (same package name) signed
     * with THIS app's certificate. Android would refuse a foreign signer too, but only after showing
     * the user an install dialog; we refuse before that.
     */
    @Suppress("DEPRECATION")
    private fun isSameAppAndSigner(file: File): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        else android.content.pm.PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun certs(info: android.content.pm.PackageInfo): Set<String> {
            val sigs = if (Build.VERSION.SDK_INT >= 28) {
                val si = info.signingInfo ?: return emptySet()
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            } else info.signatures
            return sigs.orEmpty().map { it.toCharsString() }.toSet()
        }
        val theirs = certs(archive)
        return theirs.isNotEmpty() && theirs == certs(installed)
    }

    private fun sha256Of(file: File): String = file.inputStream().use { Sha256.hex(it.source()) }

    /** True when the installer intent can be fired without the system "install unknown apps" prompt. */
    open fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** The system "install unknown apps" screen for THIS package (API 26+ gate is on the caller). */
    open fun openInstallPermissionSettings() {
        val perApp = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Many Android TV builds have no per-app screen: fall back to security, then to Settings.
        val candidates = listOf(
            perApp,
            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent)
                return
            } catch (_: android.content.ActivityNotFoundException) {
            }
        }
    }

    /** Fires the install intent via the module's FileProvider (authority "${applicationId}.updates"). */
    open fun install(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updates",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
