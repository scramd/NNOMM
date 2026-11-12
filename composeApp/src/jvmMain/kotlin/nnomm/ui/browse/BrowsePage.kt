@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package nnomm.ui.browse

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import nnomm.core.model.*
import nnomm.core.rules.*
import nnomm.ui.*
import nnomm.ui.kit.*
import nnomm.ui.theme.LocalNnTheme

/** Browse owns only view state; registry work and mod operations stay in the store. */
@Composable
fun BrowsePage() {
    val model = LocalApp.current
    val b = model.ui.browse
    // Toasts and download ticks must not rerun filtering or recompose the results.
    val pageFlow =
        remember(model) {
            model.store.state
                .map { it.copy(toasts = emptyList(), downloads = emptyList()) }
                .distinctUntilChanged()
        }
    val state by
        pageFlow.collectAsState(
            model.store.state.value.copy(toasts = emptyList(), downloads = emptyList())
        )
    val filter =
        BrowseFilter(
            b.query,
            nnomm.core.model.BrowseSort.entries.firstOrNull { it.name.equals(b.sort, true) } ?: nnomm.core.model.BrowseSort.DOWNLOADS,
            b.tags.toList(),
            b.tagMode,
            b.kind.ifEmpty { "all" },
            b.have.ifEmpty { "all" },
            b.game.isNotEmpty(),
        )
    val rows = remember(state, filter) { browseList(state, filter) }
    val sections = remember(state, filter) { browseSections(state, filter) }
    val narrow =
        with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() <= 940.dp }
    val focus = remember { FocusRequester() }
    val field = remember(b) { FocusedField({ b.query }, { b.query = "" }) }
    DisposableEffect(model) {
        val dispose = model.registerSearch(Page.Browse) { focus.requestFocus() }
        onDispose {
            dispose()
            if (model.ui.focusedField === field) model.ui.focusedField = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        BrowseHeader(state, narrow, focus, field)
        if (state.connection.kind == ConnectionKind.OFFLINE) {
            Note(
                offlineText(state, model.ui.mock == "offline"),
                Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
                warn = true,
                centered = true,
                action = {
                    Button(
                        "Try again",
                        model.store::refreshRegistry,
                        size = ControlSize.Small,
                        icon = NnIcons.refresh,
                    )
                },
            )
        }
        Box(Modifier.weight(1f)) {
            Row(Modifier.fillMaxSize()) {
                if (!narrow) Facets(state, filter, Modifier.width(186.dp))
                Results(state, filter, rows, sections, narrow, Modifier.weight(1f))
            }
            if (narrow && b.facetsOpen) {
                Facets(
                    state,
                    filter,
                    Modifier.width(210.dp)
                        .fillMaxHeight()
                        .shadow(12.dp)
                        .background(LocalNnTheme.current.colors.side)
                        .border(1.dp, LocalNnTheme.current.colors.line)
                        .padding(top = 14.dp),
                )
            }
        }
    }
}

private fun offlineText(s: AppState, mock: Boolean): String {
    if (mock)
        return "No connection to the registry. This is the list from yesterday, 21:40. You can still use your installed mods and start the game."
    val at = s.connection.readAtMillis
    val saved =
        if (at == null) "the saved list"
        else {
            val time = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
            val day = daysAgo(s, at)
            val date = if (day <= 1) ago(day) else time.toLocalDate().toString()
            "the list from $date, ${time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
        }
    return "No connection to the registry. This is $saved. You can still use your installed mods and start the game."
}

