package nnomm.core.rules

import nnomm.core.model.*

private val names =
    ThreadLocal.withInitial { java.text.Collator.getInstance(java.util.Locale.getDefault()) }

private fun compareNames(a: String, b: String) = names.get().compare(a, b)

fun isOn(s: AppState, id: String, p: String = s.profileId) =
    s.installed[id]?.profiles?.get(p)?.on == true

fun verOf(s: AppState, id: String, p: String = s.profileId) =
    s.installed[id]?.let { it.profiles[p]?.version ?: it.defaultVersion }

fun packManaged(s: AppState, id: String, p: String = s.profileId) =
    s.profile(p).pack?.mods?.contains(id) == true

/** The profile was made from a pack and the pack still controls it (updates or mod versions). */
fun followsPack(p: Profile) =
    p.pack?.let { it.followAddress != null || it.mods.isNotEmpty() } == true

fun availableVersion(s: AppState, id: String) =
    s.mod(id).let {
        if (s.setting("prerelease")?.content == "true") it.newerPreRelease ?: it.installable
        else it.installable
    }

fun hasUpdate(s: AppState, id: String) =
    id in s.installed &&
        s.installed[id]?.source != ModSource.LOCAL &&
        !packManaged(s, id) &&
        availableVersion(s, id)?.let { compareVersions(it.version, verOf(s, id).orEmpty()) > 0 } ==
            true

fun updates(s: AppState) = s.installed.keys.filter { hasUpdate(s, it) }

fun onCount(s: AppState, p: String = s.profileId) = s.installed.keys.count { isOn(s, it, p) }

fun usedIn(s: AppState, id: String) = s.profiles.filter { isOn(s, id, it.id) }.map { it.name }

fun sourceOf(s: AppState, id: String) =
    if (s.installed[id]?.source == ModSource.LOCAL) "Local file"
    else if (packManaged(s, id)) "Modpack" else "Registry"

fun daysAgo(s: AppState, date: Long?) =
    date?.let { ((s.nowMillis - it) / 86400000L).coerceIn(0, Int.MAX_VALUE.toLong() - 1).toInt() }
        ?: Int.MAX_VALUE

fun updOf(s: AppState, id: String) = daysAgo(s, s.artifact(id)?.dateMillis)

fun dependencyClosure(s: AppState, id: String, version: String? = null): List<PackageReference> {
    val seen = mutableSetOf(id)
    val result = mutableListOf<PackageReference>()
    fun visit(ref: PackageReference) {
        if (!seen.add(ref.id)) return
        val artifact =
            ref.version?.let { v -> s.mod(ref.id).versions.firstOrNull { it.version == v } }
                ?: s.artifact(ref.id)
        artifact?.let { (it.dependencies + listOfNotNull(it.extends)).forEach(::visit) }
        result += ref
    }
    val artifact =
        version?.let { v -> s.mod(id).versions.firstOrNull { it.version == v } } ?: s.artifact(id)
    artifact?.let { (it.dependencies + listOfNotNull(it.extends)).forEach(::visit) }
    return result
}

data class Problem(
    val kind: ProblemKind,
    val key: String,
    val text: String,
    val other: String? = null,
)

fun problems(s: AppState, id: String): List<Problem> {
    val st = s.installed[id] ?: return emptyList()
    val x = s.mod(id)
    val v =
        s.artifact(id)
            ?: return if (st.fileMissing)
                listOf(Problem(ProblemKind.DANGER, "missing", "File missing"))
            else
                s.applyProblems[id]
                    ?.takeIf { isOn(s, id) || it.startsWith("This mod is still in the game") }
                    ?.let { listOf(Problem(ProblemKind.WARNING, "placement", it)) }
                    .orEmpty()
    return buildList {
        s.applyProblems[id]
            ?.takeIf { isOn(s, id) || it.startsWith("This mod is still in the game") }
            ?.let { add(Problem(ProblemKind.WARNING, "placement", it)) }
        if (st.fileMissing) add(Problem(ProblemKind.DANGER, "missing", "File missing"))
        if (isOn(s, id)) {
            dependencyClosure(s, id, v.version).forEach {
                if (!isOn(s, it.id))
                    add(Problem(ProblemKind.WARNING, "dep", "Needs ${s.mod(it.id).name}", it.id))
            }
            (v.incompatibilities.map { it.id } + s.reverseIncompatibilities[id].orEmpty()).forEach {
                if (isOn(s, it))
                    add(
                        Problem(
                            ProblemKind.WARNING,
                            "conflict",
                            "Conflicts with ${s.mod(it).name}",
                            it,
                        )
                    )
            }
        }
        if (
            s.settings.bool("warnOld", s.profileId) &&
                v.gameVersion != null &&
                v.gameVersion != effectiveGameVersion(s)
        )
            add(Problem(ProblemKind.INFO, "old", "Made for ${v.gameVersion}"))
    }
}

