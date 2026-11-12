package nnomm.core.io.real

import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.*
import kotlinx.serialization.builtins.*

/** Shared by Repair and launch/restore, including separate port instances. */
internal object DoorstopLocks {
    private val locks = ConcurrentHashMap<Path, ReentrantLock>()

    fun key(game: Path): Path =
        if (Files.exists(game)) game.toRealPath() else game.toAbsolutePath().normalize()

    internal var contended: () -> Unit = {}

    fun <T> locked(game: Path, block: () -> T): T =
        locks
            .computeIfAbsent(key(game)) { ReentrantLock() }
            .let { lock ->
                if (!lock.tryLock()) {
                    contended()
                    lock.lock()
                }
                try {
                    block()
                } finally {
                    lock.unlock()
                }
            }
}

/** One durable record per canonical game folder. Legacy single records migrate before use. */
internal class DoorstopRestores(private val home: Path) {
    companion object {
        private val locks = ConcurrentHashMap<Path, Any>()
    }

    private val directory = home.resolve("doorstop-restores")

    private fun file(path: Path) =
        JsonFile(path, MapSerializer(String.serializer(), String.serializer()))

    private fun destination(game: Path): Path {
        val key = DoorstopLocks.key(game).toString()
        val hash =
            MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return directory.resolve("$hash.json")
    }

    private fun <T> access(block: () -> T): T =
        synchronized(locks.computeIfAbsent(home.toAbsolutePath().normalize()) { Any() }) {
            val legacy = file(home.resolve("doorstop-restore.json"))
            val record = legacy.read().orEmpty()
            if (record.isNotEmpty()) {
                val target = file(destination(Path.of(record.getValue("path")).parent))
                if (target.read().isNullOrEmpty()) target.write(record)
                legacy.write(emptyMap())
            }
            block()
        }

    fun records(): List<Map<String, String>> = access {
        if (!directory.exists()) emptyList()
        else
            directory.listDirectoryEntries("*.json").mapNotNull {
                file(it).read()?.takeIf { it.isNotEmpty() }
            }
    }

    fun read(game: Path): Map<String, String> = access { file(destination(game)).read().orEmpty() }

    fun write(game: Path, record: Map<String, String>) = access {
        file(destination(game)).write(record)
    }

    fun clear(game: Path) = write(game, emptyMap())
}
