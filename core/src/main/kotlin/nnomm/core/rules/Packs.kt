package nnomm.core.rules

import kotlin.math.*
import nnomm.core.model.*

data class ImportRow(
    val kind: PackKind,
    val id: String,
    val packVersion: String?,
    val have: String?,
    val text: String,
    val local: Boolean = false,
    val defaultOn: Boolean = true,
)

fun importRows(s: AppState, pack: Pack, mode: ImportMode = ImportMode.NEW): List<ImportRow> =
    pack.items.map { i ->
        if (i.kind == PackKind.LOCAL)
            return@map ImportRow(
                PackKind.LOCAL,
                i.id,
                null,
                null,
                "Not from the registry",
                true,
                false,
            )
        val have = verOf(s, i.id)
        val k =
            when {
                i.id !in s.catalog.map { it.id } -> PackKind.UNAVAILABLE
                have == null -> PackKind.ADD
                have == i.version -> PackKind.SAME
                mode == ImportMode.NEW -> PackKind.OTHER
                compareVersions(i.version.orEmpty(), have) > 0 -> PackKind.UP
                else -> PackKind.CONFLICT
            }
        val text =
            when (k) {
                PackKind.UNAVAILABLE -> "Not in the registry"
                PackKind.ADD -> s.mod(i.id).installable?.sizeKb?.let(::size).orEmpty()
                PackKind.SAME -> "you have it"
                PackKind.UP -> "$have to ${i.version}"
                PackKind.OTHER -> "pack uses ${i.version}, ${s.profile().name} keeps $have"
                else -> "${s.profile().name} has a newer version than the pack"
            }
        ImportRow(k, i.id, i.version, have, text)
    }

data class PackUpdateRow(val kind: PackKind, val id: String, val text: String)

/** Full packs are compared with their saved membership; legacy demo deltas remain readable. */
fun packUpdateItems(s: AppState, pack: Pack): List<PackItem> {
    if (
        pack.items.any {
            it.kind in setOf(PackKind.ADD, PackKind.REM, PackKind.CHG, PackKind.NEWER)
        }
    )
        return pack.items
    val info = s.profile().pack ?: return pack.items
    val previous = info.mods.associateWith { info.memberVersions[it] ?: verOf(s, it).orEmpty() }
    val incoming = pack.items.filter { it.kind != PackKind.LOCAL }.associateBy { it.id }
    return (previous.keys + incoming.keys).map { id ->
        val old = previous[id]
        val next = incoming[id]?.version
        val kind =
            when {
                id !in incoming -> PackKind.REM
                old == null -> PackKind.ADD
                old == next -> PackKind.SAME
                compareVersions(old, next.orEmpty()) > 0 -> PackKind.NEWER
                else -> PackKind.CHG
            }
        PackItem(kind, id, old, next)
    }
}

fun packUpdateRows(s: AppState, pack: Pack) =
    packUpdateItems(s, pack).map { i ->
        PackUpdateRow(
            i.kind,
            i.id,
            when (i.kind) {
                PackKind.ADD -> s.mod(i.id).installable?.sizeKb?.let(::size).orEmpty()
                PackKind.REM -> "turned off, not deleted"
                PackKind.CHG -> "${i.version} to ${i.nextVersion}"
                PackKind.NEWER -> "pack asks for ${i.nextVersion}, you keep ${i.version}"
                else -> "you have it"
            },
        )
    }

fun startTrouble(s: AppState): AppState {
    val on = s.installed.keys.filter { isOn(s, it) }
    val t =
        TroubleSession(
            s.profileId,
            on,
            on,
            rounds = max(1, ceil(log2(max(2, on.size).toDouble())).toInt()),
        )
    return applyTrouble(s.copy(trouble = t), emptyList())
}

private fun applyTrouble(s: AppState, on: List<String>): AppState {
    val t = s.trouble ?: return s
    return s.copy(
        installed =
            s.installed.mapValues { (id, m) ->
                if (id !in t.saved) m
                else
                    m.copy(
                        profiles =
                            m.profiles +
                                (t.profile to ProfileMod(id in on, verOf(s, id, t.profile)!!))
                    )
            }
    )
}

fun troubleRound(s: AppState): AppState {
    val t = s.trouble ?: return s
    val ordered =
        t.suspects
            .flatMap { id -> dependencyClosure(s, id).map { it.id } + id }
            .filter { it in t.saved }
            .distinct()
    val candidates =
        ordered.indices.map { end ->
            ordered
                .take(end + 1)
                .flatMap { id -> listOf(id) + dependencyClosure(s, id).map { it.id } }
                .filter { it in t.saved }
                .toSet()
        }
    val keep =
        candidates
            .filter { group ->
                t.suspects.size == 1 || t.suspects.count { it in group } in 1 until t.suspects.size
            }
            .minByOrNull { kotlin.math.abs(it.size - t.suspects.size / 2) }
            ?: return applyTrouble(
                s.copy(trouble = t.copy(phase = TroublePhase.INCONCLUSIVE)),
                t.saved,
            )

    val on = t.saved.filter { it in keep }
    return applyTrouble(
        s.copy(
            trouble =
                t.copy(
                    phase = TroublePhase.TEST,
                    round = t.round + 1,
                    on = on,
                    off = t.saved - on.toSet(),
                )
        ),
        on,
    )
}

fun troubleAnswer(s: AppState, still: Boolean): AppState {
    val t = s.trouble ?: return s
    val stopped = s
    if (t.phase == TroublePhase.BASE)
        return if (still)
            applyTrouble(stopped.copy(trouble = t.copy(phase = TroublePhase.NOMOD)), t.saved)
        else troubleRound(stopped)
    if (t.phase != TroublePhase.TEST) return s
    val suspects = t.suspects.filter { it in if (still) t.on else t.off }
    if (suspects.size > 1) return troubleRound(stopped.copy(trouble = t.copy(suspects = suspects)))
    if (suspects.isEmpty())
        return applyTrouble(s.copy(trouble = t.copy(phase = TroublePhase.INCONCLUSIVE)), t.saved)
    val found = suspects.first()
    return applyTrouble(
        stopped.copy(
            trouble = t.copy(phase = TroublePhase.FOUND, suspects = suspects, found = found)
        ),
        t.saved.filter { it != found },
    )
}

fun restoreTrouble(s: AppState) =
    s.trouble?.let { applyTrouble(s, it.saved).copy(trouble = null) } ?: s
