package app.facefiles.ops

import app.facefiles.data.FileDao
import app.facefiles.domain.FileCategory
import app.facefiles.scan.directoryPrefix
import app.facefiles.scan.newFileEntity
import app.facefiles.scan.upperBound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Copy / move / rename / delete on the real file system, keeping the Room index (and the faces
 * attached to indexed files) in sync so nothing needs a rescan afterwards.
 */
class JvmFileOperations(
    private val fileDao: FileDao,
    private val rootsProvider: () -> List<File>,
    private val openHandler: (File) -> Boolean,
    /** Called with every path that was created/changed/removed (Android: refresh MediaStore). */
    private val afterChange: (List<String>) -> Unit = {},
) : FileOperations {

    override fun roots(): List<String> = rootsProvider().map { it.path }

    override fun parentOf(path: String): String? = File(path).parent

    override fun open(path: String): Boolean = try {
        openHandler(File(path))
    } catch (e: Exception) {
        false
    }

    override suspend fun listFolder(path: String): List<FolderEntry> = withContext(Dispatchers.IO) {
        File(path).listFiles().orEmpty()
            .filter { !it.name.startsWith(".") }
            .map {
                FolderEntry(
                    name = it.name,
                    path = it.path,
                    isDirectory = it.isDirectory,
                    sizeBytes = if (it.isFile) it.length() else 0L,
                    modifiedAt = it.lastModified(),
                )
            }
            .sortedWith(compareByDescending<FolderEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun createFolder(parent: String, name: String): OpResult = withContext(Dispatchers.IO) {
        val problem = validateName(name)
        if (problem != null) return@withContext OpResult(0, listOf(problem))
        val dir = File(parent, name)
        when {
            dir.exists() -> OpResult(0, listOf("\"$name\" already exists"))
            dir.mkdirs() -> OpResult(1)
            else -> OpResult(0, listOf("could not create \"$name\""))
        }
    }

    override suspend fun copy(paths: List<String>, destDir: String): OpResult = withContext(Dispatchers.IO) {
        val dest = File(destDir)
        var done = 0
        val failures = ArrayList<String>()
        val created = ArrayList<File>()
        for (p in paths) {
            val src = File(p)
            try {
                check(src.exists()) { "no longer exists" }
                check(!(src.isDirectory && isInside(dest, src))) { "cannot copy a folder into itself" }
                val target = uniqueTarget(dest, src.name)
                if (src.isDirectory) {
                    check(src.copyRecursively(target, overwrite = false)) { "copy incomplete" }
                    target.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile && !it.name.startsWith(".") }
                        .forEach { created += it }
                } else {
                    Files.copy(src.toPath(), target.toPath())
                    target.setLastModified(src.lastModified())
                    created += target
                }
                done++
            } catch (e: Exception) {
                failures += "${src.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        indexFiles(created)
        afterChange(created.map { it.path })
        OpResult(done, failures)
    }

    override suspend fun move(paths: List<String>, destDir: String): OpResult = withContext(Dispatchers.IO) {
        val dest = File(destDir)
        var done = 0
        val failures = ArrayList<String>()
        val touched = ArrayList<String>()
        for (p in paths) {
            val src = File(p)
            try {
                check(src.exists()) { "no longer exists" }
                check(src.parentFile?.canonicalPath != dest.canonicalPath) { "already in that folder" }
                check(!(src.isDirectory && isInside(dest, src))) { "cannot move a folder into itself" }
                val target = uniqueTarget(dest, src.name)
                val wasDirectory = src.isDirectory
                moveOnDisk(src, target)
                relocateRows(src, target, wasDirectory)
                touched += src.path
                touched += target.path
                done++
            } catch (e: Exception) {
                failures += "${src.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        afterChange(touched)
        OpResult(done, failures)
    }

    override suspend fun rename(path: String, newName: String): OpResult = withContext(Dispatchers.IO) {
        val src = File(path)
        val problem = validateName(newName)
        if (problem != null) return@withContext OpResult(0, listOf(problem))
        try {
            check(src.exists()) { "no longer exists" }
            val target = File(src.parentFile, newName)
            check(!target.exists() || target.path.equals(src.path, ignoreCase = true) && target.path != src.path) {
                "\"$newName\" already exists"
            }
            val wasDirectory = src.isDirectory
            Files.move(src.toPath(), target.toPath())
            relocateRows(src, target, wasDirectory)
            afterChange(listOf(src.path, target.path))
            OpResult(1)
        } catch (e: Exception) {
            OpResult(0, listOf("${src.name}: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    override suspend fun delete(paths: List<String>): OpResult = withContext(Dispatchers.IO) {
        var done = 0
        val failures = ArrayList<String>()
        val removed = ArrayList<String>()
        for (p in paths) {
            val f = File(p)
            try {
                if (!f.exists()) {
                    dropRows(f, wasDirectory = false)
                    done++
                    continue
                }
                val wasDirectory = f.isDirectory
                val ok = if (wasDirectory) f.deleteRecursively() else f.delete()
                check(ok) { "could not delete" }
                dropRows(f, wasDirectory)
                removed += p
                done++
            } catch (e: Exception) {
                failures += "${f.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        afterChange(removed)
        OpResult(done, failures)
    }

    // ------------------------------------------------------------------------------------

    private fun moveOnDisk(src: File, target: File) {
        try {
            Files.move(src.toPath(), target.toPath())
        } catch (e: IOException) {
            // Different volume (internal <-> SD card) or non-empty folder: copy, then delete.
            if (src.isDirectory) {
                check(src.copyRecursively(target, overwrite = false)) { "copy incomplete" }
                check(src.deleteRecursively()) { "copied but could not remove the original" }
            } else {
                Files.copy(src.toPath(), target.toPath())
                target.setLastModified(src.lastModified())
                check(src.delete()) { "copied but could not remove the original" }
            }
        }
    }

    private suspend fun relocateRows(src: File, target: File, wasDirectory: Boolean) {
        if (wasDirectory) {
            val lower = directoryPrefix(src)
            fileDao.relocateTree(
                newDir = target.path,
                oldDirLength = src.path.codePointCount(0, src.path.length),
                lower = lower,
                upper = upperBound(lower),
            )
        } else {
            val extension = target.name.substringAfterLast('.', "").lowercase()
            val updated = fileDao.relocate(
                oldPath = src.path,
                newPath = target.path,
                newName = target.name,
                newParent = target.parent ?: "",
                extension = extension,
                category = FileCategory.fromExtension(extension),
            )
            if (updated == 0) indexFiles(listOf(target))
        }
    }

    private suspend fun dropRows(file: File, wasDirectory: Boolean) {
        if (wasDirectory) {
            val lower = directoryPrefix(file)
            fileDao.deleteSubtree(lower, upperBound(lower))
        } else {
            fileDao.deleteByPaths(listOf(file.path))
        }
    }

    private suspend fun indexFiles(files: List<File>) {
        if (files.isEmpty()) return
        val generation = System.currentTimeMillis()
        val entities = files.map {
            newFileEntity(
                path = it.path,
                name = it.name,
                parentPath = it.parent ?: "",
                sizeBytes = it.length(),
                modifiedAt = it.lastModified(),
                generation = generation,
            )
        }
        entities.chunked(500).forEach { fileDao.upsertAll(it) }
    }

    private fun validateName(name: String): String? = when {
        name.isBlank() -> "name is empty"
        name == "." || name == ".." -> "invalid name"
        name.any { it == '/' || it == '\\' || it == ':' || it == '*' || it == '?' || it == '"' || it == '<' || it == '>' || it == '|' } ->
            "name contains a character that is not allowed"
        else -> null
    }

    /** "photo.jpg" -> "photo (1).jpg" until it does not exist. */
    private fun uniqueTarget(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }

    private fun isInside(child: File, parent: File): Boolean {
        val c = child.canonicalPath
        val p = parent.canonicalPath
        return c == p || c.startsWith(p.trimEnd(File.separatorChar) + File.separatorChar)
    }
}
