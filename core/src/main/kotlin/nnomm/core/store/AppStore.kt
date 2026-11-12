package nnomm.core.store

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import nnomm.core.io.*
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.core.settings.*

/**
 * UI collects [state]. Actions return immediately; all IO uses the supplied worker scope. Call
 * [close] when the application closes. Inject a scope to control scheduling in tests.
 */
class AppStore(initial: AppState, val io: AppIo, scope: CoroutineScope? = null) : AutoCloseable {
    private val worker =
        CoroutineScope(
            (scope?.coroutineContext ?: Dispatchers.IO) +
                SupervisorJob(scope?.coroutineContext?.get(Job))
        )
    private val closing = java.util.concurrent.atomic.AtomicBoolean()
    private val closed = CompletableDeferred<Unit>()
    private val mutable = MutableStateFlow(initial.copy(packSource = io.packSource))
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private val listInputs =
        state
            .map {
                it.copy(
                    downloads = emptyList(),
                    toasts = emptyList(),
                    releaseNotes = emptyMap(),
                    notesStatus = emptyMap(),
                    metadataStatus = emptyMap(),
                    storeUnusedMb = null,
                    storeUnusedKb = null,
                    storeScanStatus = LoadStatus.IDLE,
                    storeUsedBytes = 0,
                    gameStarting = false,
                )
            }
            .distinctUntilChanged()
    val libraryRows: StateFlow<List<LibraryRow>> =
        listInputs.map(::libraryView).stateIn(worker, SharingStarted.Eagerly, libraryView(initial))
    val libraryCounts: StateFlow<Map<String, Int>> =
        listInputs.map(::libCounts).stateIn(worker, SharingStarted.Eagerly, libCounts(initial))
    val browseRows: StateFlow<List<BrowseRow>> =
        listInputs.map(::browseList).stateIn(worker, SharingStarted.Eagerly, browseList(initial))
    val browseTagCounts: StateFlow<Map<String, Int>> =
        listInputs.map(::tagCounts).stateIn(worker, SharingStarted.Eagerly, tagCounts(initial))
    val sidebarNumbers: StateFlow<Pair<Int, Int>> =
        listInputs
            .map { onCount(it) to updates(it).size }
            .stateIn(worker, SharingStarted.Eagerly, onCount(initial) to updates(initial).size)
    private val lock = Any()
    private var sequence = 0L
    private val slots = Semaphore(3)
    private val metadataSlots = Semaphore(1)
    private val backgroundMetadataSlots = Semaphore(1)
    private val jobs = mutableMapOf<String, Job>()
    private val transfers = mutableMapOf<String, Deferred<Unit>>()
    private var appliedPlan: GamePlan? = null
    private var applyPending = false
    private var queuedLoaderRequest = false