@Composable
private fun BrowseHeader(s: AppState, narrow: Boolean, focus: FocusRequester, field: FocusedField) {
    val model = LocalApp.current
    val b = model.ui.browse
    val c = LocalNnTheme.current.colors
    val searching = b.query.isNotBlank()
    val sorts = listOf("downloads", "updated", "newest", "name")
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label("Browse", Modifier.padding(end = 6.dp), size = 20f, weight = 650, width = 112f)
        Label(
            "${s.catalog.count { it.localFile == null && (!s.settings.bool("hideServer") || it.side != Side.SERVER) }} mods",
            color = c.text2,
        )
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(if (narrow) 164.dp else 250.dp)) {
            Tooltip("Looks in names, tags, authors and descriptions") {
                TextField(
                    b.query,
                    { b.query = it },
                    Modifier.fillMaxWidth().onFocusChanged {
                        if (it.hasFocus) model.ui.focusedField = field
                        else if (model.ui.focusedField === field) model.ui.focusedField = null
                    },
                    placeholder = "Search mods",
                    icon = NnIcons.search,
                    focusRequester = focus,
                )
            }
        }
        Tooltip(if (searching) "A search shows the best match first" else "Sort") {
            Select(
                if (searching) listOf("Best match")
                else listOf("Most downloaded", "Recently updated", "Newest", "Name"),
                if (searching) 0 else sorts.indexOf(b.sort).coerceAtLeast(0),
                { b.sort = sorts[it] },
                Modifier.width(if (searching) 122.dp else 159.dp),
                enabled = !searching,
            )
        }
        SegmentedControl(
            emptyList(),
            if (b.view == "grid") 0 else 1,
            { b.view = if (it == 0) "grid" else "list" },
            segments =
                listOf(Segment("Grid", NnIcons.grid, true), Segment("List", NnIcons.list, true)),
        )
        if (narrow) IconButton(NnIcons.filter, "Filters", { b.facetsOpen = !b.facetsOpen })
    }
}

@Composable
private fun Facets(s: AppState, filter: BrowseFilter, modifier: Modifier) {
    val b = LocalApp.current.ui.browse
    val have = remember(s, filter) { browseList(s, filter, "have") }
    val kinds = remember(s, filter) { browseList(s, filter, "type") }
    val counts = remember(s, filter) { tagCounts(s, filter) }
    // Keep the registry's tag frequency order when counts change with another facet.
    val tags =
        remember(s.catalog) {
            s.catalog
                .flatMap { it.tags }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
        }
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            FacetHeading("Show", b.have.isNotEmpty() && b.have != "all", { b.have = "" })
            listOf("new" to "Not installed", "inst" to "Installed", "upd" to "With an update")
                .forEach { (key, title) ->
                    val count =
                        have.count {
                            when (key) {
                                "new" -> it.mod.id !in s.installed
                                "inst" -> it.mod.id in s.installed
                                else -> hasUpdate(s, it.mod.id)
                            }
                        }
                    FilterOption(
                        title,
                        count,
                        b.have == key,
                        { b.have = if (b.have == key) "" else key },
                        radio = true,
                    )
                }
        }
        Column {
            FacetHeading("Kind", b.kind.isNotEmpty() && b.kind != "all", { b.kind = "" })
            listOf("plugin" to "Mods", "addon" to "Add-ons").forEach { (key, title) ->
                FilterOption(
                    title,
                    kinds.count {
                        (it.mod.newestRelease?.type == ModType.ADDON) == (key == "addon")
                    },
                    b.kind == key,
                    { b.kind = if (b.kind == key) "" else key },
                    radio = true,
                )
            }
        }
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 18.dp).padding(bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Label(
                    "Tags",
                    Modifier.weight(1f),
                    size = 12f,
                    weight = 600,
                    color = LocalNnTheme.current.colors.text2,
                )
                if (b.tags.isNotEmpty()) Tooltip("Show all tags again") { LinkButton("Clear", { b.tags = emptySet() }) }
                if (b.tags.size > 1)
                    Tooltip("A mod must have any, or all, of the tags you select") {
                        SegmentedControl(
                            listOf("Any", "All"),
                            if (b.tagMode == "all") 1 else 0,
                            { b.tagMode = if (it == 0) "any" else "all" },
                            small = true,
                        )
                    }
            }
            (if (b.allTags) tags else tags.take(9)).forEach { tag ->
                FilterOption(tag, counts[tag] ?: 0, tag in b.tags, { b.toggleTag(tag) })
            }
            LinkButton(
                if (b.allTags) "Fewer tags" else "All ${tags.size} tags",
                { b.allTags = !b.allTags },
                Modifier.padding(top = 4.dp),
            )
        }
        Column {
            FacetHeading("Game version", b.game.isNotEmpty(), { b.game = "" })
            control(
                Modifier.fillMaxWidth(),
                onClick = { b.game = if (b.game.isEmpty()) "fits" else "" },
            ) { m, hover, _ ->
                Row(
                    m.height(25.dp)
                        .background(
                            if (hover) LocalNnTheme.current.colors.raise else Color.Transparent
                        )
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SelectionGlyph(b.game.isNotEmpty(), false)
                    Label(
                        "Made for ${effectiveGameVersion(s) ?: "unknown"}",
                        color = if (b.game.isNotEmpty()) LocalNnTheme.current.colors.text else LocalNnTheme.current.colors.text2,
                    )
                }
            }
        }
    }
}

