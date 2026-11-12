package nnomm.core.rules

import nnomm.core.model.*

fun isOn(s: AppState,id: String,p: String=s.profileId)=s.installed[id]?.profiles?.get(p)?.on==true
fun verOf(s: AppState,id: String,p: String=s.profileId)=s.installed[id]?.let { it.profiles[p]?.version ?: it.defaultVersion }
fun packManaged(s: AppState,id: String,p: String=s.profileId)=s.profile(p).pack?.mods?.contains(id)==true
fun hasUpdate(s: AppState,id: String)=s.installed.containsKey(id) && s.mod(id).localFile==null && !packManaged(s,id) && s.mod(id).newestRelease!=null && verOf(s,id)!=s.mod(id).newestRelease?.version
fun updates(s: AppState)=s.installed.keys.filter{hasUpdate(s,it)}
fun onCount(s: AppState,p: String=s.profileId)=s.installed.keys.count{isOn(s,it,p)}
fun usedIn(s: AppState,id: String)=s.profiles.filter{isOn(s,id,it.id)}.map{it.name}
fun sourceOf(s: AppState,id: String)=if(s.installed[id]?.source==ModSource.LOCAL)"Local file" else if(packManaged(s,id))"Modpack" else "Registry"
fun daysAgo(s: AppState,date: Long?)=date?.let { ((s.nowMillis-it)/86400000L).toInt() } ?: Int.MAX_VALUE
fun updOf(s: AppState,id: String)=daysAgo(s,s.mod(id).updated)+(if(s.installed.containsKey(id)&&verOf(s,id)!=s.mod(id).newestRelease?.version)38 else 0)
data class Problem(val kind: String,val key: String,val text: String,val other: String?=null)
fun problems(s: AppState,id: String): List<Problem> {
    val st=s.installed[id]?:return emptyList();val x=s.mod(id);val v=x.newestRelease?:return emptyList()
    return buildList {
        if(st.fileMissing)add(Problem("danger","missing","File missing"))
        if(isOn(s,id)) {
            v.dependencies.forEach{if(!isOn(s,it.id))add(Problem("warn","dep","Needs ${s.mod(it.id).name}",it.id))}
            (v.incompatibilities.map{it.id}+s.catalog.filter{m->m.newestRelease?.incompatibilities?.any{it.id==id}==true}.map{it.id}).forEach { if(isOn(s,it))add(Problem("warn","conflict","Conflicts with ${s.mod(it).name}",it)) }
        }
        if(s.settings.bool("warnOld")&&v.gameVersion!=effectiveGameVersion(s))add(Problem("","old","Made for ${v.gameVersion}"))
    }
}
fun hasProblem(s: AppState,id: String)=problems(s,id).any{it.kind.isNotEmpty()}
data class BrowseFilter(val query: String="",val sort: String="downloads",val tags: List<String> = emptyList(),val tagMode: String="any",val type: String="all",val have: String="all",val fits: Boolean=false) {
    fun toggleHave(v: String)=copy(have=if(have==v)"all" else v)
    fun toggleType(v: String)=copy(type=if(type==v)"all" else v)
    fun toggleTag(v: String)=copy(tags=if(v in tags)tags-v else tags+v)
    fun clear(key: String)=when(key){"tags"->copy(tags=emptyList());"fits"->copy(fits=false);"type"->copy(type="all");"have"->copy(have="all");else->this}
}
data class BrowseRow(val mod: CatalogMod,val match: ModMatch)
fun browseFiltered(b: BrowseFilter)=b.query.isNotBlank()||b.tags.isNotEmpty()||b.type!="all"||b.have!="all"||b.fits
fun browseList(s: AppState,b: BrowseFilter=BrowseFilter(),skip: String?=null): List<BrowseRow> {
    val list=s.catalog.filter{x->
        x.localFile==null && (!s.settings.bool("hideServer")||x.side!=Side.SERVER) &&
        (skip=="type"||b.type=="all"||(if(x.newestRelease?.type==ModType.ADDON)"addon" else "plugin")==b.type) &&
        (skip=="tags"||b.tags.isEmpty()||if(b.tagMode=="all")b.tags.all{it in x.tags} else b.tags.any{it in x.tags}) &&
        (skip=="have"||when(b.have){"new"->x.id !in s.installed;"inst"->x.id in s.installed;"upd"->hasUpdate(s,x.id);else->true}) &&
        (!b.fits||x.newestRelease?.gameVersion==effectiveGameVersion(s))
    }.mapNotNull { x->matchMod(x,b.query.trim())?.let{BrowseRow(x,it)} }
    val comparator=when(b.sort){"updated"->compareBy<BrowseRow>{daysAgo(s,it.mod.updated)};"newest"->compareBy{daysAgo(s,it.mod.added)};"name"->compareBy{it.mod.name.lowercase()};else->compareByDescending{it.mod.downloadCount}}
    return list.sortedWith(if(b.query.isNotBlank())compareByDescending<BrowseRow>{it.match.score}.thenByDescending{it.mod.downloadCount} else comparator)
}
fun tagCounts(s: AppState,b: BrowseFilter=BrowseFilter()): Map<String,Int> {
    val all=s.catalog.flatMap{it.tags}.groupingBy{it}.eachCount().entries.sortedByDescending{it.value}.map{it.key}
    val base=browseList(s,b,"tags").map{it.mod}.filter{b.tagMode!="all"||b.tags.all{t->t in it.tags}}
    return all.associateWith{t->base.count{t in it.tags}}
}
data class BrowseSection(val title: String,val subtitle: String,val mods: List<CatalogMod>)
fun browseSections(s: AppState,b: BrowseFilter=BrowseFilter()): List<BrowseSection> {
    if(browseFiltered(b)||b.sort!="downloads")return emptyList()
    val pool=browseList(s,b).map{it.mod}
    val fresh=pool.filter{daysAgo(s,it.updated)<=7}.sortedBy{daysAgo(s,it.updated)}
    val new=pool.filter{daysAgo(s,it.added)<=60}.sortedBy{daysAgo(s,it.added)}
    val yours=pool.filter{it.id !in s.installed && it.newestRelease!!.dependencies.isNotEmpty()&&it.newestRelease!!.dependencies.all{d->d.id in s.installed}}
    val base=yours.flatMap{it.newestRelease!!.dependencies}.map{it.id}.distinct().joinToString(" and "){s.mod(it).name}
    return listOf(BrowseSection("Updated this week",plural(fresh.size,"mod","mods"),fresh),BrowseSection("New","first release in the last 2 months",new),BrowseSection("For mods you have","they need $base, which you have",yours)).filter{it.mods.isNotEmpty()}
}
data class LibraryFilter(val filter: String="all",val query: String="",val sort: String="name",val direction: Int=1)
fun libCounts(s: AppState)=mapOf("all" to s.installed.size,"on" to onCount(s),"off" to s.installed.size-onCount(s),"upd" to updates(s).size,"local" to s.installed.values.count{it.source==ModSource.LOCAL},"prob" to s.installed.keys.count{hasProblem(s,it)})
fun libList(s: AppState,l: LibraryFilter=LibraryFilter()): List<String> {
    val ids=s.installed.keys.filter{id->when(l.filter){"on"->isOn(s,id);"off"->!isOn(s,id);"upd"->hasUpdate(s,id);"local"->s.installed[id]?.source==ModSource.LOCAL;"prob"->hasProblem(s,id);else->true} && (l.query.isBlank()||fuzzy(l.query.trim(),s.mod(id).name)!=null)}
    fun cmp(a: String,b: String): Int=when(l.sort) {
        "size"->(s.mod(a).newestRelease?.sizeKb?:0).compareTo(s.mod(b).newestRelease?.sizeKb?:0)
        "upd"->updOf(s,a).compareTo(updOf(s,b))
        "source"->sourceOf(s,a).compareTo(sourceOf(s,b))
        "on"->(!isOn(s,a)).compareTo(!isOn(s,b))
        "ver"->(!hasUpdate(s,a)).compareTo(!hasUpdate(s,b))
        else->s.mod(a).name.lowercase().compareTo(s.mod(b).name.lowercase())
    }
    return ids.sortedWith {a,b->(cmp(a,b)*l.direction).takeIf{it!=0}?:s.mod(a).name.compareTo(s.mod(b).name)}
}
data class ModAction(val label: String,val action: UiAction?=null,val disabled: Boolean=false,val tooltip: String?=null,val percent: Int?=null)
fun modAction(s: AppState,id: String): ModAction {
    s.downloads.firstOrNull{it.id==id&&it.percent<100&&it.error==null}?.let{return ModAction("${it.percent}%",percent=it.percent)}
    val off=s.connection.kind==ConnectionKind.OFFLINE
    if(id !in s.installed)return ModAction("Install",UiAction("install",id),off,if(off)"Needs a connection" else null)
    if(s.installed.getValue(id).fileMissing)return ModAction("Repair",UiAction("repair",id),tooltip="The file is missing. Download it again.")
    if(hasUpdate(s,id))return ModAction("Update",UiAction("update",id),off,"${verOf(s,id)} to ${s.mod(id).newestRelease?.version}")
    if(!isOn(s,id))return ModAction("Turn on",UiAction("turnOn",id),tooltip="Installed. Off in ${s.profile().name}.")
    return ModAction("On",tooltip="Installed and on in ${s.profile().name}")
}
data class FixItem(val label: String,val action: UiAction,val danger: Boolean=false)
fun problemFixes(s: AppState,id: String,p: Problem): List<FixItem> {
    fun off(q: String)=FixItem("Turn off ${s.mod(q).name}",UiAction("turnOff",q))
    return when(p.key){
        "dep"->listOf(FixItem("${if(p.other in s.installed)"Turn on" else "Install"} ${s.mod(p.other!!).name}",UiAction(if(p.other in s.installed)"turnOn" else "install",p.other)),off(id))
        "conflict"->listOf(off(p.other!!),off(id),FixItem("See why they conflict",UiAction("openMod",id,"needs")))
        "missing"->listOf(FixItem("Download it again",UiAction("repairProblem",id)),FixItem("Remove it from the list",UiAction("uninstall",id),true))
        else->emptyList()
    }
}

/** Unknown disk version uses the newest game version named by the registry. */
fun effectiveGameVersion(s: AppState): String? = s.game.version?.takeUnless{it=="unknown"} ?: s.catalog.flatMap{it.versions}.mapNotNull{it.gameVersion}.maxWithOrNull(::compareVersions)
