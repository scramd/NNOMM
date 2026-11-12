package nnomm.app

import dev.nucleusframework.updater.UpdateResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Optional updater work never blocks startup or mod management. */
class AppUpdates {
    suspend fun check(mode: String, testVersions: Boolean = false, status: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            if (mode == "Never") return@withContext
            val updater = createUpdater(testVersions)
            runCatching {
                    if (!updater.isUpdateSupported()) {
                        status("Updates are available from the NNOMM releases page.")
                        return@runCatching
                    }
                    when (val result = updater.checkForUpdates()) {
                        is UpdateResult.Available -> {
                            if (mode != "By itself" || requiresManualWindowsUpdate()) {
                                status(
                                    "An NNOMM update is available. Open About and updates to get it."
                                )
                            } else {
                                var file: File? = null
                                updater.downloadUpdate(result.info).collect { progress ->
                                    file = progress.file ?: file
                                }
                                file?.let(updater::installAndRestart)
                            }
                        }
                        is UpdateResult.NotAvailable ->
                            status("You have the newest version of NNOMM.")
                        is UpdateResult.Error -> status("Could not check for NNOMM updates.")
                    }
                }
                .onFailure { status("Could not check for NNOMM updates.") }
        }
}