@Composable
private fun FacetHeading(title: String, clear: Boolean, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 18.dp).padding(bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(
            title,
            Modifier.weight(1f),
            size = 12f,
            weight = 600,
            color = LocalNnTheme.current.colors.text2,
        )
        if (clear) Tooltip("Show all again") { LinkButton("Clear", onClear) }
    }
}

private fun BrowseView.toggleTag(tag: String) {
    tags = if (tag in tags) tags - tag else tags + tag
}

private fun BrowseView.clearFilters() {
    query = ""
    tags = emptySet()
    have = ""
    kind = ""
    game = ""
}

@Composable
private fun Results(
    s: AppState,
    filter: BrowseFilter,
    rows: List<BrowseRow>,
    sections: List<BrowseSection>,
    narrow: Boolean,
    modifier: Modifier,
) {
    val b = LocalApp.current.ui.browse
    val filtered = browseFiltered(filter)
    val loading = s.connection.kind == ConnectionKind.LOADING
    val grid = rememberLazyGridState()
    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .padding(start = if (narrow) 18.dp else 12.dp, end = 18.dp, bottom = 18.dp)
    ) {
        val columns = ((maxWidth.value + 8) / 236).toInt().coerceAtLeast(1)
        LazyVerticalGrid(
            GridCells.Adaptive(228.dp),
            Modifier.fillMaxSize(),
            state = grid,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement =
                Arrangement.spacedBy(if (b.view == "grid" || loading) 8.dp else 0.dp),
        ) {
            if (loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeading("Reading the registry", "")
                }
                items(9) { Skeleton(Modifier.fillMaxWidth()) }
            } else {
                if (filtered)
                    item(span = { GridItemSpan(maxLineSpan) }) { ActiveFilters(s, rows.size) }
                sections.forEach { section ->
                    item(key = "section-${section.title}", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(bottom = 12.dp)) {
                            SectionHeading(section.title, section.subtitle)
                            Spacer(Modifier.height(8.dp))
                            CardStrip(section.mods, { it.id }) {
                                BrowseCard(s, BrowseRow(it, ModMatch(0)), strip = true)
                            }
                        }
                    }
                }
                if (!filtered)
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeading(
                            "All mods",
                            when (b.sort) {
                                "updated" -> "last updated first"
                                "newest" -> "newest first"
                                "name" -> "by name"
                                else -> "most downloaded first"
                            },
                        )
                    }
                if (rows.isEmpty())
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 60.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Column(
                                Modifier.widthIn(max = 380.dp).padding(horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Label("No mod matches", size = 16f, weight = 600)
                                Label(
                                    "Try fewer letters or remove a filter. The search also looks in descriptions, tags and author names.",
                                    color = LocalNnTheme.current.colors.text2,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                                Button("Clear search and filters", b::clearFilters)
                            }
                        }
                    }
                if (b.view == "grid") {
                    items(
                        rows.chunked(columns),
                        key = { it.first().mod.id },
                        span = { GridItemSpan(maxLineSpan) },
                    ) { group ->
                        Row(
                            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            group.forEach { row ->
                                BrowseCard(s, row, modifier = Modifier.weight(1f).fillMaxHeight())
                            }
                            repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                } else
                    items(rows, key = { it.mod.id }, span = { GridItemSpan(maxLineSpan) }) {
                        BrowseRowView(s, it)
                    }
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(grid),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp).offset(x = 12.dp),
        )
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    FlowRow(
        Modifier.heightIn(min = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        Label(title, size = 14f, weight = 600)
        if (subtitle.isNotEmpty())
            Label(subtitle, size = 12f, color = LocalNnTheme.current.colors.text3)
    }
}