fun hasProblem(s: AppState, id: String) = problems(s, id).any { it.kind != ProblemKind.INFO }

data class BrowseFilter(
    val query: String = "",
    val sort: BrowseSort = BrowseSort.DOWNLOADS,
    val tags: List<String> = emptyList(),
    val tagMode: String = "any",
    val type: String = "all",
    val have: String = "all",
    val fits: Boolean = false,
    /** Tags whose mods leave the list, whatever [tagMode] is. */
    val hideTags: List<String> = emptyList(),
) {
    fun toggleHave(v: String) = copy(have = if (have == v) "all" else v)

    fun toggleType(v: String) = copy(type = if (type == v) "all" else v)

    fun toggleTag(v: String) = copy(tags = if (v in tags) tags - v else tags + v)

    fun clear(key: String) =
        when (key) {
            "tags" -> copy(tags = emptyList())
            "hide" -> copy(hideTags = emptyList())
            "fits" -> copy(fits = false)
            "type" -> copy(type = "all")
            "have" -> copy(have = "all")
            else -> this
        }
}

data class BrowseRow(val mod: CatalogMod, val match: ModMatch, val action: ModAction? = null)

fun browseFiltered(b: BrowseFilter) =
    b.query.isNotBlank() || b.tags.isNotEmpty() || b.hideTags.isNotEmpty() || b.type != "all" || b.have != "all" || b.fits

fun browseList(
    s: AppState,
    b: BrowseFilter = BrowseFilter(),
    skip: String? = null,
): List<BrowseRow> {
    val list =
        s.catalog
            .filter { x ->
                x.localFile == null &&
                    (!s.settings.bool("hideServer") || x.side != Side.SERVER) &&
                    (skip == "type" ||
                        b.type == "all" ||
                        (if (x.installable?.type == ModType.ADDON) "addon" else "plugin") ==
                            b.type) &&
                    (skip == "tags" ||
                        b.tags.isEmpty() ||
                        if (b.tagMode == "all") b.tags.all { it in x.tags }
                        else b.tags.any { it in x.tags }) &&
                    b.hideTags.none { it in x.tags } &&
                    (skip == "have" ||
                        when (b.have) {
                            "new" -> x.id !in s.installed
                            "inst" -> x.id in s.installed
                            "upd" -> hasUpdate(s, x.id)
                            else -> true
                        }) &&
                    (!b.fits || x.installable?.gameVersion == effectiveGameVersion(s))
            }
            .mapNotNull { x ->
                matchMod(x, b.query.trim())?.let { BrowseRow(x, it, modAction(s, x.id)) }
            }
    val comparator =
        when (b.sort) {
            BrowseSort.UPDATED -> compareBy<BrowseRow> { daysAgo(s, it.mod.updated) }
            BrowseSort.NEWEST -> compareBy { daysAgo(s, it.mod.added) }
            BrowseSort.NAME -> compareBy { it.mod.name.lowercase() }
            else -> compareByDescending { it.mod.downloadCount }
        }
    return list.sortedWith(
        if (b.query.isNotBlank())
            compareByDescending<BrowseRow> { it.match.score }
                .thenByDescending { it.mod.downloadCount }
        else comparator
    )
}

