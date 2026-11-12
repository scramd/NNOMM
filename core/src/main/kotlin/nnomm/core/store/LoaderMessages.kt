package nnomm.core.store

/** Technical details stay in the log; these instructions also apply to loader removal. */
internal fun loaderFailureHelp(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    return when {
        causes.any { it is nnomm.core.io.LoaderNotInstalled } ->
            "The mod loader is not installed. Use Install to add BepInEx 5.4.23.4."
        causes.any { it is nnomm.core.io.LoaderInstallBlocked } ->
            causes.first { it is nnomm.core.io.LoaderInstallBlocked }.message!!
        causes.any { it is nnomm.core.io.LoaderRecoveryPending } ->
            "Changes in this game folder still need recovery. Restore access to the game folder, then Retry."
        causes.any { it is nnomm.core.io.LoaderRepairRefused } ->
            "Repair supports only BepInEx 5.4.23.4. Select a folder with that version, or restore the loader using its original installer. Existing files were kept."
        causes.any { it is java.nio.charset.CharacterCodingException } ->
            "doorstop_config.ini contains invalid UTF-16 text. Its bytes were kept. Restore a valid copy of the file, then try again."
        causes.any {
            it is java.io.IOException &&
                it.message.orEmpty().let { message ->
                    message.contains("no space left", true) ||
                        message.contains("not enough space", true) ||
                        message.contains("disk full", true) ||
                        message.contains("disk is full", true)
                }
        } -> "Free some space on the game and storage drives, then try again."
        causes.any { it is nnomm.core.io.DoorstopSettingsFailure } ->
            "Close programs that use doorstop_config.ini, check that NNOMM can read and change it, then try again."
        causes.any {
            it is java.nio.file.AccessDeniedException ||
                it is java.nio.file.FileSystemException &&
                    it.reason.orEmpty().let { reason ->
                        reason.contains("used by another process", true) ||
                            reason.contains("locked", true) ||
                            reason.contains("permission denied", true) ||
                            reason.contains("read-only file system", true)
                    }
        } ->
            "Check that NNOMM can change files in the game folder and in its own storage folder, and close programs using them, then try again."
        causes.any {
            it is java.io.FileNotFoundException &&
                it.message.orEmpty().contains("Permission denied", true)
        } ->
            "Check that NNOMM can change files in the game folder and in its own storage folder, and close programs using them, then try again."
        causes.any {
            it is java.util.zip.ZipException ||
                it.message.orEmpty().let { message ->
                    message.contains("SHA-256", true) ||
                        message.contains("verification", true) ||
                        message.contains("checksum", true)
                }
        } -> "The downloaded loader files could not be checked. Download them again with Retry."
        causes.any {
            it is nnomm.core.io.LoaderDownloadFailure ||
                it is javax.net.ssl.SSLException ||
                it is java.io.EOFException
        } -> "Check your connection and try again."

        causes.any {
            it is java.net.SocketException ||
                it is java.net.UnknownHostException ||
                it is java.net.http.HttpTimeoutException ||
                it is java.net.SocketTimeoutException ||
                it is io.ktor.client.network.sockets.ConnectTimeoutException ||
                it is io.ktor.client.plugins.ResponseException ||
                it is io.ktor.client.plugins.HttpRequestTimeoutException
        } -> "Check your connection and try again."
        else -> "Check the game folder and try again."
    }
}
