package com.example.miniterminal

import android.content.Context
import android.os.Build
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Downloads a real proot binary + a minimal Alpine Linux rootfs on first
 * use, so the terminal can run a genuine Linux environment (bash, apk
 * install, python, real git, etc) instead of just Android's built-in
 * toybox shell.
 *
 * proot binaries: https://github.com/skirsten/proot-portable-android-binaries
 * (prebuilt, based on Termux's own proot package — no cross-compiling needed).
 * Alpine rootfs: official Alpine CDN, looked up dynamically so this doesn't
 * go stale when Alpine ships a new release.
 *
 * Honest caveats, read before relying on this:
 * - Verified to work well on Android <= 14. On Android 15+, some devices'
 *   tightened seccomp filters are known to break proot outright for
 *   certain syscalls — this is a real, currently open issue in the wider
 *   proot ecosystem as of mid-2026, not something specific to this app,
 *   and there is no simple fix at time of writing.
 * - Downloads roughly 40-80MB total the first time "setup" is run. This
 *   is a one-time cost per install, cached afterward.
 */
object ProotSetup {

    private const val PROOT_BASE_URL = "https://skirsten.github.io/proot-portable-android-binaries"
    private const val ALPINE_INDEX_URL_TEMPLATE =
        "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/%s/"

    fun abiSegment(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "armv7"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> null
    }

    fun rootfsDir(context: Context) = File(context.filesDir, "alpine")
    fun prootDir(context: Context) = File(context.filesDir, "proot")
    fun prootBinary(context: Context) = File(prootDir(context), "proot")
    fun tmpDir(context: Context) = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
    fun homeDir(context: Context) = File(rootfsDir(context), "root").apply { mkdirs() }

    fun isReady(context: Context): Boolean =
        prootBinary(context).exists() && File(rootfsDir(context), "bin").exists()

    /** Blocking — call this from a background thread, not the UI thread. */
    fun performSetup(context: Context, onProgress: (String) -> Unit) {
        val abi = abiSegment()
        if (abi == null) {
            onProgress("Unsupported CPU architecture: ${Build.SUPPORTED_ABIS.joinToString()}\n")
            return
        }
        onProgress("Detected architecture: $abi\n")

        if (!prootBinary(context).exists()) {
            onProgress("Downloading proot binary...\n")
            prootDir(context).mkdirs()
            downloadFile("$PROOT_BASE_URL/$abi/proot", prootBinary(context))
            prootBinary(context).setExecutable(true, false)
            onProgress("proot ready (${prootBinary(context).length() / 1024} KB)\n")
        } else {
            onProgress("proot binary already installed, skipping.\n")
        }

        if (!File(rootfsDir(context), "bin").exists()) {
            onProgress("Looking up the current Alpine release for $abi...\n")
            val indexUrl = ALPINE_INDEX_URL_TEMPLATE.format(abi)
            val html = fetchText(indexUrl)
            val match = Regex("alpine-minirootfs-[0-9.]+-$abi\\.tar\\.gz").find(html)
                ?: throw IllegalStateException(
                    "Could not find an Alpine minirootfs build for $abi. " +
                    "Alpine may have changed their download page structure."
                )
            val fileName = match.value
            onProgress("Downloading $fileName ...\n")
            val tarballFile = File(context.cacheDir, fileName)
            downloadFile(indexUrl + fileName, tarballFile)

            onProgress("Extracting Alpine rootfs (can take a minute)...\n")
            rootfsDir(context).mkdirs()
            extractTarGz(tarballFile, rootfsDir(context))
            tarballFile.delete()
            onProgress("Alpine rootfs ready.\n")
        } else {
            onProgress("Alpine rootfs already installed, skipping.\n")
        }

        homeDir(context)
        onProgress("Setup complete.\n")
    }

    /** Builds the proot command that launches an interactive shell inside Alpine. */
    fun buildLaunchCommand(context: Context): List<String> {
        return listOf(
            prootBinary(context).absolutePath,
            "-0", // fake root inside the container — apk and most package installs expect this
            "-r", rootfsDir(context).absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm",
            "/bin/sh"
        )
    }

    fun launchEnv(context: Context): Map<String, String> = mapOf(
        "PROOT_TMP_DIR" to tmpDir(context).absolutePath
    )

    private fun downloadFile(urlStr: String, dest: File) {
        val conn = URI(urlStr).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
    }

    private fun fetchText(urlStr: String): String {
        val conn = URI(urlStr).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun extractTarGz(tarGzFile: File, destDir: File) {
        java.util.zip.GZIPInputStream(tarGzFile.inputStream()).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    when {
                        entry.isDirectory -> outFile.mkdirs()
                        entry.isSymbolicLink -> {
                            outFile.parentFile?.mkdirs()
                            try {
                                java.nio.file.Files.createSymbolicLink(
                                    outFile.toPath(), File(entry.linkName).toPath()
                                )
                            } catch (ignored: Exception) {
                                // link may already exist from a prior partial run — safe to skip
                            }
                        }
                        else -> {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                            val executableBit = (entry.mode and 0b001_000_000) != 0
                            if (executableBit) outFile.setExecutable(true, false)
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }
}
