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
                val chooser = (if (saveName != null) SaveFileChooser(extensions.firstOrNull()) else javax.swing.JFileChooser()).apply {
                    dialogTitle = title
                    fileSelectionMode = if (directory) javax.swing.JFileChooser.DIRECTORIES_ONLY else javax.swing.JFileChooser.FILES_ONLY
                    isMultiSelectionEnabled = multiple
                    if (extensions.isNotEmpty()) fileFilter = javax.swing.filechooser.FileNameExtensionFilter(extensions.joinToString(", "), *extensions.toTypedArray())
                    if (saveName != null) selectedFile = File(saveName)
                }
                val answer = if (saveName != null) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
                result.complete(if (answer != javax.swing.JFileChooser.APPROVE_OPTION) emptyList()
                    else if (multiple) chooser.selectedFiles.toList()
                    else listOf(chooser.selectedFile))
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

/** Resolve the name before confirming; a refusal leaves the chooser and its name intact. */
internal class SaveFileChooser(private val extension: String?) : javax.swing.JFileChooser() {
    override fun approveSelection() {
        val file = finalSaveFile(selectedFile ?: return, extension)
        selectedFile = file
        if (file.exists()) {
            if (!file.isFile) {
                javax.swing.JOptionPane.showMessageDialog(this, "Choose a file name, not a folder.", "Save file", javax.swing.JOptionPane.WARNING_MESSAGE)
                return
            }
            val answer = javax.swing.JOptionPane.showConfirmDialog(
                this,
                "${file.name} already exists. Replace it?",
                "Replace file?",
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE,
            )
            if (answer != javax.swing.JOptionPane.YES_OPTION) return
        }
        super.approveSelection()
    }
}

internal fun finalSaveFile(file: File, extension: String?): File =
    if (extension != null && !file.name.endsWith(".$extension", ignoreCase = true)) File(file.path + ".$extension") else file
