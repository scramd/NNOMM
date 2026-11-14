package nnomm.core.settings

import nnomm.core.io.real.DEFAULT_REGISTRY
import nnomm.core.model.SettingsValues

/** One registry address in Settings > Registry, with its switch. */
data class RegistrySource(val address: String, val on: Boolean)

private fun lines(v: SettingsValues, key: String) =
    v.general[key]?.content?.split(Regex("[\\n,;]+"))?.map { it.trim() }?.filter { it.isNotEmpty() }

/**
 * "sources" holds the registries NNOMM reads (RealRuntime reads it). "sourcesOff" holds added
 * registries that are turned off, so that they stay in the list and can be turned on again.
 * NOMNOM is always listed first.
 */
fun registrySources(v: SettingsValues): List<RegistrySource> {
    val on = lines(v, "sources") ?: listOf(DEFAULT_REGISTRY)
    val off = lines(v, "sourcesOff").orEmpty()
    return (listOf(DEFAULT_REGISTRY) + on + off).distinct().map { RegistrySource(it, it in on) }
}

/**
 * The new values of "sources" and "sourcesOff" after [address] is turned on or off, or removed
 * from the list ([on] is null). NOMNOM cannot be removed; it only turns off.
 */
fun changeRegistry(v: SettingsValues, address: String, on: Boolean?): Pair<String, String> {
    val list = registrySources(v).toMutableList()
    val i = list.indexOfFirst { it.address == address }
    when {
        on == null -> if (i >= 0 && address != DEFAULT_REGISTRY) list.removeAt(i)
        i >= 0 -> list[i] = RegistrySource(address, on)
        else -> list += RegistrySource(address, on)
    }
    return list.filter { it.on }.joinToString("\n") { it.address } to
        list.filter { !it.on && it.address != DEFAULT_REGISTRY }.joinToString("\n") { it.address }
}
