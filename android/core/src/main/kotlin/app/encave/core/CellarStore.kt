package app.encave.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Persistence for a cellar. The JSON file store is the first implementation;
 * the encrypted sync core will provide another one behind the same interface.
 */
interface CellarStore {
    fun load(): Cellar
    fun save(cellar: Cellar)

    /**
     * Moves a stored cellar that cannot be read out of the way, so nothing saved later
     * overwrites it. Returns the name it is kept under, or null if it could not be moved.
     */
    fun setAside(): String? = null

    /** Cellar files that could not be read and were kept aside, oldest first. */
    fun keptAside(): List<File> = emptyList()
}

/**
 * Stores the cellar as one JSON file (same encoding as backups), written atomically:
 * a temporary file is written and synced, then renamed over the previous one.
 */
class JsonFileCellarStore(val file: File, private val now: () -> LocalDateTime = LocalDateTime::now) : CellarStore {
    override fun load(): Cellar {
        if (!file.exists()) return Cellar()
        return CellarJson.decode(file.readText(Charsets.UTF_8))
    }

    /** `cellar-unreadable-<yyyyMMdd-HHmmss>.json`, next to the cellar file. */
    override fun setAside(): String? {
        if (!file.exists()) return null
        val stamp = now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))
        val base = "${file.nameWithoutExtension}-unreadable-$stamp"
        var target = File(file.parentFile, "$base.${file.extension}")
        var n = 2
        while (target.exists()) target = File(file.parentFile, "$base-${n++}.${file.extension}")
        return runCatching { Files.move(file.toPath(), target.toPath()) }.map { target.name }.getOrNull()
    }

    override fun keptAside(): List<File> {
        val prefix = "${file.nameWithoutExtension}-unreadable-"
        return file.parentFile?.listFiles { f -> f.name.startsWith(prefix) && f.extension == file.extension }
            .orEmpty().sortedBy { it.name }
    }

    override fun save(cellar: Cellar) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.outputStream().use { stream ->
            stream.write(CellarJson.encode(cellar).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** Keeps everything in memory. For previews and tests. */
class InMemoryCellarStore(initial: Cellar = Cellar()) : CellarStore {
    private var cellar = initial.copy()

    @Synchronized override fun load(): Cellar = cellar.copy()

    @Synchronized override fun save(cellar: Cellar) {
        this.cellar = cellar.copy()
    }
}
