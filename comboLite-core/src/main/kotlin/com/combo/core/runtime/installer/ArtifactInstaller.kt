package com.combo.core.runtime.installer

import android.app.Application
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.combo.core.model.PluginInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Format is owned by ComboLite, while authorization, schema/ABI policy and deletion belong to the host. */
internal class ArtifactInstaller(private val application: Application) {
    @Serializable
    private data class OwnedFile(val relativePath: String, val sizeBytes: Long, val sha256: String)
    @Serializable
    private data class Record(val formatVersion: Int, val pluginInfo: PluginInfo, val files: List<OwnedFile>)
    @Serializable
    private data class Intent(
        val formatVersion: Int,
        val pluginId: String,
        val versionCode: Long,
        val apkSha256: String,
        val ownedRelativePaths: List<String>,
    )

    suspend fun install(
        source: File,
        directory: File,
        pluginId: String,
        versionCode: Long,
        sha256: String,
        parse: suspend (File) -> PluginInfo,
        createIndex: (File, File) -> Boolean,
    ): PluginInfo {
        validateIdentity(pluginId, versionCode, sha256)
        val target = validateDirectory(directory, mayBeAbsent = true)
        if (exists(target)) return inspect(target, pluginId, versionCode, sha256)
        val sourceStat = regular(source)
        check(sourceStat.st_size in 1..MAX_APK_BYTES) { "APK exceeds allowed size" }
        check(hash(source) == sha256) { "APK SHA-256 mismatch" }
        validateZip(source)
        val nativePaths = ZipFile(source).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("lib/") }
                .map { entry ->
                    val segments = entry.name.split('/')
                    check(segments.size == 3 && segments[2].endsWith(".so")) { "Invalid Native archive path" }
                    entry.name
                }.filter { it.split('/')[1] in Build.SUPPORTED_ABIS }.toList()
        }
        val ownedPaths = (listOf(INTENT, RECORD, APK, INDEX) + nativePaths +
            nativePaths.flatMap { listOf("lib", it.substringBeforeLast('/')) }).distinct().sorted()
        check(target.mkdir()) { "Cannot create immutable artifact directory" }
        syncDirectory(requireNotNull(target.parentFile))
        // This intent is created before any payload; failed/partial installs are never overwritten.
        // Authority cleanup owns only these relative paths and validates all path components itself.
        writeNew(File(target, INTENT), Json.encodeToString(Intent(FORMAT, pluginId, versionCode, sha256,
            ownedPaths)).byteInputStream(), MAX_RECORD_BYTES)
        val apk = File(target, APK)
        writeNew(apk, openRegular(source), MAX_APK_BYTES)
        check(hash(apk) == sha256) { "Copied APK SHA-256 mismatch" }
        check(apk.setReadOnly()) { "Cannot seal installed APK" }
        validateZip(apk)
        val info = parse(apk)
        check(info.id == pluginId && info.versionCode == versionCode) { "APK identity mismatch" }
        extractNative(apk, target)
        check(createIndex(apk, target)) { "Cannot build class index" }
        val index = File(target, INDEX)
        check(regular(index).st_size in 1..MAX_INDEX_BYTES) { "Invalid class index" }
        syncFile(index)
        check(index.setReadOnly()) { "Cannot seal class index" }
        val inventory = collectFiles(target).sortedBy { it.relativePath }.filter { it.relativePath != RECORD }
        val record = Json.encodeToString(Record(FORMAT, info, inventory))
        writeNew(File(target, RECORD), record.byteInputStream(), MAX_RECORD_BYTES)
        check(File(target, RECORD).setReadOnly()) { "Cannot seal artifact record" }
        syncDirectory(target)
        return inspect(target, pluginId, versionCode, sha256)
    }

    fun inspect(directory: File, pluginId: String, versionCode: Long, sha256: String): PluginInfo {
        validateIdentity(pluginId, versionCode, sha256)
        val target = validateDirectory(directory, mayBeAbsent = false)
        val recordFile = File(target, RECORD)
        check(regular(recordFile).st_size in 1..MAX_RECORD_BYTES) { "Missing or invalid artifact record" }
        val record = openRegular(recordFile).bufferedReader().use { Json.decodeFromString<Record>(it.readText()) }
        check(record.formatVersion == FORMAT) { "Unsupported artifact record format" }
        val info = record.pluginInfo
        check(info.id == pluginId && info.versionCode == versionCode && info.path == File(target, APK).path) {
            "Artifact record identity mismatch"
        }
        check(info.enabled && info.entryClass.isNotBlank()) { "Invalid artifact entry" }
        check(record.files.size in 3..MAX_ENTRIES && record.files.map { it.relativePath }.toSet().size == record.files.size) {
            "Invalid artifact inventory"
        }
        record.files.forEach { entry ->
            validateRelative(entry.relativePath)
            check(entry.relativePath != RECORD && entry.sizeBytes >= 0 && HASH.matches(entry.sha256))
        }
        val actual = collectFiles(target).filter { it.relativePath != RECORD }.sortedBy { it.relativePath }
        check(actual == record.files.sortedBy { it.relativePath }) { "Artifact file inventory or hashes changed" }
        check(actual.single { it.relativePath == APK }.sha256 == sha256) { "Installed APK SHA-256 mismatch" }
        check(actual.any { it.relativePath == INDEX } && actual.any { it.relativePath == INTENT }) {
            "Incomplete artifact inventory"
        }
        val intentFile = File(target, INTENT)
        check(regular(intentFile).st_size in 1..MAX_RECORD_BYTES)
        val intent = openRegular(intentFile).bufferedReader().use { Json.decodeFromString<Intent>(it.readText()) }
        check(intent.formatVersion == FORMAT && intent.pluginId == pluginId &&
            intent.versionCode == versionCode && intent.apkSha256 == sha256) { "Artifact intent mismatch" }
        val actualPaths = actual.map { it.relativePath } + RECORD
        val directoryPaths = actualPaths.flatMap { path ->
            val parts = path.split('/')
            (1 until parts.size).map { parts.take(it).joinToString("/") }
        }
        check(intent.ownedRelativePaths == (actualPaths + directoryPaths).distinct().sorted()) { "Artifact ownership intent changed" }
        return info
    }

    private fun validateIdentity(pluginId: String, versionCode: Long, sha256: String) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { "Immutable artifacts require Android API 26 or newer" }
        require(pluginId.isNotBlank() && versionCode > 0 && HASH.matches(sha256)) { "Invalid artifact identity" }
    }

    private fun validateDirectory(directory: File, mayBeAbsent: Boolean): File {
        val root = application.filesDir.canonicalFile
        val inputRoot = application.filesDir.absoluteFile.toPath()
        val supplied = directory.absoluteFile.toPath()
        val relative = when {
            supplied.startsWith(inputRoot) -> inputRoot.relativize(supplied).toString()
            supplied.startsWith(root.toPath()) -> root.toPath().relativize(supplied).toString()
            else -> error("Artifact is outside application files directory")
        }
        validateRelative(relative)
        val result = File(root, relative)
        var node = root
        val segments = relative.split('/')
        segments.forEachIndexed { index, segment ->
            node = File(node, segment)
            if (mayBeAbsent && index == segments.lastIndex && !exists(node)) return@forEachIndexed
            check(OsConstants.S_ISDIR(Os.lstat(node.path).st_mode)) { "Artifact directory contains non-directory or symlink" }
        }
        return result
    }

    private fun validateRelative(path: String) {
        require(path.isNotEmpty() && path.length <= 1024 && !path.startsWith('/') && '\\' !in path && '\u0000' !in path)
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsafe artifact path" }
    }

    private fun regular(file: File) = Os.lstat(file.path).also {
        check(OsConstants.S_ISREG(it.st_mode) && it.st_nlink == 1L) { "Expected an ordinary, unlinked file: ${file.name}" }
    }

    private fun exists(file: File): Boolean = try {
        Os.lstat(file.path)
        true
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENOENT) false else throw e
    }

    private fun openRegular(file: File): FileInputStream {
        regular(file)
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            val stat = Os.fstat(fd)
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink == 1L)
            return OwnedDescriptorInputStream(fd)
        } catch (e: Throwable) {
            Os.close(fd)
            throw e
        }
    }

    private fun writeNew(file: File, input: InputStream, limit: Long) {
        input.use { source ->
            val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
            val ownedOutput = try { OwnedDescriptorOutputStream(fd) } catch (failure: Throwable) {
                if (fd.valid()) Os.close(fd)
                throw failure
            }
            ownedOutput.use { output ->
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    val size = source.read(buffer)
                    if (size < 0) break
                    count += size
                    check(count <= limit) { "Artifact output exceeds limit" }
                    output.write(buffer, 0, size)
                }
                output.fd.sync()
            }
        }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        openRegular(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun collectFiles(directory: File): List<OwnedFile> {
        val result = mutableListOf<OwnedFile>()
        fun visit(node: File, depth: Int) {
            check(depth <= 4 && result.size <= MAX_ENTRIES) { "Artifact tree exceeds limits" }
            val stat = Os.lstat(node.path)
            if (OsConstants.S_ISDIR(stat.st_mode)) {
                val children = requireNotNull(node.listFiles()) { "Cannot list artifact directory" }
                check(children.size <= MAX_ENTRIES && (node == directory || children.isNotEmpty())) { "Unexpected empty artifact directory" }
                children.sortedBy { it.name }.forEach { visit(it, depth + 1) }
            } else {
                regular(node)
                val path = node.relativeTo(directory).invariantSeparatorsPath
                validateRelative(path)
                check(stat.st_size in 0..MAX_APK_BYTES)
                result.add(OwnedFile(path, stat.st_size, hash(node)))
            }
        }
        visit(directory, 0)
        return result
    }

    /** Inspect central directory modes; ZipFile alone hides Unix symlink/device attributes. */
    private fun validateZip(apk: File) {
        RandomAccessFile(apk, "r").use { file ->
            val length = file.length()
            val tailSize = minOf(length, 65_557L).toInt()
            val tail = ByteArray(tailSize)
            file.seek(length - tailSize)
            file.readFully(tail)
            fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
            fun u32(bytes: ByteArray, at: Int) = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
            val eocd = (tail.size - 22 downTo 0).firstOrNull {
                u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tail.size
            } ?: error("Invalid ZIP end record")
            val entries = u16(tail, eocd + 10)
            val size = u32(tail, eocd + 12)
            val offset = u32(tail, eocd + 16)
            check(u16(tail, eocd + 4) == 0 && u16(tail, eocd + 6) == 0 &&
                u16(tail, eocd + 8) == entries && entries in 1..MAX_ENTRIES &&
                size in 1..MAX_INDEX_BYTES && offset + size <= length - tailSize + eocd) { "Unsupported ZIP layout" }
            file.seek(offset)
            repeat(entries) {
                val header = ByteArray(46)
                file.readFully(header)
                check(u32(header, 0) == 0x02014b50L) { "Invalid ZIP central entry" }
                val unixMode = (u32(header, 38) ushr 16).toInt() and 61440
                check(unixMode == 0 || unixMode == 32768 || unixMode == 16384) { "ZIP link or special entry rejected" }
                check(u16(header, 8) and 1 == 0 && u32(header, 24) <= MAX_APK_BYTES) { "Unsupported ZIP entry" }
                file.seek(file.filePointer + u16(header, 28) + u16(header, 30) + u16(header, 32))
                check(file.filePointer <= offset + size) { "ZIP directory overflow" }
            }
            check(file.filePointer == offset + size) { "Unexpected ZIP directory records" }
        }
        ZipFile(apk).use { zip ->
            val names = mutableSetOf<String>()
            for (entry in zip.entries()) {
                validateRelative(entry.name.removeSuffix("/"))
                check(names.add(entry.name)) { "Duplicate ZIP entry" }
            }
        }
    }

    private fun extractNative(apk: File, directory: File) {
        var total = 0L
        ZipFile(apk).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.name.startsWith("lib/") || entry.isDirectory) continue
                val segments = entry.name.split('/')
                check(segments.size == 3 && segments[2].endsWith(".so")) { "Invalid Native archive path" }
                if (segments[1] !in Build.SUPPORTED_ABIS) continue
                check(entry.size in 1..MAX_NATIVE_BYTES) { "Native entry exceeds limit" }
                total += entry.size
                check(total <= MAX_APK_BYTES) { "Native output exceeds limit" }
                val libRoot = File(directory, "lib")
                check(libRoot.isDirectory || libRoot.mkdir())
                val abiRoot = File(libRoot, segments[1])
                check(abiRoot.isDirectory || abiRoot.mkdir())
                val output = File(abiRoot, segments[2])
                writeNew(output, zip.getInputStream(entry), entry.size)
                check(regular(output).st_size == entry.size && output.setReadOnly())
                val crc = CRC32()
                openRegular(output).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        crc.update(buffer, 0, size)
                    }
                }
                check(crc.value == entry.crc) { "Native entry CRC mismatch" }
                syncDirectory(abiRoot)
                syncDirectory(libRoot)
            }
        }
    }

    private fun syncFile(file: File) = openRegular(file).use { it.fd.sync() }
    private fun syncDirectory(file: File) {
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) { "Expected directory for sync" }
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }

    companion object {
        const val FORMAT = 1
        const val RECORD = "artifact-record.json"
        const val INTENT = "artifact-installation-intent.json"
        const val APK = "base.apk"
        const val INDEX = "class_index"
        const val MAX_APK_BYTES = 1024L * 1024 * 1024
        const val MAX_NATIVE_BYTES = 512L * 1024 * 1024
        const val MAX_INDEX_BYTES = 64L * 1024 * 1024
        const val MAX_RECORD_BYTES = 32L * 1024 * 1024
        const val MAX_ENTRIES = 60_000
        val HASH = Regex("[a-f0-9]{64}")
    }
}