@Composable
private fun ActiveFilters(s: AppState, count: Int) {
    val b = LocalApp.current.ui.browse
    val c = LocalNnTheme.current.colors
    FlowRow(
        Modifier.padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Label(plural(count, "mod", "mods"), weight = 700)
        if (b.query.isNotBlank()) Label("for \"${b.query.trim()}\"", color = c.text2)
        if (b.have.isNotEmpty() && b.have != "all")
            FilterChip(
                when (b.have) {
                    "new" -> "Not installed"
                    "inst" -> "Installed"
                    else -> "With an update"
                }
            ) {
                b.have = ""
            }
        if (b.kind.isNotEmpty() && b.kind != "all")
            FilterChip(if (b.kind == "addon") "Add-ons" else "Mods") { b.kind = "" }
        b.tags.forEach { tag -> FilterChip(tag) { b.toggleTag(tag) } }
        if (b.game.isNotEmpty()) FilterChip("Made for ${effectiveGameVersion(s)}") { b.game = "" }
        if (b.tags.size > 1)
            Label(
                if (b.tagMode == "any") "any of these tags" else "all of these tags",
                color = c.text3,
            )
        LinkButton("Clear all", b::clearFilters)
    }
}

@Composable
private fun FilterChip(text: String, remove: () -> Unit) {
    Tooltip("Remove this filter") { Chip(text, selected = true, onClick = remove, trailingIcon = NnIcons.x) }
}

@Composable
private fun modName(row: BrowseRow): AnnotatedString =
    highlightedText(row.mod.name, row.match.reason?.takeIf { it.kind == nnomm.core.model.MatchKind.NAME }?.indices.orEmpty())

@Composable
private fun reasonText(reason: MatchReason): AnnotatedString = buildAnnotatedString {
    append(reason.text)
    reason.snippet?.let { append(highlightedText(it, reason.highlightRange?.toList().orEmpty())) }
}

@Composable
private fun BrowseCard(
    s: AppState,
    row: BrowseRow,
    strip: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val model = LocalApp.current
    val x = row.mod
    val c = LocalNnTheme.current.colors
    val current = model.ui.panel?.modId == x.id
    val shape = RoundedCornerShape(9.dp)
    control(
        modifier.fillMaxWidth().heightIn(min = 0.dp),
        onClick = { model.ui.openMod(x.id) },
    ) { m, hover, _ ->
        Column(
            m.background(c.raise, shape)
                .border(
                    if (current) 2.dp else 1.dp,
                    if (current) c.text3 else if (hover) c.line2 else c.line,
                    shape,
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModTile(x.id, x.name, x.demoPicture, TileSize.Medium, imageUrl = x.imageUrl)
                Column(Modifier.weight(1f)) {
                    Label(modName(row), Modifier.heightIn(min = 18.9.dp), size = 13.5f, weight = 600, maxLines = 2)
                    Label(x.authors.joinToString(", "), Modifier.heightIn(min = 16.8.dp), color = c.text3, size = 12f, maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            Label(
                x.description,
                Modifier.heightIn(min = if (strip) 18.dp else 35.dp),
                color = c.text2,
                size = 12.5f,
                maxLines = if (strip) 1 else 2,
            )
            row.match.reason
                ?.takeUnless { it.kind == nnomm.core.model.MatchKind.NAME }
                ?.let {
                    Spacer(Modifier.height(8.dp))
                    Label(reasonText(it), color = c.text3, size = 12f, maxLines = 1)
                }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.clipToBounds(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                x.tags.take(3).forEach { tag ->
                    Tooltip("Show mods with this tag") { Tag(tag, { model.ui.browse.tags += tag }) }
                }
                if (x.newestRelease?.type == ModType.ADDON)
                    Tooltip(
                        "Content for another mod. Needs ${x.newestRelease!!.dependencies.joinToString(", ") { dep -> s.catalog.firstOrNull { it.id == dep.id }?.name ?: dep.id }}."
                    ) {
                        Tag("Add-on")
                    }
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 27.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Tooltip("Downloads") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(NnIcons.download, Modifier.size(12.dp), c.text3)
                            Label(num(x.downloadCount), color = c.text3, size = 12f)
                        }
                    }
                    Tooltip("Newest version") {
                        Label(age(s, x), color = c.text3, size = 12f, maxLines = 1)
                    }
                }
                StateButton(x.id)
            }
        }
    }
}

