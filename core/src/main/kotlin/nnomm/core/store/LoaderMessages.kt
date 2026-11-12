package nnomm.core.store

/** Technical details stay in the log; these instructions also apply to loader removal. */
internal fun loaderFailureHelp(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    return when {
        causes.any {
            it is java.net.SocketException ||
                it is java.net.UnknownHostException ||
                it is java.net.http.HttpTimeoutException ||
                it is java.net.SocketTimeoutException ||
                it is io.ktor.client.network.sockets.ConnectTimeoutException ||
                it is io.ktor.client.plugins.ResponseException ||
                it is io.ktor.client.plugins.HttpRequestTimeoutException
        } -> "Check your connection and try again."
        causes.any {
            it is java.nio.file.AccessDeniedException ||
                it is java.nio.file.FileSystemException &&
                    it.reason.orEmpty().let { reason ->
                        reason.contains("used by another process", true) ||
                            reason.contains("locked", true) ||
                            reason.contains("permission denied", true)
                    }
        } ->
            "Check that NNOMM can change files in the game folder and close programs using them, then try again."
        causes.any {
            it is java.nio.file.FileSystemException &&
                it.reason.orEmpty().let { reason ->
                    reason.contains("space", true) || reason.contains("disk full", true)
                }
        } -> "Free some space on the game and storage drives, then try again."
        causes.any {
            it is java.util.zip.ZipException ||
                it.message.orEmpty().let { message ->
                    message.contains("SHA-256", true) ||
                        message.contains("verification", true) ||
                        message.contains("checksum", true)
                }
        } -> "The downloaded loader files could not be checked. Download them again with Retry."
        else -> "Check the game folder and try again."
    }
}