    // Preserve disk mutation order, including an Undo issued before remove finishes.
    private val diskQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val diskWorker =
        worker.launch {
            for (operation in diskQueue) {
                try {
                    operation()
                    try {
                        updateStoreUsage()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        io.logger.error("store usage", e)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    io.logger.error("action", e)
                    toast("The action could not be completed. Your files have been kept.")
                }
            }
        }

    private fun disk(block: suspend () -> Unit) {
        if (!diskQueue.trySend(block).isSuccess) return
    }

    private suspend fun <T> diskAwait(block: suspend () -> T): T {
        if (closing.get()) throw CancellationException("Store is closing")
        val caller = currentCoroutineContext()
        val result = CompletableDeferred<T>()
        val sent =
            diskQueue.trySend {
                try {
                    caller.ensureActive()
                    result.complete(block())
                } catch (e: Exception) {
                    result.completeExceptionally(e)
                }
            }
        if (!sent.isSuccess) throw CancellationException("Store is closed")
        return result.await()
    }

    private val conflictObserver =
        io.gameFolder.placementConflicts?.let { flow ->
            worker.launch {
                flow.collect { value -> change { it.copy(placementConflicts = value) } }
            }
        }
    private val placementObserver =
        io.gameFolder.applyProblems?.let { flow ->
            worker.launch { flow.collect { value -> change { it.copy(applyProblems = value) } } }
        }
    private val launcherObserver =
        worker.launch {
            io.launcher.running.collect { running ->
                val wasRunning = state.value.gameRunning
                change {
                    it.copy(
                        gameRunning = running,
                        gameStarting = if (running) false else it.gameStarting,
                    )
                }
                if (wasRunning && !running) {
                    disk {
                        io.gameFolder.recoverPending()
                        change { it.copy(gameIoPending = io.gameFolder.recoveryPending) }
                        if (state.value.deferredModChange) {
                            try {
                                prepareGameFolder(state.value, modAction = false)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                io.logger.error("deferred apply", e)
                                toast(
                                    "Could not apply mod changes. Your files have been kept.",
                                    "Retry",
                                    undo = { retryApply() },
                                )
                            }
                        }
                    }
                }
            }
        }
    private val metadataRequested = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val catalogMetadata =
        worker.launch {
            state
                .map { s -> s.catalog.map { it.id to it.versions.map { v -> v.version } } }
                .distinctUntilChanged()
                .collect {
                    yield()
                    loadMetadata(backgroundLoad = true)
                }
        }

    private var saveFailed = false
    @OptIn(FlowPreview::class)
    private val persistenceJob =
        io.persistence?.let { persistence ->
            worker.launch {
                state.debounce(350).collect { snapshot ->
                    try {
                        persistence.save(snapshot)
                        saveFailed = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        /* Report once; avoid a toast/save failure loop. */
                        if (!saveFailed) {
                            saveFailed = true
                            io.logger.error("save", e)
                            toast("Could not save your changes.", ms = 30000)
                        }
                    }
                }
            }
        }

    internal suspend fun awaitDiskIdle() {
        diskAwait {}
    }

    suspend fun flush() {
        io.persistence?.save(state.value)
        io.settingsFile.write(state.value.settings)
    }

    private fun change(f: (AppState) -> AppState) {
        synchronized(lock) { mutable.value = f(mutable.value) }
    }

    private fun background(block: suspend () -> Unit) =
        worker.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                io.logger.error("action", e)
                toast("The action could not be completed. Your files have been kept.")
            }
        }

    fun toast(
        text: String,
        action: String? = null,
        target: UiAction? = null,
        ms: Long = 7000,
        undo: (() -> Unit)? = null,
    ) {
        val id = synchronized(lock) { ++sequence }
        change {
            it.copy(toasts = (it.toasts + Toast(id, text, action, target, ms, undo)).takeLast(3))
        }
        worker.launch {
            delay(ms)
            change { it.copy(toasts = it.toasts.filterNot { t -> t.id == id }) }
        }
    }

    fun dismissToast(id: Long) {
        change { it.copy(toasts = it.toasts.filterNot { t -> t.id == id }) }
    }

    fun runToast(id: Long): UiAction? {
        val t =
            synchronized(lock) {
                val found = state.value.toasts.find { it.id == id } ?: return null
                dismissToast(id)
                found
            }
        t.callback?.invoke()
        return t.target
    }

    fun undo() {
        state.value.toasts.lastOrNull { it.action == "Undo" }?.let { runToast(it.id) }
    }

    private fun setProfileMod(
        s: AppState,
        id: String,
        p: String,
        on: Boolean? = null,
        version: String? = null,
    ): AppState {
        val m = s.installed[id] ?: return s
        val old = m.profiles[p] ?: ProfileMod(false, m.defaultVersion)
        val v = version ?: old.version
        return s.copy(
            installed =
                s.installed +
                    (id to
                        m.copy(
                            versions = m.versions + v,
                            profiles = m.profiles + (p to old.copy(on = on ?: old.on, version = v)),
                        ))
        )
    }

    private fun resolvedPlan(s: AppState) = gamePlan(s)

    private class LoaderInstallFailure(cause: Exception) : Exception(cause)

    private suspend fun prepareGameFolder(snapshot: AppState, modAction: Boolean = true) {
        val folder = snapshot.game.folder ?: return
        if (snapshot.game.folderMissing) return
        if (io.launcher.running.value || state.value.gameRunning) {
            change { it.copy(deferredModChange = true) }
            io.persistence?.save(state.value)
            return
        }
        if (modAction && onCount(snapshot) > 0 && !snapshot.game.loaderPresent) {
            try {
                io.gameFolder.installLoader(folder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw LoaderInstallFailure(e)
            }
            val game = io.gameFolder.inspect(folder)
            change { it.copy(game = game) }
            toast("Installed the mod loader BepInEx 5.4.23.4. Mods need it to run.")
        }
        io.gameFolder.apply(gamePlan(snapshot))
        if (io.gameFolder.applySucceeded) {
            appliedPlan = resolvedPlan(snapshot)
            change { it.copy(deferredModChange = false) }
        } else {
            appliedPlan = null
            error("Some changes are pending. Retry or take over existing mods before Play.")
        }
    }

    private fun applyAndSave(force: Boolean = false, allowLoader: Boolean = false) {
        if (io.persistence == null) background { io.settingsFile.write(state.value.settings) }
        synchronized(lock) {
            queuedLoaderRequest = queuedLoaderRequest || allowLoader
            if (applyPending) return
            if (
                !force &&
                    resolvedPlan(state.value) == appliedPlan &&
                    (!queuedLoaderRequest ||
                        state.value.game.loaderPresent ||
                        onCount(state.value) == 0)
            ) {
                queuedLoaderRequest = false
                return
            }
            applyPending = true
            disk {
                val installLoader =
                    synchronized(lock) {
                        applyPending = false
                        queuedLoaderRequest.also { queuedLoaderRequest = false }
                    }
                try {
                    prepareGameFolder(state.value, modAction = installLoader)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    change { it.copy(deferredModChange = true) }
                    io.persistence?.save(state.value)
                    io.logger.error("apply", e)
                    toast(
                        "Could not apply mod changes. Your files have been kept.",
                        "Retry",
                        undo = { applyAndSave(force = true, allowLoader = installLoader) },
                    )
                }
            }
        }
    }

    private fun queue(
        id: String,
        kind: DownloadKind,
        p: String = state.value.profileId,
        version: String? = null,
        quiet: Boolean = false,
        with: List<String> = emptyList(),
    ) {
        synchronized(lock) {
            val s = state.value
            val requested = version ?: availableVersion(s, id)?.version
            if (requested == null) {
                toast("No version of ${s.mod(id).name} is available")
                return
            }
            if (
                requested in s.installed[id]?.versions.orEmpty() &&
                    s.installed[id]?.fileMissing == false &&
                    kind !in setOf(DownloadKind.REPAIR, DownloadKind.REPAIRPROBLEM)
            ) {
                background {
                    if (!diskAwait { io.modStore.hasVersion(id, requested) }) {
                        toast("Version $requested is not available")
                        return@background
                    }
                    change {
                        setProfileMod(
                            it,
                            id,
                            p,
                            on = if (kind == DownloadKind.INSTALL) true else null,
                            version = requested,
                        )
                    }
                    applyAndSave(
                        allowLoader = kind == DownloadKind.INSTALL && p == state.value.profileId
                    )
                }
                return
            }
            if (s.mod(id).versions.none { it.version == requested }) {
                toast("Version $requested is not available")
                return
            }
            val key = "$id/$requested/$p"
            if (jobs[key]?.isActive == true) return
            val x = s.mod(id)
            val requestedFile =
                x.versions
                    .find { it.version == requested }
                    ?.let { it.fileName ?: it.downloadUrl?.substringBefore('?') }
                    .orEmpty()
                    .lowercase()
            if (requestedFile.endsWith(".rar")) {
                toast("This version uses an archive format NNOMM cannot install yet.")
                return
            }
            val v = requested
            val d = Download(id, kind, p, v, quiet = quiet, withDependencies = with)
            change {
                it.copy(
                    downloads =
                        it.downloads.filterNot {
                            it.id == id && it.profile == p && it.version == v
                        } + d
                )
            }
            val job =
                worker.launch(start = CoroutineStart.LAZY) {
                    try {
                        val transferKey = "$id/$v"
                        val transfer =
                            synchronized(lock) {
                                transfers.getOrPut(transferKey) {
                                    worker
                                        .async(start = CoroutineStart.LAZY) {
                                            slots.withPermit {
                                                val artifact = x.versions.first { it.version == v }
                                                var lastProgress = Long.MIN_VALUE
                                                var lastPercent = -1
                                                val file =
                                                    io.downloader.download(x, artifact) { pct ->
                                                        val now = System.nanoTime()
                                                        val percent = pct.coerceIn(0, 99)
                                                        if (
                                                            percent != lastPercent &&
                                                                (lastProgress == Long.MIN_VALUE ||
                                                                    now - lastProgress >=
                                                                        100_000_000L)
                                                        ) {
                                                            lastProgress = now
                                                            lastPercent = percent
                                                            change { st ->
                                                                st.copy(
                                                                    downloads =
                                                                        st.downloads.map {
                                                                            if (
                                                                                it.id == id &&
                                                                                    it.version == v
                                                                            )
                                                                                it.copy(
                                                                                    percent =
                                                                                        percent
                                                                                )
                                                                            else it
                                                                        }
                                                                )
                                                            }
                                                        }
                                                    }
                                                try {
                                                    diskAwait {
                                                        ensureActive()
                                                        io.modStore.put(id, v, file)
                                                    }
                                                } finally {
                                                    file.close()
                                                }
                                            }
                                        }
                                        .also { transfer ->
                                            transfer.invokeOnCompletion {
                                                synchronized(lock) {
                                                    if (transfers[transferKey] === transfer)
                                                        transfers.remove(transferKey)
                                                }
                                            }
                                        }
                                }
                            }
                        transfer.await()
                        ensureActive()
                        run {
                            var finished = false
                            change { st ->
                                if (
                                    st.profiles.none { it.id == p } ||
                                        ((kind == DownloadKind.UPDATE ||
                                            kind in
                                                setOf(
                                                    DownloadKind.REPAIR,
                                                    DownloadKind.REPAIRPROBLEM,
                                                )) && id !in st.installed)
                                )
                                    return@change st.copy(
                                        downloads =
                                            st.downloads.map {
                                                if (
                                                    it.id == id &&
                                                        it.profile == p &&
                                                        it.version == v
                                                )
                                                    it.copy(percent = 100)
                                                else it
                                            }
                                    )
                                finished = true
                                val next =
                                    if (kind == DownloadKind.UPDATE)
                                        setProfileMod(st, id, p, version = v)
                                    else if (
                                        kind in
                                            setOf(DownloadKind.REPAIR, DownloadKind.REPAIRPROBLEM)
                                    ) {
                                        val m = st.installed[id]
                                        if (m == null) st
                                        else
                                            st.copy(
                                                installed =
                                                    st.installed +
                                                        (id to m.copy(fileMissing = false))
                                            )
                                    } else {
                                        val old = st.installed[id]
                                        val m =
                                            old
                                                ?: InstalledMod(
                                                    id,
                                                    versions = setOf(v),
                                                    defaultVersion = v,
                                                    profiles =
                                                        st.profiles.associate {
                                                            it.id to ProfileMod(false, v)
                                                        },
                                                )
                                        setProfileMod(
                                            st.copy(
                                                installed =
                                                    st.installed +
                                                        (id to m.copy(fileMissing = false))
                                            ),
                                            id,
                                            p,
                                            true,
                                            v,
                                        )
                                    }
                                next.copy(
                                    downloads =
                                        next.downloads.map {
                                            if (it.id == id && it.profile == p && it.version == v)
                                                it.copy(percent = 100)
                                            else it
                                        }
                                )
                            }
                            if (finished && !quiet) {
                                if (kind in setOf(DownloadKind.REPAIR, DownloadKind.REPAIRPROBLEM))
                                    toast(
                                        if (kind == DownloadKind.REPAIRPROBLEM) "Repaired ${x.name}"
                                        else "Downloaded ${x.name} again"
                                    )
                                else if (kind == DownloadKind.UPDATE)
                                    toast(
                                        "Updated ${x.name} to ${v}",
                                        "What's new",
                                        UiAction("openMod", id, "new"),
                                    )
                                else
                                    toast(
                                        "Installed ${x.name} and turned it on in ${state.value.profile(p).name}",
                                        "Undo",
                                        undo = { uninstallSilently(listOf(id) + with) },
                                    )
                            }
                            applyAndSave(
                                allowLoader =
                                    kind == DownloadKind.INSTALL && p == state.value.profileId,
                                force =
                                    kind in setOf(DownloadKind.REPAIR, DownloadKind.REPAIRPROBLEM),
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        change {
                            it.copy(
                                downloads =
                                    it.downloads.map { d ->
                                        if (d.id == id && d.profile == p && d.version == v)
                                            d.copy(
                                                error =
                                                    "Download failed. Retry when you have a connection."
                                            )
                                        else d
                                    }
                            )
                        }
                        io.logger.error("download", e)
                        toast("Could not download ${x.name}.")
                    }
                }
            jobs[key] = job
            job.invokeOnCompletion {
                synchronized(lock) { if (jobs[key] === job) jobs.remove(key) }
            }
            job.start()
        }
    }

    fun install(id: String, quiet: Boolean = false) {
        val s = state.value
        if (s.connection.kind == ConnectionKind.OFFLINE) return
        val version =
            availableVersion(s, id)
                ?: run {
                    toast("No version of ${s.mod(id).name} is available")
                    return
                }
        val deps = dependencyClosure(s, id, version.version)
        val need =
            if (s.settings.bool("autoDeps", s.profileId)) deps.filter { it.id !in s.installed }
            else emptyList()
        need.forEach { queue(it.id, DownloadKind.INSTALL, s.profileId, it.version, quiet = true) }
        queue(
            id,
            DownloadKind.INSTALL,
            s.profileId,
            version.version,
            quiet = quiet,
            with = need.map { it.id },
        )
        if (need.isNotEmpty() && !quiet)
            toast(
                "${s.mod(id).name} needs ${need.joinToString(" and "){s.mod(it.id).name}}. Installing both."
            )
        deps
            .filter { it.id in s.installed && !isOn(s, it.id) }
            .forEach { setOn(listOf(it.id), true, s.profileId) }
    }

    fun update(id: String) {
        if (state.value.connection.kind != ConnectionKind.OFFLINE) queue(id, DownloadKind.UPDATE)
    }

    fun updateAll() {
        updates(state.value).forEach(::update)
    }

    fun lookForUpdates() {
        refreshRegistry(announce = true)
    }

    fun paletteToggle(id: String) {
        toggle(id)
        val s = state.value
        toast(
            "${s.mod(id).name} is ${if(isOn(s,id))"on" else "off"}" +
                if (s.settings.bool("showProfiles") && s.profiles.size > 1)
                    " in ${s.profile().name}"
                else ""
        )
    }

    fun cancelDownload(id: String) {
        synchronized(lock) {
            transfers.filterKeys { it.startsWith("$id/") }.values.toList().forEach { it.cancel() }
        }
        synchronized(lock) {
            jobs.filterKeys { it.startsWith("$id/") }.values.toList().forEach { it.cancel() }
        }
        change { it.copy(downloads = it.downloads.filterNot { it.id == id }) }
    }

    fun clearFinished() {
        change { it.copy(downloads = it.downloads.filter { it.percent < 100 && it.error == null }) }
    }

    fun setOn(ids: List<String>, on: Boolean, p: String = state.value.profileId) {
        val before = state.value
        change { s -> ids.fold(s) { acc, id -> setProfileMod(acc, id, p, on) } }
        if (ids.size > 1)
            toast(
                "${if(on)"Turned on" else "Turned off"} ${ids.size} mods",
                "Undo",
                undo = {
                    change { s ->
                        ids.fold(s) { acc, id -> setProfileMod(acc, id, p, isOn(before, id, p)) }
                    }
                    applyAndSave()
                },
            )
        applyAndSave(allowLoader = on && p == state.value.profileId)
    }

    fun toggle(id: String, p: String = state.value.profileId) =
        setOn(listOf(id), !isOn(state.value, id, p), p)

    private fun uninstallSilently(ids: List<String>) {
        io.modStore.protect(ids.toSet())
        val keepForRemoval = state.value.installed.filterKeys { it in ids }
        ids.forEach(::cancelDownload)
        change { it.copy(installed = it.installed - ids.toSet()) }
        disk {
            try {
                io.modStore.remove(ids)
            } catch (e: Exception) {
                change {
                    it.copy(
                        installed = it.installed + keepForRemoval,
                        toasts = it.toasts.filterNot { t -> t.text.startsWith("Uninstalled ") },
                    )
                }
                io.logger.error("uninstall", e)
                toast(
                    "Could not uninstall the selected mods. They are still in your Library.",
                    "Retry",
                    undo = { uninstall(ids) },
                )
            }
        }
        applyAndSave()
    }

    fun uninstall(ids: List<String>) {
        if (ids.isEmpty()) return
        val s = state.value
        val keep = s.installed.filterKeys { it in ids }
        val where =
            if (ids.size == 1 && s.settings.bool("showProfiles") && s.profiles.size > 1)
                usedIn(s, ids[0])
            else emptyList()
        uninstallSilently(ids)
        val text =
            if (ids.size == 1)
                "Uninstalled ${s.mod(ids[0]).name}" +
                    (if (where.isNotEmpty()) ". It was on in ${where.joinToString(" and ")}"
                    else "")
            else
                "Uninstalled ${ids.size} mods" +
                    (if (s.settings.bool("showProfiles") && s.profiles.size > 1)
                        " from all profiles"
                    else "")
        toast(text, "Undo", ms = 8000, undo = { restoreRemoved(ids, keep) })
    }

    private fun restoreRemoved(ids: List<String>, keep: Map<String, InstalledMod>) {
        disk {
            try {
                for (m in keep.values) for (v in m.versions) require(
                    io.modStore.hasVersion(m.id, v)
                ) {
                    "Stored files are missing for ${m.id}."
                }
                io.modStore.restore(ids)
                change { it.copy(installed = it.installed + keep) }
                applyAndSave()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                io.logger.error("restore", e)
                toast(
                    "Could not restore the selected mods. Their files have been kept.",
                    "Retry",
                    undo = { restoreRemoved(ids, keep) },
                )
            }
        }
    }

    fun changeVersion(id: String, version: String, p: String = state.value.profileId) {
        val s = state.value
        if (verOf(s, id, p) == version) return
        if (
            s.mod(id).versions.none { it.version == version } &&
                version !in s.installed[id]?.versions.orEmpty()
        ) {
            toast("Version $version is not available")
            return
        }
        queue(id, DownloadKind.UPDATE, p, version, true)
        toast(
            "Changing ${s.mod(id).name} to $version" +
                if (s.settings.bool("showProfiles") && s.profiles.size > 1)
                    " in ${s.profile(p).name}"
                else ""
        )
    }

    fun repair(id: String, fromProblem: Boolean = false) {
        queue(
            id,
            if (fromProblem) DownloadKind.REPAIRPROBLEM else DownloadKind.REPAIR,
            version = verOf(state.value, id),
        )
    }

    fun useProfile(id: String) {
        if (state.value.trouble != null) {
            toast(
                "Finish or stop the broken-mod test before you change the profile.",
                "Show the test",
                UiAction("trouble"),
            )
            return
        }
        if (state.value.profiles.none { it.id == id }) return
        change { it.copy(profileId = id) }
        applyAndSave()
    }

    fun newProfile(
        name: String = "",
        copyCurrent: Boolean =
            state.value.settings.general["newFrom"]?.content != "With all mods off",
        ownSettings: Boolean = state.value.settings.bool("ownSettings"),
    ): String {
        val id = java.util.UUID.randomUUID().toString()
        val from = state.value.profileId
        if (state.value.trouble != null) stopBrokenModTest()
        val p = Profile(id, name.trim().ifEmpty { "New profile" })
        change { s ->
            s.copy(
                profiles = s.profiles + p,
                profileId = id,
                installed =
                    s.installed.mapValues { (_, m) ->
                        m.copy(
                            profiles =
                                m.profiles +
                                    (id to
                                        ProfileMod(
                                            copyCurrent && (m.profiles[from]?.on == true),
                                            if (copyCurrent)
                                                m.profiles[from]?.version ?: m.defaultVersion
                                            else m.defaultVersion,
                                        ))
                        )
                    },
                settings =
                    s.settings.copy(
                        overrides =
                            s.settings.overrides +
                                (id to
                                    ((if (copyCurrent) s.settings.overrides[from].orEmpty()
                                    else emptyMap()) +
                                        ("ownSettingsP" to JsonPrimitive(ownSettings))))
                    ),
            )
        }
        toast("Made the profile ${p.name}")
        applyAndSave()
        return id
    }

    fun copyProfile(id: String): String {
        if (state.value.trouble != null) stopBrokenModTest()
        val s = state.value
        val p = s.profile(id)
        val new = java.util.UUID.randomUUID().toString()
        change {
            it.copy(
                profiles = it.profiles + Profile(new, p.name + " copy"),
                profileId = new,
                installed =
                    it.installed.mapValues { (_, m) ->
                        m.copy(
                            profiles =
                                m.profiles +
                                    (new to (m.profiles[id] ?: ProfileMod(false, m.defaultVersion)))
                        )
                    },
                settings =
                    it.settings.copy(
                        overrides =
                            it.settings.overrides + (new to it.settings.overrides[id].orEmpty())
                    ),
            )
        }
        toast("Made ${p.name} copy")
        applyAndSave()
        return new
    }

    fun renameProfile(id: String, name: String) {
        change {
            it.copy(
                profiles =
                    it.profiles.map { p ->
                        if (p.id == id) p.copy(name = name.trim().ifEmpty { p.name }) else p
                    }
            )
        }
    }

    fun deleteProfile(id: String) {
        if (id == "default") return
        if (state.value.trouble != null) stopBrokenModTest()
        val s = state.value
        val p = s.profile(id)
        val index = s.profiles.indexOf(p)
        change {
            it.copy(
                profiles = it.profiles - p,
                profileId = if (it.profileId == id) "default" else it.profileId,
            )
        }
        toast(
            "Deleted the profile ${p.name}. Its mods stay installed.",
            "Undo",
            undo = {
                change { st ->
                    st.copy(
                        profiles =
                            st.profiles.toMutableList().apply { add(index.coerceAtMost(size), p) }
                    )
                }
                applyAndSave()
            },
        )
        worker.launch {
            delay(7000)
            change { st ->
                if (st.profiles.any { it.id == id }) st
                else
                    st.copy(
                        installed =
                            st.installed.mapValues { (_, m) -> m.copy(profiles = m.profiles - id) },
                        settings = st.settings.copy(overrides = st.settings.overrides - id),
                    )
            }
        }
        applyAndSave()
    }

    private fun saveSettings() {
        if (io.persistence == null) background { io.settingsFile.write(state.value.settings) }
    }

    private var gamePathRevision = 0L

    fun setSetting(key: String, value: JsonPrimitive, profile: String? = null) {
        if (key == "gamePath") {
            val revision =
                synchronized(lock) {
                    gamePathRevision++
                    change { nnomm.core.settings.setSetting(it, key, value, profile) }
                    gamePathRevision
                }
            saveSettings()
            background {
                val game =
                    if (value.content.isBlank()) GameInfo()
                    else io.gameFolder.inspect(value.content)
                synchronized(lock) {
                    if (revision == gamePathRevision) change { it.copy(game = game) }
                }
            }
        } else {
            change { nnomm.core.settings.setSetting(it, key, value, profile) }
            saveSettings()
        }
    }

    fun ovset(key: String, p: String = state.value.profileId) {
        change { nnomm.core.settings.ovset(it, key, p) }
        saveSettings()
    }

    fun ovreset(key: String, p: String = state.value.profileId) {
        change { nnomm.core.settings.ovreset(it, key, p) }
        saveSettings()
    }

    fun ovclear(p: String = state.value.profileId) {
        change { nnomm.core.settings.ovclear(it, p) }
        saveSettings()
    }

    fun play(withoutMods: Boolean = false) {
        val s = state.value
        if (s.gameRunning || s.gameStarting) return
        if (s.game.folder == null || s.game.folderMissing) {
            toast("Choose the game folder in Settings first.")
            return
        }
        change { it.copy(gameStarting = true) }
        toast(
            if (withoutMods) "Starting Nuclear Option without mods. Your profile stays as it is."
            else "Starting Nuclear Option with ${onCount(s)} mods",
            ms = 4000,
        )
        background {
            var applying = !withoutMods
            try {
                diskAwait {
                    if (
                        !withoutMods &&
                            (resolvedPlan(state.value) != appliedPlan ||
                                (onCount(state.value) > 0 && !state.value.game.loaderPresent))
                    )
                        prepareGameFolder(state.value)
                    applying = false
                    val current = state.value
                    current.game.folder?.let {
                        io.gameFolder.setConsole(
                            it,
                            current.settings.bool("console", current.profileId),
                        )
                    }
                    io.launcher.start(current, withoutMods)
                }
                delay(60000)
                if (state.value.gameStarting && !state.value.gameRunning)
                    toast("Nuclear Option did not start. Try Play again.")
                change { it.copy(gameStarting = false) }
            } catch (e: Exception) {
                change { it.copy(gameStarting = false) }
                if (e is CancellationException) throw e
                io.logger.error("play", e)
                if (e is LoaderInstallFailure) {
                    toast(
                        "Could not install the mod loader. ${loaderFailureHelp(e.cause ?: e)}",
                        "Retry",
                        ms = 30000,
                        undo = { play(withoutMods) },
                    )
                    return@background
                }
                val failures =
                    if (applying) io.gameFolder.applyProblems?.value.orEmpty() else emptyMap()
                val failedProfile = state.value.profileId
                val reason =
                    failures.values.distinct().joinToString(" ").ifBlank {
                        if (applying)
                            "Some changes are pending. Retry or take over existing mods before Play."
                        else
                            when (e.message) {
                                "Doorstop's enabled setting was not found." ->
                                    "The mod loader’s settings file is damaged. Use Repair for the mod loader in Settings, Game."
                                "Nuclear Option is already running." ->
                                    "Nuclear Option is already running."
                                else -> "Check that the game is installed and try Play again."
                            }
                    }
                toast(
                    "Nuclear Option did not start. $reason",
                    "Retry",
                    ms = 30000,
                    undo = { play(withoutMods) },
                )
                val turnOff = failures.filterValues { !it.contains("same game folder name") }.keys
                if (turnOff.isNotEmpty())
                    toast(
                        "Turn off the mods with failed changes to continue with the other mods.",
                        "Turn off",
                        ms = 30000,
                        undo = { setOn(turnOff.toList(), false, failedProfile) },
                    )
            }
        }
    }

    fun gameClosed() {
        change { it.copy(gameRunning = io.launcher.running.value) }
    }

    fun startBrokenModTest() {
        if (state.value.trouble != null) return
        if (io.persistence == null) {
            change { startTrouble(it) }
            applyAndSave()
        } else
            disk {
                if (state.value.trouble != null) return@disk
                val next = startTrouble(state.value)
                // Save the original set and session before publishing temporary switches.
                io.persistence.save(next)
                change { next }
                prepareGameFolder(next)
            }
    }

    fun answerBrokenModTest(stillThere: Boolean) {
        change { troubleAnswer(it, stillThere) }
        if (state.value.trouble?.phase == TroublePhase.INCONCLUSIVE)
            toast(
                "These mods need each other, so the test cannot split them: " +
                    state.value.trouble!!.suspects.joinToString { state.value.mod(it).name },
                ms = 30000,
            )
        applyAndSave()
    }

    fun stopBrokenModTest() {
        change { restoreTrouble(it) }
        toast("Your mods are on again, as before")
        applyAndSave()
    }

    fun endBrokenModTest(choice: TroubleChoice = TroubleChoice.OFF) {
        val t = state.value.trouble ?: return
        val id = t.found
        change { restoreTrouble(it) }
        if (id != null && choice == TroubleChoice.OFF) setOn(listOf(id), false, t.profile)
        if (id == null) return
        if (choice == TroubleChoice.KEEP) setOn(listOf(id), true, t.profile)
        if (choice == TroubleChoice.REMOVE) uninstall(listOf(id))
        else
            toast(
                if (choice == TroubleChoice.OFF) "${state.value.mod(id).name} stays off"
                else "${state.value.mod(id).name} is on again"
            )
    }

    fun closeBrokenModTest() {
        if (state.value.trouble?.phase == TroublePhase.INCONCLUSIVE) {
            stopBrokenModTest()
            return
        }
        if (state.value.trouble?.phase == TroublePhase.NOMOD) change { it.copy(trouble = null) }
        else if (state.value.trouble?.phase == TroublePhase.FOUND) endBrokenModTest()
    }

    fun importPack(
        pack: Pack,
        mode: ImportMode = ImportMode.NEW,
        keep: Map<String, String> = emptyMap(),
        newProfileId: String = java.util.UUID.randomUUID().toString(),
        packSettings: SettingsValues? = null,
    ) {
        if (state.value.connection.kind == ConnectionKind.OFFLINE) {
            toast("Needs a connection")
            return
        }
        if (state.value.trouble != null) stopBrokenModTest()
        val rows =
            importRows(state.value, pack, mode).filterNot {
                it.local ||
                    it.kind == PackKind.UNAVAILABLE ||
                    (it.packVersion == null && it.kind != PackKind.SAME)
            }
        if (mode == ImportMode.NEW) {
            val id = newProfileId
            change {
                it.copy(
                    profiles =
                        it.profiles +
                            Profile(
                                id,
                                pack.name,
                                PackInfo(
                                    pack.by,
                                    pack.version,
                                    mods = rows.map { it.id },
                                    followAddress = pack.followAddress,
                                    memberVersions =
                                        rows
                                            .mapNotNull { row ->
                                                row.packVersion?.let { row.id to it }
                                            }
                                            .toMap(),
                                ),
                            ),
                    profileId = id,
                    settings =
                        if (packSettings == null) it.settings
                        else
                            it.settings.copy(
                                overrides =
                                    it.settings.overrides +
                                        (id to
                                            (packSettings.general +
                                                ("ownSettingsP" to JsonPrimitive(true))))
                            ),
                )
            }
        }
        var n = 0
        rows.forEach { r ->
            if (r.kind == PackKind.ADD) {
                queue(r.id, DownloadKind.INSTALL, version = r.packVersion, quiet = true)
                n++
            } else {
                setOn(listOf(r.id), true)
                if (
                    r.kind == PackKind.UP ||
                        r.kind == PackKind.OTHER ||
                        (r.kind == PackKind.CONFLICT && keep[r.id] == "pack")
                ) {
                    queue(r.id, DownloadKind.UPDATE, version = r.packVersion, quiet = true)
                    n++
                }
            }
        }
        toast(
            "Importing ${pack.name}" + if (n == 0) "" else ": ${plural(n,"download","downloads")}"
        )
    }

    /** Keep the profile and its versions, but no longer fetch updates for this pack. */
    fun stopFollowingPack(profileId: String = state.value.profileId) {
        change { s ->
            s.copy(
                profiles =
                    s.profiles.map { profile ->
                        if (profile.id == profileId)
                            profile.copy(
                                pack = profile.pack?.copy(followAddress = null, nextVersion = null)
                            )
                        else profile
                    }
            )
        }
        saveSettings()
        toast("Stopped following this pack. Its mods and versions stay in this profile.")
    }

    fun checkPackUpdates() {
        val source = io.packSource ?: return
        state.value.profiles.forEach { profile ->
            val info = profile.pack
            if (info?.followAddress != null)
                background {
                    val next = source.following(info)
                    change { s ->
                        s.copy(
                            profiles =
                                s.profiles.map { p ->
                                    if (
                                        p.id == profile.id &&
                                            p.pack?.followAddress == info.followAddress
                                    )
                                        p.copy(pack = p.pack.copy(nextVersion = next?.version))
                                    else p
                                }
                        )
                    }
                }
        }
    }

    fun updatePack(pack: Pack) {
        if (state.value.trouble != null) stopBrokenModTest()
        if (state.value.connection.kind == ConnectionKind.OFFLINE) {
            toast("Needs a connection")
            return
        }
        val p = state.value.profile()
        val info = p.pack ?: return
        var managed = info.mods
        val items = packUpdateItems(state.value, pack)
        items.forEach { i ->
            if (
                state.value.installed[i.id]?.source == ModSource.LOCAL ||
                    state.value.catalog.find { it.id == i.id }?.localFile != null ||
                    i.kind == PackKind.LOCAL
            )
                return@forEach
            when (i.kind) {
                PackKind.ADD -> {
                    if (i.id in state.value.installed) setOn(listOf(i.id), true)
                    else
                        queue(
                            i.id,
                            DownloadKind.INSTALL,
                            version = i.nextVersion ?: i.version,
                            quiet = true,
                        )
                    if (i.id !in managed) managed = managed + i.id
                }
                PackKind.REM -> {
                    setOn(listOf(i.id), false)
                    managed = managed - i.id
                }
                PackKind.CHG ->
                    queue(i.id, DownloadKind.UPDATE, version = i.nextVersion, quiet = true)
                else -> Unit
            }
        }
        val v = info.nextVersion ?: pack.version
        change {
            it.copy(
                profiles =
                    it.profiles.map { q ->
                        if (q.id == p.id)
                            q.copy(
                                pack =
                                    info.copy(
                                        version = v,
                                        nextVersion = null,
                                        mods = managed,
                                        memberVersions =
                                            managed.associateWith { id ->
                                                items
                                                    .firstOrNull { it.id == id }
                                                    ?.let {
                                                        if (it.kind == PackKind.NEWER)
                                                            it.version.orEmpty()
                                                        else
                                                            (it.nextVersion ?: it.version).orEmpty()
                                                    }
                                                    ?: info.memberVersions[id]
                                                    ?: verOf(state.value, id).orEmpty()
                                            },
                                    )
                            )
                        else q
                    }
            )
        }
        toast("${p.name} is now $v")
    }

    fun shareProfile(
        name: String = state.value.profile().name,
        version: String = "1.0",
        by: String = "",
        includeLocal: Boolean = false,
        includeSettings: Boolean = false,
        followAddress: String? = null,
    ): Pack {
        val s = state.value
        return Pack(
            name,
            by,
            version,
            s.installed.keys
                .filter {
                    isOn(s, it) && (includeLocal || s.installed[it]?.source != ModSource.LOCAL)
                }
                .map {
                    PackItem(
                        if (s.installed[it]?.source == ModSource.LOCAL) PackKind.LOCAL
                        else PackKind.VER,
                        it,
                        verOf(s, it),
                    )
                },
            followAddress = followAddress,
            includeSettings = includeSettings,
        )
    }

    /** Adopt explicitly selected pack payloads already materialized by pack IO. They remain off. */
    fun addPreparedLocalMods(mods: List<Pair<CatalogMod, InstalledMod>>) {
        require(mods.all { it.second.source == ModSource.LOCAL && it.first.id == it.second.id })
        change { s ->
            s.copy(
                catalog =
                    s.catalog.filterNot { m -> mods.any { it.first.id == m.id } } +
                        mods.map { it.first },
                installed =
                    s.installed +
                        mods
                            .filter { it.first.id !in s.installed }
                            .associate { (mod, m) ->
                                mod.id to
                                    m.copy(
                                        profiles =
                                            s.profiles.associate {
                                                it.id to ProfileMod(false, m.defaultVersion)
                                            }
                                    )
                            },
            )
        }
        applyAndSave()
    }

    /**
     * Store a new payload under the existing identity and a separate version so Undo never
     * overwrites the previous file. Profile on/off switches remain unchanged.
     */
    fun replaceLocal(id: String, path: String) {
        if (state.value.installed[id]?.source != ModSource.LOCAL) return
        background {
            val previous = state.value.installed[id] ?: return@background
            val oldMod = state.value.mod(id)
            val version = "local-" + java.util.UUID.randomUUID().toString()
            diskAwait {
                io.modStore.put(
                    id,
                    version,
                    nnomm.core.io.DownloadedFile(path, PayloadOrigin.LOCAL_FILE),
                )
            }
            val filename = java.io.File(path).name
            change { s ->
                val current = s.installed[id] ?: return@change s
                s.copy(
                    catalog =
                        s.catalog.map { mod ->
                            if (mod.id == id)
                                mod.copy(
                                    localFile = filename,
                                    versions = mod.versions + ModVersion(version),
                                )
                            else mod
                        },
                    installed =
                        s.installed +
                            (id to
                                current.copy(
                                    fileName = filename,
                                    versions = current.versions + version,
                                    defaultVersion = version,
                                    profiles =
                                        current.profiles.mapValues { (_, pm) ->
                                            pm.copy(version = version)
                                        },
                                    fileMissing = false,
                                )),
                )
            }
            io.modStore.protect(setOf(id))
            toast(
                "Replaced ${oldMod.name}",
                "Undo",
                ms = 8000,
                undo = {
                    change { s ->
                        val current = s.installed[id] ?: return@change s
                        s.copy(
                            catalog = s.catalog.map { if (it.id == id) oldMod else it },
                            installed =
                                s.installed +
                                    (id to
                                        previous.copy(
                                            profiles =
                                                current.profiles.mapValues { (profile, pm) ->
                                                    pm.copy(
                                                        version =
                                                            previous.profiles[profile]?.version
                                                                ?: previous.defaultVersion
                                                    )
                                                }
                                        )),
                        )
                    }
                    applyAndSave()
                },
            )
            applyAndSave()
        }
    }

    fun addLocal(path: String) {
        val p = state.value.profileId
        background {
            val (mod, installed) = diskAwait { io.modStore.addLocal(path) }
            if (mod.id in state.value.installed) {
                toast("${mod.name} is already in your Library")
                return@background
            }
            change {
                it.copy(
                    catalog = it.catalog.filterNot { m -> m.id == mod.id } + mod,
                    installed =
                        it.installed +
                            (mod.id to
                                installed.copy(
                                    profiles =
                                        installed.profiles +
                                            (p to ProfileMod(true, installed.defaultVersion))
                                )),
                )
            }
            toast(
                "Added ${installed.fileName?:mod.name} to your Library and turned it on",
                "Undo",
                undo = { uninstallSilently(listOf(mod.id)) },
            )
            applyAndSave(allowLoader = true)
        }
    }

    fun retryApply() = applyAndSave(force = true)

    fun takeOverExistingMods(folder: String) {
        background { adoptExistingMods(folder, false) }
    }

    /** Await adoption before dismissing first start; failure leaves confirmation available. */
    suspend fun confirmFirstStart(folder: String) = adoptExistingMods(folder, true)

    private suspend fun adoptExistingMods(folder: String, complete: Boolean) {
        val game = io.gameFolder.inspect(folder)
        val existing = diskAwait { io.gameFolder.takeOver(folder) }
        change { s ->
            val catalog = (s.catalog + existing.map { it.first }).distinctBy { it.id }
            val installed =
                s.installed +
                    existing.associate { (mod, m) ->
                        val previous = s.installed[mod.id]
                        mod.id to
                            (previous?.copy(
                                versions = previous.versions + m.versions,
                                profiles = m.profiles + previous.profiles,
                            ) ?: m)
                    }
            val on = installed.values.count { it.profiles["default"]?.on == true }
            s.copy(
                game = game,
                settings =
                    s.settings.copy(
                        general = s.settings.general + ("gamePath" to JsonPrimitive(folder))
                    ),
                catalog = catalog,
                installed = installed,
                firstStart = FirstStart(complete || s.firstStart.complete, on, installed.size - on),
            )
        }
        try {
            diskAwait { updateStoreUsage() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            io.logger.error("takeover size", e)
        }
    }

    /** Notices share the store's toast lifetime and are cancelled with the store. */
    fun forwardNotices(notices: kotlinx.coroutines.channels.ReceiveChannel<String>) {
        worker.launch {
            for (message in notices) {
                if (
                    message.contains("takeover", true) ||
                        message.contains("Take over", true) ||
                        message.contains("Ownership is missing")
                )
                    toast(
                        message,
                        "Take over",
                        ms = 30000,
                        undo = { state.value.game.folder?.let(::takeOverExistingMods) },
                    )
                else toast(message)
            }
        }
    }

    fun completeFirstStart() {
        change { it.copy(firstStart = it.firstStart.copy(complete = true)) }
    }

    fun repairLoader() {
        val folder = state.value.game.folder ?: return
        disk {
            try {
                io.gameFolder.repairLoader(folder)
                val game = io.gameFolder.inspect(folder)
                change { it.copy(game = game) }
                toast("Repaired the mod loader.")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                io.logger.error("repair loader", e)
                toast(
                    "Could not repair the mod loader. ${loaderFailureHelp(e)}",
                    "Retry",
                    ms = 30000,
                    undo = { repairLoader() },
                )
            }
        }
    }

    @Volatile private var visibleMetadataIds: Set<String> = emptySet()

    private fun requestMetadata(key: String): Boolean =
        synchronized(metadataRequested) {
            if ((metadataRequested[key] ?: 0) > io.clock.nowMillis()) false
            else {
                metadataRequested[key] = io.clock.nowMillis() + 300_000
                true
            }
        }

    fun loadMetadata(
        ids: Set<String> = state.value.catalog.map { it.id }.toSet(),
        backgroundLoad: Boolean = false,
    ) {
        if (!backgroundLoad) visibleMetadataIds = ids.toSet()
        val snapshot = state.value
        snapshot.catalog
            .filter { it.id in ids }
            .forEach { mod ->
                listOfNotNull(mod.newestRelease, mod.newerPreRelease, mod.oldestVersion)
                    .distinctBy { it.version }
                    .forEach { version ->
                        if (version.downloadUrl != null)
                            background {
                                (if (backgroundLoad) backgroundMetadataSlots else metadataSlots)
                                    .withPermit {
                                        if (!backgroundLoad && mod.id !in visibleMetadataIds)
                                            return@withPermit
                                        if (!requestMetadata("${mod.id}/${version.version}"))
                                            return@withPermit
                                        val key = "${mod.id}/${version.version}"
                                        change {
                                            it.copy(
                                                metadataStatus =
                                                    it.metadataStatus + (key to LoadStatus.LOADING)
                                            )
                                        }
                                        val meta =
                                            try {
                                                io.meta.metadata(mod, version)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                io.logger.error("metadata", e)
                                                change {
                                                    it.copy(
                                                        metadataStatus =
                                                            it.metadataStatus +
                                                                (key to LoadStatus.FAILED)
                                                    )
                                                }
                                                return@withPermit
                                            }
                                        if (meta.sizeKb != null || meta.dateMillis != null)
                                            metadataRequested[key] = Long.MAX_VALUE
                                        change {
                                            it.copy(
                                                metadataStatus =
                                                    it.metadataStatus + (key to LoadStatus.READY)
                                            )
                                        }
                                        change { s ->
                                            s.copy(
                                                catalog =
                                                    s.catalog.map { m ->
                                                        if (m.id != mod.id) m
                                                        else
                                                            m.copy(
                                                                versions =
                                                                    m.versions.map { v ->
                                                                        if (
                                                                            v.version !=
                                                                                version.version
                                                                        )
                                                                            v
                                                                        else
                                                                            v.copy(
                                                                                sizeKb =
                                                                                    meta.sizeKb,
                                                                                dateMillis =
                                                                                    meta.dateMillis,
                                                                            )
                                                                    }
                                                            )
                                                    }
                                            )
                                        }
                                    }
                            }
                    }
            }
    }

    private var refreshSchedule: Job? = null

    fun startRegistrySchedule() {
        refreshSchedule?.cancel()
        refreshSchedule =
            worker.launch {
                state
                    .map { it.settings.general["checkUpdates"]?.content }
                    .distinctUntilChanged()
                    .collectLatest { choice ->
                        if (choice != "Only when I ask") refreshRegistry()
                        if (choice == "Every hour while NNOMM is open")
                            while (isActive) {
                                delay(3600000)
                                refreshRegistry()
                            }
                    }
            }
    }

    fun findGameFolder() {
        background {
            val folder = io.gameFolder.find(state.value.settings)
            if (folder == null) toast("Nuclear Option was not found. Choose its folder.")
            else setSetting("gamePath", JsonPrimitive(folder))
        }
    }

    fun resetSettings() {
        change {
            it.copy(
                settings =
                    defaultSettings()
                        .copy(
                            general =
                                defaultSettings().general +
                                    mapOf(
                                        "gamePath" to JsonPrimitive(it.game.folder.orEmpty()),
                                        "store" to
                                            (it.settings.general["store"] ?: JsonPrimitive("")),
                                    )
                        )
            )
        }
        saveSettings()
    }

    /**
     * Call only after the UI danger-zone confirmation. Payloads and game files remain recoverable.
     */
    fun removeEverything() {
        if (state.value.gameRunning) {
            toast("Close Nuclear Option before removing mods.")
            return
        }
        state.value.installed.keys.forEach(::cancelDownload)
        disk {
            if (io.launcher.running.value || state.value.gameRunning) {
                toast("Close Nuclear Option before removing mods.")
                return@disk
            }
            val snapshot = state.value
            val keep =
                snapshot.installed
                    .filter { (id, mod) ->
                        mod.source == ModSource.LOCAL || io.modStore.keepInLibrary(id, mod.versions)
                    }
                    .mapValues { (_, mod) ->
                        mod.copy(profiles = mod.profiles.mapValues { (_, p) -> p.copy(on = false) })
                    }
            val remove = snapshot.installed.keys - keep.keys
            io.modStore.protect(snapshot.installed.keys)
            val proposed = snapshot.copy(installed = keep)
            try {
                if (snapshot.game.folder != null) {
                    check(!snapshot.game.folderMissing) {
                        "The game folder was not found. Choose it in Settings and Retry."
                    }
                    prepareGameFolder(proposed, modAction = false)
                    check(!io.launcher.running.value && !state.value.gameRunning) {
                        "Close Nuclear Option before removing mods."
                    }
                    check(io.gameFolder.applySucceeded) {
                        "The game folder needs attention. Retry or take over existing mods."
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason =
                    if (io.launcher.running.value || state.value.gameRunning)
                        "Close Nuclear Option before removing mods."
                    else
                        io.gameFolder.applyProblems
                            ?.value
                            .orEmpty()
                            .values
                            .distinct()
                            .joinToString(" ")
                            .ifBlank {
                                if (snapshot.game.folderMissing)
                                    "The game folder was not found. Choose it in Settings and Retry."
                                else
                                    "The game folder needs attention. Retry or take over existing mods."
                            }
                toast(
                    "Could not remove all mods. $reason",
                    "Retry",
                    ms = 30000,
                    undo = { removeEverything() },
                )
                return@disk
            }
            // Publish the completed mod removal before the independent loader operation.
            change { it.copy(installed = keep, deferredModChange = false) }
            try {
                io.persistence?.save(state.value)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                io.logger.error("save removal", e)
                toast(
                    if (snapshot.game.folder == null)
                        "The Library changed, but it could not be saved. Try saving again before closing NNOMM."
                    else
                        "Mods were removed from the game, but the Library could not be saved. Try saving again before closing NNOMM.",
                    "Retry",
                    ms = 30000,
                    undo = {
                        background {
                            flush()
                            toast("Saved the Library.")
                        }
                    },
                )
            }
            io.modStore.remove(remove.toList())
            val result =
                buildList {
                        if (remove.isNotEmpty())
                            add(
                                "Removed ${plural(remove.size, "registry mod", "registry mods")} from every profile."
                            )
                        if (keep.isNotEmpty())
                            add(
                                "Kept ${plural(keep.size, "mod", "mods")} in the Library, turned off, because ${if (keep.size == 1) "its files may" else "their files may"} not be available to download again."
                            )
                        if (snapshot.installed.isEmpty()) add("There are no mods to remove.")
                        if (snapshot.game.folder == null)
                            add(
                                "No game folder is selected. Mods left in the previously selected game folder will be removed when you choose that folder again and press Play."
                            )
                    }
                    .joinToString(" ")
            toast(result)
            snapshot.game.folder?.let { removeLoaderAfterMods(it) }
        }
    }

    private suspend fun removeLoaderAfterMods(folder: String) {
        try {
            check(!io.launcher.running.value && !state.value.gameRunning)
            io.gameFolder.removeLoader(folder)
            val game = io.gameFolder.inspect(folder)
            change { it.copy(game = game) }
            toast("NNOMM-installed mod loader files were removed. Existing loader files were kept.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            io.logger.error("remove loader", e)
            toast(
                "Mod removal is complete. Could not remove the mod loader. ${loaderFailureHelp(e)}",
                "Retry",
                ms = 30000,
                undo = {
                    disk {
                        state.value.game.folder?.let { removeLoaderAfterMods(it) }
                            ?: toast("Choose the game folder in Settings first.")
                    }
                },
            )
        }
    }

    fun clearUnusedFiles() {
        disk {
            io.modStore.clearUnused { state.value }
            refreshStoreUsage()
        }
    }

    private suspend fun updateStoreUsage(includeClearEstimate: Boolean = false) {
        val snapshot = state.value
        val bytes = io.modStore.usedBytes()
        val installed = io.modStore.installedBytes(snapshot)
        val unused =
            if (includeClearEstimate) (io.modStore.unusedBytes(snapshot) + 1023) / 1024
            else snapshot.storeUnusedKb ?: snapshot.storeUnusedMb?.times(1024)
        change {
            it.copy(
                storeUsedMb = bytes / 1024 / 1024,
                storeUsedBytes = bytes,
                storeUsedKb = (installed + 1023) / 1024,
                storeUnusedMb = unused?.div(1024),
                storeUnusedKb = unused,
            )
        }
    }

    fun refreshStoreUsage() {
        synchronized(lock) {
            if (state.value.storeScanStatus == LoadStatus.LOADING) return
            change { it.copy(storeScanStatus = LoadStatus.LOADING) }
        }
        disk {
            try {
                updateStoreUsage(includeClearEstimate = true)
                change { it.copy(storeScanStatus = LoadStatus.READY) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                io.logger.error("store scan", e)
                change { it.copy(storeScanStatus = LoadStatus.FAILED) }
            }
        }
    }

    fun moveStore(folder: String) {
        disk {
            io.modStore.moveTo(folder)
            change {
                it.copy(
                    settings =
                        it.settings.copy(
                            general = it.settings.general + ("store" to JsonPrimitive(folder))
                        )
                )
            }
            refreshStoreUsage()
        }
    }

    fun loadReleaseNotes(id: String) {
        synchronized(lock) {
            if (state.value.notesStatus[id] in setOf(LoadStatus.LOADING, LoadStatus.READY)) return
            change { it.copy(notesStatus = it.notesStatus + (id to LoadStatus.LOADING)) }
        }
        background {
            try {
                val notes = io.releaseNotes.releases(state.value.mod(id))
                change {
                    it.copy(
                        releaseNotes = it.releaseNotes + (id to notes),
                        notesStatus = it.notesStatus + (id to LoadStatus.READY),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                io.logger.error("release notes", e)
                change { it.copy(notesStatus = it.notesStatus + (id to LoadStatus.FAILED)) }
            }
        }
    }

    fun retryDownload(id: String) {
        state.value.downloads
            .firstOrNull { it.id == id && it.error != null }
            ?.let { queue(it.id, it.kind, it.profile, it.version, it.quiet) }
    }

    fun refreshRegistry(announce: Boolean = false) {
        change { it.copy(connection = CatalogConnection(ConnectionKind.LOADING)) }
        background {
            val snapshot =
                try {
                    io.registry.readSnapshot()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    io.logger.error("registry", e)
                    val saved =
                        try {
                            io.registry.saved()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            io.logger.error("registry cache", e)
                            null
                        }
                    change {
                        it.copy(
                            catalog = mergeCatalog(saved?.first.orEmpty(), it),
                            connection =
                                CatalogConnection(
                                    ConnectionKind.OFFLINE,
                                    saved?.second ?: it.connection.readAtMillis,
                                ),
                        )
                    }
                    return@background
                }
            val mods = cleanCatalogTags(snapshot.mods)
            if (snapshot.savedAtMillis != null) {
                change {
                    it.copy(
                        catalog = mergeCatalog(mods, it),
                        connection =
                            CatalogConnection(ConnectionKind.OFFLINE, snapshot.savedAtMillis),
                    )
                }
                return@background
            }
            val now = io.clock.nowMillis()
            change {
                it.copy(
                    catalog = mergeCatalog(mods, it),
                    connection = CatalogConnection(ConnectionKind.READY, now),
                    nowMillis = now,
                )
            }
            checkPackUpdates()
            if (announce)
                toast(
                    updates(state.value).size.let {
                        if (it == 0) "All mods are up to date"
                        else plural(it, "mod has an update", "mods have an update")
                    }
                )
            if (state.value.setting("onUpdate")?.content == "Update by itself") updateAll()
            try {
                io.registry.save(mods, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                io.logger.error("registry save", e)
                toast("Could not save the mod list. The current list is ready to use.")
            }
        }
    }

    private fun mergeCatalog(mods: List<CatalogMod>, s: AppState): List<CatalogMod> {
        val previous = s.catalog.associateBy { it.id }
        val enriched =
            mods.map { mod ->
                val versions = previous[mod.id]?.versions.orEmpty().associateBy { it.version }
                mod.copy(
                    versions =
                        mod.versions.map { version ->
                            val old =
                                versions[version.version]?.takeIf {
                                    it.downloadUrl == version.downloadUrl
                                }
                            version.copy(
                                sizeKb = version.sizeKb ?: old?.sizeKb,
                                dateMillis = version.dateMillis ?: old?.dateMillis,
                            )
                        }
                )
            }
        return (enriched + s.catalog.filter { it.id in s.installed || it.localFile != null })
            .distinctBy { it.id }
            .let(::cleanCatalogTags)
    }

    private suspend fun drainAndClose() {
        try {
            persistenceJob?.cancel()
            launcherObserver.cancel()
            refreshSchedule?.cancel()
            diskQueue.close()
            diskWorker.join()
            flush()
            closed.complete(Unit)
        } catch (e: Exception) {
            closed.completeExceptionally(e)
            io.logger.error("shutdown", e)
        } finally {
            worker.cancel()
        }
    }

    suspend fun shutdown() {
        if (closing.compareAndSet(false, true)) drainAndClose()
        closed.await()
    }

    override fun close() {
        if (closing.compareAndSet(false, true)) worker.launch { drainAndClose() }
    }
}