private fun age(s: AppState, x: CatalogMod) =
    if (x.updated == null) "" else ago(daysAgo(s, x.updated))

@Composable
private fun BrowseRowView(s: AppState, row: BrowseRow) {
    val model = LocalApp.current
    val x = row.mod
    val c = LocalNnTheme.current.colors
    BoxWithConstraints {
        val compact = maxWidth <= 800.dp
        control(Modifier.fillMaxWidth(), onClick = { model.ui.openMod(x.id) }) { m, hover, _ ->
            Row(
                m.heightIn(min = 44.dp)
                    .background(
                        if (hover || model.ui.panel?.modId == x.id) c.raise else Color.Transparent
                    )
                    .drawBehind {
                        drawLine(
                            c.line,
                            androidx.compose.ui.geometry.Offset(0f, size.height),
                            androidx.compose.ui.geometry.Offset(size.width, size.height),
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModTile(x.id, x.name, x.demoPicture, imageUrl = x.imageUrl)
                Column(Modifier.weight(1.1f).widthIn(min = if (compact) 120.dp else 140.dp)) {
                    Label(modName(row), weight = 600, maxLines = 1)
                    Label(x.authors.joinToString(", "), size = 12f, color = c.text3, maxLines = 1)
                }
                Label(
                    row.match.reason?.takeUnless { it.kind == nnomm.core.model.MatchKind.NAME }?.let { reasonText(it) }
                        ?: AnnotatedString(x.description),
                    Modifier.weight(if (compact) 1.4f else 2f),
                    color = c.text2,
                    maxLines = 1,
                )
                Tooltip("Downloads") {
                    Label(
                        num(x.downloadCount),
                        Modifier.width(if (compact) 70.dp else 76.dp),
                        color = c.text2,
                        width = 88f,
                        maxLines = 1,
                    )
                }
                if (!compact)
                    Label(
                        age(s, x),
                        Modifier.width(76.dp),
                        color = c.text2,
                        width = 88f,
                        maxLines = 1,
                    )
                Box(
                    Modifier.width(if (compact) 92.dp else 96.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    StateButton(x.id)
                }
            }
        }
    }
}

@Composable
private fun StateButton(id: String) {
    val model = LocalApp.current
    val flow =
        remember(model, id) { model.store.state.map { modAction(it, id) }.distinctUntilChanged() }
    val action by flow.collectAsState(modAction(model.store.state.value, id))
    val c = LocalNnTheme.current.colors
    @Composable
    fun content() {
        when {
            action.percent != null -> DownloadPill(action.percent!! / 100f, action.label)
            action.action == null ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(NnIcons.check, color = c.on)
                    Label("On", color = c.on, size = 13f, weight = 600)
                }
            else ->
                Button(
                    action.label,
                    { action.action?.let(model::perform) },
                    kind =
                        when (action.label) {
                            "Update" -> ButtonKind.Upd
                            "Install" -> ButtonKind.Default
                            else -> ButtonKind.Line
                        },
                    size = ControlSize.Small,
                    enabled = !action.disabled,
                )
        }
    }
    val tip = if (action.disabled) "Needs a connection" else action.tooltip
    if (tip != null) Tooltip(tip) { content() } else content()
}
