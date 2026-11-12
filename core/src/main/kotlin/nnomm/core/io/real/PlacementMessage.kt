package nnomm.core.io.real

internal fun placementMessage(name: String, error: Exception): String {
    val advice =
        when (error.message) {
            "Stored files are missing" ->
                "its stored files are missing. Choose Repair in the mod's menu and try again."
            "Its parent must be placed first" ->
                "a required mod could not be placed. Turn on or repair the required mod and try again."
            "Game path is too long" ->
                "the game folder address is too long. Move the game to a shorter folder address and choose it in Settings."
            else -> "a file could not be changed. Close the program that uses it and try again."
        }
    return "Could not place $name: $advice"
}
