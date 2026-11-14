package com.ahmadreza.photoflow

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class PhotoFile(val uri: Uri, val name: String, val mime: String, val size: Long = 0L)
data class OutputBundle(val folder: DocumentFile, val files: List<Uri>)

object PhotoFiles {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")

    suspend fun scan(context: Context, tree: Uri): List<PhotoFile> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return@withContext emptyList()
        root.listFiles().filter { it.isFile && isImage(it) }.map { doc ->
            PhotoFile(doc.uri, doc.name ?: "image", doc.type ?: "image/*", doc.length())
        }.sortedBy { it.name.lowercase() }
    }

    private fun isImage(file: DocumentFile): Boolean {
        val ext = file.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return file.type?.startsWith("image/") == true || ext in imageExtensions
    }

    suspend fun createOutput(
        context: Context,
        destinationTree: Uri,
        originals: List<PhotoFile>,
        outputsByOriginal: Map<String, List<PhotoFile>>,
        separateFolders: Boolean
    ): OutputBundle = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, destinationTree) ?: error("پوشه مقصد در دسترس نیست")
        val export = root.createDirectory(uniqueName(root, "PhotoFlow-Export")) ?: error("ساخت پوشه خروجی ناموفق بود")
        val created = mutableListOf<Uri>()
        originals.forEachIndexed { index, original ->
            val children = outputsByOriginal[original.uri.toString()].orEmpty()
            val base = safeBaseName(original.name)
            if (separateFolders) {
                val groupDir = export.createDirectory("%02d - %s".format(index + 1, base))
                    ?: error("ساخت پوشه گروه ناموفق بود: $base")
                created += copy(context.contentResolver, original, groupDir, "%02d - %s".format(index + 1, original.name))
                children.forEachIndexed { version, photo ->
                    created += copy(context.contentResolver, photo, groupDir, "%02d - %s - نسخه %d.%s".format(
                        index + 1, base, version + 1, extension(photo.name)
                    ))
                }
            } else {
                created += copy(context.contentResolver, original, export, "%02d - %s".format(index + 1, original.name))
                children.forEachIndexed { version, photo ->
                    created += copy(context.contentResolver, photo, export, "%02d - %s - نسخه %d.%s".format(
                        index + 1, base, version + 1, extension(photo.name)
                    ))
                }
            }
        }
        OutputBundle(export, created)
    }

    suspend fun createZip(context: Context, folder: DocumentFile, destinationTree: Uri): Uri =
        withContext(Dispatchers.IO) {
            val dest = DocumentFile.fromTreeUri(context, destinationTree) ?: error("مقصد پیدا نشد")
            val zipDoc = dest.createFile("application/zip", uniqueName(dest, "PhotoFlow-Export.zip"))
                ?: error("ساخت ZIP ناموفق بود")
            context.contentResolver.openOutputStream(zipDoc.uri)?.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    fun addTree(dir: DocumentFile, prefix: String) {
                        dir.listFiles().forEach { child ->
                            val path = prefix + (child.name ?: "file")
                            if (child.isDirectory) addTree(child, "$path/")
                            else {
                                zip.putNextEntry(ZipEntry(path))
                                context.contentResolver.openInputStream(child.uri)?.use { input ->
                                    BufferedInputStream(input).copyTo(zip)
                                }
                                zip.closeEntry()
                            }
                        }
                    }
                    addTree(folder, "")
                }
            } ?: error("نوشتن فایل ZIP ناموفق بود")
            zipDoc.uri
        }

    private fun copy(resolver: ContentResolver, source: PhotoFile, target: DocumentFile, name: String): Uri {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val mime = source.mime.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val out = target.createFile(mime, safe) ?: error("ساخت فایل ناموفق بود: $safe")
        resolver.openInputStream(source.uri)?.use { input ->
            resolver.openOutputStream(out.uri)?.use { output -> input.copyTo(output) }
                ?: error("نوشتن فایل ناموفق بود: $safe")
        } ?: error("خواندن فایل ناموفق بود: ${source.name}")
        return out.uri
    }

    private fun uniqueName(parent: DocumentFile, desired: String): String {
        val existing = parent.listFiles().mapNotNull { it.name }.toSet()
        if (desired !in existing) return desired
        var i = 2
        while ("$desired-$i" in existing) i++
        return "$desired-$i"
    }
    private fun safeBaseName(name: String) = name.substringBeforeLast('.', name)
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
    private fun extension(name: String) = name.substringAfterLast('.', "jpg").ifBlank { "jpg" }
}
