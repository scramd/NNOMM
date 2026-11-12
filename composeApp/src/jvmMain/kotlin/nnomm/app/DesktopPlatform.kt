package nnomm.app

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.*
import io.github.vinceglb.filekit.path
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import kotlinx.coroutines.*
import nnomm.ui.Platform

/** Native dialogs suspend; desktop operations run on IO, never the Compose event thread. */
class DesktopPlatform : Platform, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun chooseFiles(
        title: String,
        extensions: List<String>,
        multiple: Boolean,
    ): List<File> =
        withContext(Dispatchers.IO) {
            if (multiple)
                FileKit.openFilePicker(
                        type = FileKitType.File(extensions),
                        mode = FileKitMode.Multiple(),
                        dialogSettings = FileKitDialogSettings(title = title),
                    )
                    ?.map { File(it.path) }
                    .orEmpty()
            else
                listOfNotNull(
                    FileKit.openFilePicker(
                            type = FileKitType.File(extensions),
                            dialogSettings = FileKitDialogSettings(title = title),
                        )
                        ?.let { File(it.path) }
                )
        }

    override suspend fun chooseSaveFile(
        title: String,
        suggestedName: String,
        extension: String,
    ): File? =
        withContext(Dispatchers.IO) {
            FileKit.openFileSaver(
                    suggestedName = suggestedName.removeSuffix(".$extension"),
                    extension = extension,
                    dialogSettings = FileKitDialogSettings(title = title),
                )
                ?.let { File(it.path) }
        }

    override suspend fun chooseFolder(title: String): File? =
        withContext(Dispatchers.IO) {
            FileKit.openDirectoryPicker(dialogSettings = FileKitDialogSettings(title = title))?.let { File(it.path) }
        }

    override fun openFolder(folder: File) {
        scope.launch { Desktop.getDesktop().open(folder) }
    }

    override fun openUrl(url: String) {
        scope.launch { Desktop.getDesktop().browse(URI(url)) }
    }

    override fun copyText(text: String) {
        scope.launch {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    override fun close() {
        scope.cancel()
    }
}
