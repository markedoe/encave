package app.encave.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persistence for a cellar. The JSON file store is the first implementation;
 * the encrypted sync core will provide another one behind the same interface.
 */
interface CellarStore {
    fun load(): Cellar
    fun save(cellar: Cellar)
}

/**
 * Stores the cellar as one JSON file (same encoding as backups), written atomically:
 * a temporary file is written and synced, then renamed over the previous one.
 */
class JsonFileCellarStore(val file: File) : CellarStore {
    override fun load(): Cellar {
        if (!file.exists()) return Cellar()
        return CellarJson.decode(file.readText(Charsets.UTF_8))
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
