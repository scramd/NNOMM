package nnomm.core.io.real

import java.nio.file.*
import kotlin.io.path.*
import kotlinx.coroutines.runBlocking
import nnomm.core.io.*
import nnomm.core.model.*

/** Explicit manual check, never wired into test. Network operation is GET only. */
fun main(args: Array<String>) = runBlocking {
    val home = settingsHome()
    require(
        home.startsWith(
            Path.of(System.getProperty("user.home"), "nnomm-sandbox").toAbsolutePath().normalize()
        )
    ) {
        "Set NNOMM_HOME to the sandbox home for this check."
    }
    Files.createDirectories(home)
    val client = networkClient("0.1.0-integration")
    try {
        val registry = RealRegistry(client, home)
        val mods = registry.read()
        val versions = mods.flatMap { it.versions }
        registry.save(mods, System.currentTimeMillis())
        check(registry.saved()!!.first == mods)
        println(
            "Registry: ${mods.size} mods; ${versions.size} versions; ${versions.count{it.hash!=null}} hashes; ${mods.count{it.githubOwner!=null&&it.githubRepository!=null}} GitHub-backed mods; ${mods.count{it.imageUrl!=null}} images."
        )
        if ("--takeover" in args) {
            val game = home.parent.resolve("game")
            require(game.resolve("NuclearOption.exe").fileSize() == 0L)
            val store = RealModStore(home) { mods }
            val folder =
                RealGameFolder(
                    home,
                    store,
                    RealDownloader(client, home),
                    { mods },
                    running = { false },
                    notice = { println(it) },
                )
            val imported = folder.takeOver(game.toString())
            println(
                "Sandbox takeover: ${imported.size} mods; ${imported.count{it.second.profiles["default"]?.on==true}} on; ${imported.count{it.second.profiles["default"]?.on==false}} off."
            )
        }
    } finally {
        client.close()
    }
}
