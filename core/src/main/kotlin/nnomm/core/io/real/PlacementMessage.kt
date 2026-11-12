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
            "These files need recovery" ->
                "these files need recovery. Their copies and journal are kept. Restore unavailable storage, then Retry."
            "Could not keep player files" ->
                "its player files could not be saved. Your game files were left unchanged. Retry or turn it off."
            else ->
                when (error) {
                    is java.nio.file.AccessDeniedException,
                    is java.nio.file.FileSystemException ->
                        "a file could not be changed. Check folder access and close programs using it, then Retry."
                    else ->
                        "the change could not be completed. Your files have been kept. Choose Retry to try again, or turn it off; see the problem report for details."
                }
        }
    return "Could not place $name: $advice"
}
