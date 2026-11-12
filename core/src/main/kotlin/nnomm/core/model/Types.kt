package nnomm.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DownloadKind {
    @SerialName("install") INSTALL,
    @SerialName("update") UPDATE,
    @SerialName("repair") REPAIR,
    @SerialName("repairProblem") REPAIRPROBLEM,
}

@Serializable
enum class TroublePhase {
    @SerialName("base") BASE,
    @SerialName("test") TEST,
    @SerialName("nomod") NOMOD,
    @SerialName("found") FOUND,
    @SerialName("inconclusive") INCONCLUSIVE,
}

@Serializable
enum class ImportMode {
    @SerialName("new") NEW,
    @SerialName("add") ADD,
}

@Serializable
enum class TroubleChoice {
    @SerialName("off") OFF,
    @SerialName("keep") KEEP,
    @SerialName("remove") REMOVE,
}

@Serializable
enum class BrowseSort {
    @SerialName("downloads") DOWNLOADS,
    @SerialName("updated") UPDATED,
    @SerialName("newest") NEWEST,
    @SerialName("name") NAME,
}

@Serializable
enum class LibrarySort {
    @SerialName("name") NAME,
    @SerialName("size") SIZE,
    @SerialName("upd") UPD,
    @SerialName("source") SOURCE,
    @SerialName("on") ON,
    @SerialName("ver") VER,
}

@Serializable
enum class ActionKind {
    @SerialName("retryApply") RETRYAPPLY,
    @SerialName("play") PLAY,
    @SerialName("lookForUpdates") LOOKFORUPDATES,
    @SerialName("addFile") ADDFILE,
    @SerialName("import") IMPORT,
    @SerialName("share") SHARE,
    @SerialName("trouble") TROUBLE,
    @SerialName("newProfile") NEWPROFILE,
    @SerialName("openGameFolder") OPENGAMEFOLDER,
    @SerialName("navigate") NAVIGATE,
    @SerialName("useProfile") USEPROFILE,
    @SerialName("openMod") OPENMOD,
    @SerialName("paletteToggle") PALETTETOGGLE,
    @SerialName("install") INSTALL,
    @SerialName("openSettings") OPENSETTINGS,
    @SerialName("libraryFilter") LIBRARYFILTER,
    @SerialName("repair") REPAIR,
    @SerialName("update") UPDATE,
    @SerialName("turnOn") TURNON,
    @SerialName("turnOff") TURNOFF,
    @SerialName("repairProblem") REPAIRPROBLEM,
    @SerialName("uninstall") UNINSTALL,
}

@Serializable
enum class DownloadStatus {
    ACTIVE,
    COMPLETE,
    FAILED,
}

@Serializable
enum class PackKind {
    @SerialName("add") ADD,
    @SerialName("same") SAME,
    @SerialName("up") UP,
    @SerialName("conflict") CONFLICT,
    @SerialName("other") OTHER,
    @SerialName("local") LOCAL,
    @SerialName("ver") VER,
    @SerialName("rem") REM,
    @SerialName("chg") CHG,
    @SerialName("newer") NEWER,
    @SerialName("unavailable") UNAVAILABLE,
}

@Serializable
enum class LoadStatus {
    IDLE,
    LOADING,
    READY,
    FAILED,
}

@Serializable
enum class ProblemKind {
    @SerialName("") INFO,
    @SerialName("warn") WARNING,
    @SerialName("danger") DANGER,
}

@Serializable
enum class MatchKind {
    @SerialName("name") NAME,
    @SerialName("tag") TAG,
    @SerialName("author") AUTHOR,
    @SerialName("description") DESCRIPTION,
}
