package nnomm.core.io.real

import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.sf.sevenzipjbinding.*
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream

private val sevenZipLoad = Any()

/**
 * Loads the native library once, one caller at a time. The library's own first-use loading is
 * not safe for two archives opened together (an install beside a background check), and after
 * one failure, for example a full temporary folder, it does not try again by itself.
 */
private fun loadSevenZip() =
    synchronized(sevenZipLoad) {
        if (!SevenZip.isInitializedSuccessfully()) SevenZip.initSevenZipFromPlatformJAR()
    }

/** Extract in one pass, including solid archives, without trusting native entry paths. */
internal suspend fun unpackSevenZip(
    file: Path,
    destination: (String) -> Path,
    budget: (Int) -> Unit,
) {
    val context = currentCoroutineContext()
    context.ensureActive()
    loadSevenZip()
    RandomAccessFile(file.toFile(), "r").use { input ->
        SevenZip.openInArchive(ArchiveFormat.SEVEN_ZIP, RandomAccessFileInStream(input)).use {
            archive ->
            val count = archive.numberOfItems
            require(count in 1..100_000) { "Empty or oversized archive" }
            val entries =
                (0 until count).map { index ->
                    context.ensureActive()
                    val folder = archive.getProperty(index, PropID.IS_FOLDER) == true
                    val path = archive.getProperty(index, PropID.PATH) as? String
                    require(!path.isNullOrEmpty()) { "Missing 7z entry path" }
                    val dest = destination(path.replace('\\', '/'))
                    require(
                        archive.getProperty(index, PropID.IS_ANTI) != true &&
                            archive.getProperty(index, PropID.IS_ALT_STREAM) != true &&
                            archive.getProperty(index, PropID.SYM_LINK) == null &&
                            archive.getProperty(index, PropID.HARD_LINK) == null
                    ) {
                        "Unsafe 7z entry"
                    }
                    val attributes =
                        (archive.getProperty(index, PropID.ATTRIBUTES) as? Number)?.toInt() ?: 0
                    require(attributes and 0x440 == 0) { "7z device or reparse point" }
                    // 7z stores Unix mode bits in the high word of ATTRIBUTES (extension bit
                    // 0x8000).
                    val mode =
                        (archive.getProperty(index, PropID.POSIX_ATTRIB) as? Number)?.toInt()
                            ?: if (attributes and 0x8000 != 0) attributes ushr 16 else 0
                    val type = mode and 0xf000
                    require(type == 0 || type == if (folder) 0x4000 else 0x8000) {
                        "Non-regular 7z entry"
                    }
                    dest to folder
                }
            require(entries.any { !it.second }) { "Empty 7z archive" }
            var output: OutputStream? = null
            var failure: Exception? = null
            // JNI wraps callback exceptions. Retain the original so cancellation and limits
            // reach callers as CancellationException and IllegalArgumentException, respectively.
            fun <T> callback(action: () -> T): T {
                try {
                    failure?.let { throw it }
                    context.ensureActive()
                    return action()
                } catch (e: Exception) {
                    if (failure == null) failure = e
                    throw SevenZipException(e)
                }
            }
            try {
                archive.extract(
                    null,
                    false,
                    object : IArchiveExtractCallback {
                        override fun setTotal(total: Long) = callback { Unit }

                        override fun setCompleted(complete: Long) = callback { Unit }

                        override fun prepareOperation(mode: ExtractAskMode) = callback { Unit }

                        override fun getStream(
                            index: Int,
                            mode: ExtractAskMode,
                        ): ISequentialOutStream? = callback {
                            output?.close()
                            output = null
                            if (mode != ExtractAskMode.EXTRACT) return@callback null
                            val (dest, folder) = entries[index]
                            if (folder) {
                                Files.createDirectories(dest)
                                null
                            } else {
                                Files.createDirectories(dest.parent)
                                val stream =
                                    Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW)
                                output = stream
                                ISequentialOutStream { bytes ->
                                    callback {
                                        budget(bytes.size)
                                        stream.write(bytes)
                                        bytes.size
                                    }
                                }
                            }
                        }

                        override fun setOperationResult(result: ExtractOperationResult) = callback {
                            output?.close()
                            output = null
                            if (result != ExtractOperationResult.OK)
                                throw SevenZipException("7z extraction failed: $result")
                        }
                    },
                )
                failure?.let { throw it }
                context.ensureActive()
            } catch (e: Exception) {
                throw failure ?: e
            } finally {
                output?.close()
            }
        }
    }
}
