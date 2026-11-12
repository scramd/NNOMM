package nnomm.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import nnomm.core.demo.Demo
import nnomm.core.model.ActionKind
import nnomm.core.rules.problemFixes
import nnomm.core.rules.problems

class PlacementFixesTest {
    @Test
    fun eachCollisionBadgeOffersOnlyItsOwnTurnOff() {
        val ids = listOf("minimap", "delivery")
        val state = Demo.state().copy(
            applyProblems = ids.associateWith {
                "Two enabled mods use the same game folder name. Turn off one of them and Retry."
            },
        )
        ids.forEach { id ->
            val problem = problems(state, id).single { it.key == "placement" }
            val fixes = problemFixes(state, id, problem)
            assertEquals(listOf(ActionKind.RETRYAPPLY, ActionKind.TURNOFF), fixes.map { it.action.kind })
            assertEquals(id, fixes.last().action.id)
            assertEquals("Turn off ${state.mod(id).name}", fixes.last().label)
        }
    }
    @Test
    fun playCollisionDoesNotOfferBulkTurnOff() = kotlinx.coroutines.runBlocking {
        val io = Demo.io()
        val failures = kotlinx.coroutines.flow.MutableStateFlow(
            listOf("minimap", "delivery").associateWith {
                "Two enabled mods use the same game folder name. Turn off one of them and Retry."
            },
        )
        val folder = object : nnomm.core.io.GameFolder by io.gameFolder {
            override val applySucceeded = false
            override val applyProblems = failures
            override suspend fun apply(plan: nnomm.core.io.GamePlan) {
                error("Placement refused")
            }
        }
        val store = nnomm.core.store.AppStore(Demo.state(), io.copy(gameFolder = folder))
        try {
            store.play()
            kotlinx.coroutines.withTimeout(5000) {
                while (store.state.value.toasts.none { it.action == "Retry" }) {
                    kotlinx.coroutines.delay(10)
                }
            }
            kotlinx.coroutines.delay(100)
            assertEquals(false, store.state.value.toasts.any { it.action == "Turn off" })
            failures.value.keys.forEach { id ->
                assertEquals(true, nnomm.core.rules.isOn(store.state.value, id))
            }
        } finally {
            store.shutdown()
        }
    }
}
