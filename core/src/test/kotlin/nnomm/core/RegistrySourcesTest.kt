package nnomm.core

import kotlin.test.*
import kotlinx.serialization.json.JsonPrimitive
import nnomm.core.io.real.DEFAULT_REGISTRY
import nnomm.core.model.SettingsValues
import nnomm.core.settings.*

class RegistrySourcesTest {
    private val other = "https://example.com/manifest.json"

    private fun values(sources: String?, off: String? = null) =
        SettingsValues(
            buildMap {
                sources?.let { put("sources", JsonPrimitive(it)) }
                off?.let { put("sourcesOff", JsonPrimitive(it)) }
            }
        )

    private fun SettingsValues.apply(change: Pair<String, String>) =
        copy(general = general + ("sources" to JsonPrimitive(change.first)) + ("sourcesOff" to JsonPrimitive(change.second)))

    @Test
    fun anAddedRegistryTurnedOffStaysListedAndTurnsOnAgain() {
        var v = values("$DEFAULT_REGISTRY\n$other")
        assertEquals(listOf(RegistrySource(DEFAULT_REGISTRY, true), RegistrySource(other, true)), registrySources(v))
        v = v.apply(changeRegistry(v, other, false))
        assertEquals(DEFAULT_REGISTRY, v.general["sources"]?.content)
        assertEquals(listOf(RegistrySource(DEFAULT_REGISTRY, true), RegistrySource(other, false)), registrySources(v))
        v = v.apply(changeRegistry(v, other, true))
        assertEquals(listOf(RegistrySource(DEFAULT_REGISTRY, true), RegistrySource(other, true)), registrySources(v))
        assertEquals("", v.general["sourcesOff"]?.content)
    }

    @Test
    fun removeTakesAnAddedRegistryOffTheListButNotNomnom() {
        var v = values("$DEFAULT_REGISTRY\n$other")
        v = v.apply(changeRegistry(v, other, null))
        assertEquals(listOf(RegistrySource(DEFAULT_REGISTRY, true)), registrySources(v))
        v = v.apply(changeRegistry(v, DEFAULT_REGISTRY, null))
        assertEquals(listOf(RegistrySource(DEFAULT_REGISTRY, true)), registrySources(v))
    }

    @Test
    fun nomnomCanBeTurnedOffAndIsStillListed() {
        var v = values(null)
        assertEquals(listOf(RegistrySource(DEFAULT_REGISTRY, true)), registrySources(v))
        v = v.apply(changeRegistry(v, DEFAULT_REGISTRY, false))
        assertEquals("", v.general["sources"]?.content)
        assertEquals("", v.general["sourcesOff"]?.content)
        assertEquals(listOf(RegistrySource(DEFAULT_REGISTRY, false)), registrySources(v))
    }

    @Test
    fun theOffListIsAKnownSetting() {
        assertTrue(SETTINGS.any { it.key == "sourcesOff" && it.type == "hidden" })
    }
}
