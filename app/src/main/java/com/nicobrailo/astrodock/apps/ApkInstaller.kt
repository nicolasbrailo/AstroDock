package com.nicobrailo.astrodock.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// Downloads an APK and hands it to the system installer, which asks the user to
// confirm. Installing also needs the "install unknown apps" permission, which
// the system asks for the first time (and which is listed in the System tab).
class ApkInstaller(private val context: Context) {
    // Installing is refused without this, so it's worth asking before
    // downloading tens of megabytes
    val canInstall: Boolean get() = context.packageManager.canRequestPackageInstalls()

    // Opens the screen where the user allows it (also listed in the System tab)
    fun askToAllowInstalls() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // Removes a download once its app is installed
    fun forget(app: Installable) {
        File(File(context.cacheDir, "apks"), "${app.packageName}.apk").delete()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Downloads to the cache and returns the file. onProgress gets whole
    // percentages, or -1 while the size is unknown.
    suspend fun download(app: Installable, onProgress: suspend (Int) -> Unit): File {
        val url = app.apkUrl ?: throw IOException("${app.name} has no direct download")
        val directory = File(context.cacheDir, "apks").apply { mkdirs() }
        // Overwritten every time, so a half-finished file is never installed
        val file = File(directory, "${app.packageName}.apk")
        val part = File(directory, "${app.packageName}.apk.part")

        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Downloading $url: HTTP ${response.code}")
                }
                val body = response.body
                val total = body.contentLength()
                var done = 0L
                var lastPercent = -2
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            val percent = if (total > 0) (done * 100 / total).toInt() else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (!part.renameTo(file)) {
                part.delete()
                throw IOException("Can't save the download")
            }
        }
        return file
    }

    // Opens the system's install screen for a downloaded file
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.apks", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
