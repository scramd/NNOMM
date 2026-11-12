package nnomm.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlin.math.*
import nnomm.core.model.TroublePhase
import nnomm.core.model.TroubleChoice
import nnomm.core.rules.*
import nnomm.ui.*
import nnomm.ui.kit.*

class TroubleDialog(private val model: AppModel) : Overlay {
    @Composable
    override fun Content() {
        val state by model.store.state.collectAsState()
        // Shell Escape and Ctrl K can replace the layer without calling its dismiss callback.
        DisposableEffect(model) { onDispose { model.store.closeBrokenModTest() } }
        val t = state.trouble
        val phase = t?.phase
        val on = state.installed.keys.count { isOn(state, it) }
        fun close() {
            model.store.closeBrokenModTest()
            model.closeOverlay()
        }
        Dialog(
            true,
            when (phase) {
                TroublePhase.FOUND -> "Found the mod"
                TroublePhase.NOMOD -> "A mod is not the cause"
                else -> "Find the mod that breaks the game"
            },
            ::close,
            leftFooter = {
                if (phase in listOf(TroublePhase.BASE, TroublePhase.TEST))
                    Button(
                        "Stop and turn my mods back on",
                        {
                            model.store.stopBrokenModTest()
                            model.closeOverlay()
                        },
                        kind = ButtonKind.Ghost,
                    )
                if (phase == TroublePhase.FOUND && t?.found != null)
                    Button(
                        "Open the mod page",
                        {
                            model.ui.openMod(t.found!!)
                            close()
                        },
                        kind = ButtonKind.Ghost,
                    )
            },
            footer = {
                when (phase) {
                    null -> {
                        Cancel(model)
                        Button(
                            "Start",
                            { model.store.startBrokenModTest() },
                            kind = ButtonKind.Strong,
                        )
                    }
                    TroublePhase.BASE,
                    TroublePhase.TEST -> {
                        Button("Play", { model.store.play() }, icon = NnIcons.play)
                        Button("The problem is gone", { model.store.answerBrokenModTest(false) })
                        Button(
                            "Still there",
                            { model.store.answerBrokenModTest(true) },
                            kind = ButtonKind.Strong,
                        )
                    }
                    TroublePhase.FOUND -> {
                        Button(
                            "Keep it on",
                            {
                                model.store.endBrokenModTest(TroubleChoice.KEEP)
                                model.closeOverlay()
                            },
                        )
                        Button(
                            "Remove it",
                            {
                                model.store.endBrokenModTest(TroubleChoice.REMOVE)
                                model.closeOverlay()
                            },
                            kind = ButtonKind.Danger,
                        )
                        Button(
                            "Leave it off",
                            {
                                model.store.endBrokenModTest(TroubleChoice.OFF)
                                model.closeOverlay()
                            },
                            kind = ButtonKind.Strong,
                        )
                    }
                    else -> Button("Close", ::close, kind = ButtonKind.Strong)
                }
            },
        ) {
            when (phase) {
                TroublePhase.INCONCLUSIVE -> {
                    Label("These mods cannot be split into separate groups because they need each other. This test cannot find the cause. Your mods are on again, as before.")
                }
                null -> {
                    Label(
                        "Use this when the game crashes or behaves wrongly and you do not know which mod is the cause."
                    )
                    val rounds = max(1, ceil(log2(max(2, on).toDouble())).toInt())
                    Label(
                        "NNOMM turns off half of your $on mods. You start the game and say if the problem is still there. After about $rounds rounds one mod is left."
                    )
                    Label(
                        "A mod stays on while another mod in the test needs it. At the end, or when you stop, NNOMM puts ${state.profile().name} back exactly as it is now."
                    )
                    Label("If the problem needs two mods together, this test cannot find it.", color = nnomm.ui.theme.LocalNnTheme.current.colors.text3)
                }
                TroublePhase.BASE -> {
                    Label(
                        "First check: all ${t!!.saved.size} mods are off now. Start the game and do what causes the problem."
                    )
                    Label(
                        "If the problem is still there, a mod is not the cause, and the test stops."
                    )
                }
                TroublePhase.TEST -> {
                    Summary(
                        listOf(
                            "${t!!.round} of ${t.rounds}" to "round",
                            "${t.on.size}" to "mods on",
                            "${t.off.size}" to "mods off",
                            "${t.suspects.size}" to "still possible",
                        )
                    )
                    Label(
                        "Start the game and do what causes the problem. Then come back and answer."
                    )
                    DiffBox(150) {
                        t.on.forEach { DiffLine("on", state.mod(it).name) {} }
                        t.off.forEach { DiffLine("off", state.mod(it).name) {} }
                    }
                }
                TroublePhase.NOMOD -> {
                    Label(
                        "The problem is there with all mods off, so a mod is not the cause. Your mods are on again, as before."
                    )
                    Label(
                        "Next steps: \"Verify integrity of game files\" in Steam, or Repair for the mod loader in Settings, Game."
                    )
                }
                TroublePhase.FOUND -> {
                    t?.found?.let { id ->
                        val mod = state.mod(id)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ModTile(id, mod.name, size = TileSize.Medium)
                            Column {
                                Label(mod.name, size = 15f, weight = 700)
                                Label(
                                    "${mod.authors.joinToString(", ")}, version ${verOf(state, id)}, made for game ${mod.newestRelease?.gameVersion.orEmpty()}"
                                )
                            }
                        }
                    }
                    Label(
                        "In the test, the problem followed this mod: it was there with the mod on and gone with it off. Your other mods are on again."
                    )
                }
            }
        }
    }
}