fun tagCounts(s: AppState, b: BrowseFilter = BrowseFilter()): Map<String, Int> {
    val all =
        s.catalog
            .flatMap { it.tags }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
    val base =
        browseList(s, b, "tags")
            .map { it.mod }
            .filter { b.tagMode != "all" || b.tags.all { t -> t in it.tags } }
    return all.associateWith { t -> base.count { t in it.tags } }
        .entries
        .sortedByDescending { it.value }
        .associate { it.key to it.value }
}

data class BrowseSection(val title: String, val subtitle: String, val mods: List<CatalogMod>)

fun browseSections(s: AppState, b: BrowseFilter = BrowseFilter()): List<BrowseSection> {
    if (browseFiltered(b) || b.sort != BrowseSort.DOWNLOADS) return emptyList()
    val pool = browseList(s, b).map { it.mod }
    val fresh =
        pool
            .filter { daysAgo(s, it.updated) <= 7 }
            .sortedWith(
                compareBy<CatalogMod> { daysAgo(s, it.updated) }
                    .thenByDescending { it.downloadCount }
                    .thenBy { it.name }
            )
    val new =
        pool
            .filter { daysAgo(s, it.added) <= 60 }
            .sortedWith(
                compareBy<CatalogMod> { daysAgo(s, it.added) }
                    .thenByDescending { it.downloadCount }
                    .thenBy { it.name }
            )
    val yours =
        pool.filter {
            it.id !in s.installed &&
                it.installable
                    ?.let { v -> v.dependencies + listOfNotNull(v.extends) }
                    .orEmpty()
                    .isNotEmpty() &&
                it.installable
                    ?.let { v -> v.dependencies + listOfNotNull(v.extends) }
                    .orEmpty()
                    .all { d -> d.id in s.installed }
        }
    val base =
        yours
            .flatMap {
                it.installable?.let { v -> v.dependencies + listOfNotNull(v.extends) }.orEmpty()
            }
            .map { it.id }
            .distinct()
            .joinToString(" and ") { s.mod(it).name }
    return listOf(
            BrowseSection("Updated this week", plural(fresh.size, "mod", "mods"), fresh),
            BrowseSection("New", "first release in the last 2 months", new),
            BrowseSection("For mods you have", "they need $base, which you have", yours),
        )
        .filter { it.mods.isNotEmpty() }
}

data class LibraryFilter(
    val filter: String = "all",
    val query: String = "",
    val sort: LibrarySort = LibrarySort.NAME,
    val direction: Int = 1,
)

fun libCounts(s: AppState) =
    mapOf(
        "all" to s.installed.size,
        "on" to onCount(s),
        "off" to s.installed.size - onCount(s),
        "upd" to updates(s).size,
        "local" to s.installed.values.count { it.source == ModSource.LOCAL },
        "prob" to s.installed.keys.count { hasProblem(s, it) },
    )

fun libList(s: AppState, l: LibraryFilter = LibraryFilter()): List<String> {
    val ids =
        s.installed.keys.filter { id ->
            when (l.filter) {
                "on" -> isOn(s, id)
                "off" -> !isOn(s, id)
                "upd" -> hasUpdate(s, id)
                "local" -> s.installed[id]?.source == ModSource.LOCAL
                "prob" -> hasProblem(s, id)
                else -> true
            } && (l.query.isBlank() || fuzzy(l.query.trim(), s.mod(id).name) != null)
        }
    fun cmp(a: String, b: String): Int =
        when (l.sort) {
            LibrarySort.SIZE ->
                (s.mod(a).newestRelease?.sizeKb ?: 0).compareTo(s.mod(b).newestRelease?.sizeKb ?: 0)
            LibrarySort.UPD -> updOf(s, a).compareTo(updOf(s, b))
            LibrarySort.SOURCE -> sourceOf(s, a).compareTo(sourceOf(s, b))
            LibrarySort.ON -> (!isOn(s, a)).compareTo(!isOn(s, b))
            LibrarySort.VER -> (!hasUpdate(s, a)).compareTo(!hasUpdate(s, b))
            else -> compareNames(s.mod(a).name, s.mod(b).name)
        }
    return ids.sortedWith { a, b ->
        (cmp(a, b) * l.direction).takeIf { it != 0 } ?: compareNames(s.mod(a).name, s.mod(b).name)
    }
}

