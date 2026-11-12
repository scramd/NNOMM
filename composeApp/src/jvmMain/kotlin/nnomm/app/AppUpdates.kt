package nnomm.app

import dev.nucleusframework.updater.UpdateResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Optional updater work never blocks startup or mod management. */
class AppUpdates {
    suspend fun check(mode: String, testVersions: Boolean = false, manual: Boolean = false, status: (String) -> Unit, beforeInstall: suspend () -> Unit = {}) =
        withContext(Dispatchers.IO) {
            if (mode == "Never" && !manual) return@withContext
            runCatching {
                    val updater = createUpdater(testVersions)
                    if (!updater.isUpdateSupported()) {
                        if (manual) status("Automatic updates are unavailable for this build. Open the releases page to get NNOMM.")
                        return@runCatching
                    }
                    when (val result = updater.checkForUpdates()) {
                        is UpdateResult.Available -> {
                            if (manual || mode != "By itself" || requiresManualWindowsUpdate()) {
                                status(
                                    "An NNOMM update is available. Open About and updates to get it."
                                )
                            } else {
                                var file: File? = null
                                updater.downloadUpdate(result.info).collect { progress ->
                                    file = progress.file ?: file
                                }
                                file?.let { beforeInstall(); updater.installAndRestart(it) }
                            }
                        }
                        is UpdateResult.NotAvailable ->
                            if (manual) status("You have the newest version of NNOMM.")
                        is UpdateResult.Error -> if (manual) status("Could not check for NNOMM updates.")
                    }
                }
                .onFailure { if (manual) status("Could not check for NNOMM updates.") }
        }
}
