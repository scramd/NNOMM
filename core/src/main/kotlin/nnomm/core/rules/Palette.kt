package nnomm.core.rules

import nnomm.core.model.*
import nnomm.core.settings.*

data class PaletteItem(val group: String,val label: String,val hint: String,val action: UiAction,val indices: List<Int> = emptyList(),val alternative: UiAction?=null,val alternativeHint: String?=null,val rank: Int=0)
fun palItems(s: AppState,query: String): List<PaletteItem> {
    val q=query.trim()
    val actions=listOf("Start the game" to "play","Look for mod updates" to "lookForUpdates","Add a mod from a file" to "addFile","Import a modpack" to "import","Share this profile as a modpack" to "share","Find the mod that breaks the game" to "trouble","New profile" to "newProfile","Open the game folder" to "openGameFolder").map{(label,key)->PaletteItem("Actions",label,if(key=="play")"Ctrl Enter" else "",UiAction(key))}
    val pages=listOf("Browse","Library","Settings").map{PaletteItem("Go to",it,"",UiAction("navigate",it.lowercase()))}
    val profiles=if(s.settings.bool("showProfiles"))s.profiles.filter{it.id!=s.profileId}.map{PaletteItem("Profiles",if(q.isEmpty())"Use ${it.name}" else it.name,if(q.isEmpty())"" else "Use this profile",UiAction("useProfile",it.id))} else emptyList()
    if(q.isEmpty())return actions.take(6)+pages+profiles
    fun ranked(items: List<PaletteItem>,limit: Int=Int.MAX_VALUE)=items.mapNotNull{item->fuzzy(q,item.label)?.let{item.copy(indices=it.indices,rank=it.score)}}.sortedByDescending{it.rank}.take(limit)
    val yours=s.installed.keys.map{id->val on=isOn(s,id);PaletteItem("Your mods",s.mod(id).name,if(on)"On" else "Off",UiAction("openMod",id,"about"),alternative=UiAction("paletteToggle",id),alternativeHint=if(on)"turn it off" else "turn it on")}
    val registry=s.catalog.filter{it.id !in s.installed&&it.localFile==null}.map{PaletteItem("Mods in the registry",it.name,"Not installed",UiAction("openMod",it.id,"about"),alternative=UiAction("install",it.id),alternativeHint="install it")}
    val settings=SETTINGS.filter{it.label.isNotEmpty()&&!it.profileOnly}.map{PaletteItem("Settings",it.label,CATS.toMap()[it.cat].orEmpty(),UiAction("openSettings",it.cat))}
    return ranked(yours,4)+ranked(registry,4)+ranked(profiles)+ranked(actions,4)+ranked(pages)+ranked(settings,4)
}