data class ModAction(
    val label: String,
    val action: UiAction? = null,
    val disabled: Boolean = false,
    val tooltip: String? = null,
    val percent: Int? = null,
)

fun modAction(s: AppState, id: String): ModAction {
    s.downloads
        .firstOrNull { it.id == id && it.percent < 100 && it.error == null }
        ?.let {
            return ModAction(
                if (it.percent < 0) "Downloading" else "${it.percent}%",
                percent = it.percent,
            )
        }
    availableVersion(s, id)?.unavailableReason?.let {
        return ModAction("Unavailable", disabled = true, tooltip = it)
    }
    val off = s.connection.kind == ConnectionKind.OFFLINE
    if (id !in s.installed)
        return ModAction(
            "Install",
            UiAction("install", id),
            off,
            if (off) "Needs a connection" else null,
        )
    if (s.installed.getValue(id).fileMissing)
        return ModAction(
            "Repair",
            UiAction("repair", id),
            tooltip = "The file is missing. Download it again.",
        )
    if (hasUpdate(s, id))
        return ModAction(
            "Update",
            UiAction("update", id),
            off,
            "${verOf(s,id)} to ${availableVersion(s,id)?.version}",
        )
    if (!isOn(s, id))
        return ModAction(
            "Turn on",
            UiAction("turnOn", id),
            tooltip = "Installed. Off in ${s.profile().name}.",
        )
    return ModAction("On", tooltip = "Installed and on in ${s.profile().name}")
}

data class FixItem(val label: String, val action: UiAction, val danger: Boolean = false)

fun problemFixes(s: AppState, id: String, p: Problem): List<FixItem> {
    fun off(q: String) = FixItem("Turn off ${s.mod(q).name}", UiAction("turnOff", q))
    return when (p.key) {
        "placement" ->
            listOf(FixItem("Retry", UiAction("retryApply", id))) +
                (if (isOn(s, id)) listOf(off(id))
                else
                    s.placementConflicts[id]
                        .orEmpty()
                        .filter { isOn(s, it) }
                        .sortedBy { s.mod(it).name }
                        .map(::off))
        "dep" ->
            listOf(
                FixItem(
                    "${if(p.other in s.installed)"Turn on" else "Install"} ${s.mod(p.other!!).name}",
                    UiAction(if (p.other in s.installed) "turnOn" else "install", p.other),
                ),
                off(id),
            )
        "conflict" ->
            listOf(
                off(p.other!!),
                off(id),
                FixItem("See why they conflict", UiAction("openMod", id, "needs")),
            )
        "missing" ->
            listOf(
                FixItem("Download it again", UiAction("repairProblem", id)),
                FixItem("Remove it from the list", UiAction("uninstall", id), true),
            )
        else -> emptyList()
    }
}

/** Unknown disk version uses the newest game version named by the registry. */
fun effectiveGameVersion(s: AppState): String? =
    s.game.version?.takeUnless { it == "unknown" } ?: s.registryGameVersion

/** Complete stable row data. Observe this instead of recalculating problems on progress ticks. */
data class LibraryRow(
    val id: String,
    val mod: CatalogMod,
    val installed: InstalledMod,
    val on: Boolean,
    val version: String?,
    val source: String,
    val problems: List<Problem>,
    val hasUpdate: Boolean,
    val updatedDays: Int,
)

fun libraryView(s: AppState, filter: LibraryFilter = LibraryFilter()) =
    libList(s, filter).map { id ->
        LibraryRow(
            id,
            s.mod(id),
            s.installed.getValue(id),
            isOn(s, id),
            verOf(s, id),
            sourceOf(s, id),
            problems(s, id),
            hasUpdate(s, id),
            updOf(s, id),
        )
    }
