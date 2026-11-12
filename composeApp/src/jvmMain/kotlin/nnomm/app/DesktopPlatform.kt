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

    private val linux = System.getProperty("os.name").startsWith("Linux")

    override suspend fun chooseFiles(
        title: String,
        extensions: List<String>,
        multiple: Boolean,
    ): List<File> =
        withContext(Dispatchers.IO) {
            if (linux) return@withContext swingChooser(title, extensions = extensions, multiple = multiple)
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
            if (linux) return@withContext swingChooser(title, saveName = suggestedName, extensions = listOf(extension)).firstOrNull()
            FileKit.openFileSaver(
                    suggestedName = suggestedName.removeSuffix(".$extension"),
                    extension = extension,
                    dialogSettings = FileKitDialogSettings(title = title),
                )
                ?.let { File(it.path) }
        }

    override suspend fun chooseFolder(title: String): File? =
        withContext(Dispatchers.IO) {
            if (linux) return@withContext swingChooser(title, directory = true).firstOrNull()
            FileKit.openDirectoryPicker(dialogSettings = FileKitDialogSettings(title = title))?.let { File(it.path) }
        }

    /** Swing owns its event thread; Compose and GTK are never involved in the Linux chooser. */
    private suspend fun swingChooser(
        title: String,
        directory: Boolean = false,
        saveName: String? = null,
        extensions: List<String> = emptyList(),
        multiple: Boolean = false,
    ): List<File> = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<List<File>>()
        javax.swing.SwingUtilities.invokeLater {
            try {
                val chooser = javax.swing.JFileChooser().apply {
                    dialogTitle = title
                    fileSelectionMode = if (directory) javax.swing.JFileChooser.DIRECTORIES_ONLY else javax.swing.JFileChooser.FILES_ONLY
                    isMultiSelectionEnabled = multiple
                    if (extensions.isNotEmpty()) fileFilter = javax.swing.filechooser.FileNameExtensionFilter(extensions.joinToString(", "), *extensions.toTypedArray())
                    if (saveName != null) selectedFile = File(saveName)
                }
                val answer = if (saveName != null) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
                result.complete(if (answer != javax.swing.JFileChooser.APPROVE_OPTION) emptyList()
                    else if (multiple) chooser.selectedFiles.toList()
                    else listOf(chooser.selectedFile).map { file ->
                        if (saveName != null && extensions.isNotEmpty() && !file.name.endsWith(".${extensions.first()}", true)) File(file.path + ".${extensions.first()}") else file
                    })
            } catch (e: Exception) { result.completeExceptionally(e) }
        }
        result.await()
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
