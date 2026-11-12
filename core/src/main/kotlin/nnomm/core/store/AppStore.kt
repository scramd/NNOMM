package nnomm.core.store

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonPrimitive
import nnomm.core.model.*
import nnomm.core.io.*
import nnomm.core.rules.*
import nnomm.core.settings.*

/** UI collects [state]. Actions return immediately; all IO uses the supplied worker scope.
 * Call [close] when the application closes. Inject a scope to control scheduling in tests.
 */
class AppStore(initial: AppState,val io: AppIo,scope: CoroutineScope?=null): AutoCloseable {
    private val owned=scope==null
    private val worker=scope?:CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(initial)
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private val lock=Any()
    private var sequence=0L
    private val slots=Semaphore(3)
    private val jobs=mutableMapOf<String,Job>()
    // Preserve disk mutation order, including an Undo issued before remove finishes.
    private val diskQueue=Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val diskWorker=worker.launch {
        for (operation in diskQueue) {
            try { operation() }
            catch(e: CancellationException){throw e}
            catch(e: Exception){toast(e.message?:"The action could not be completed.")}
        }
    }
    private fun disk(block: suspend () -> Unit) { diskQueue.trySend(block).getOrThrow() }
    private suspend fun <T> diskAwait(block: suspend () -> T): T {
        val result=CompletableDeferred<T>()
        disk { try { result.complete(block()) } catch(e: Exception){result.completeExceptionally(e)} }
        return result.await()
    }
    private val launcherObserver=worker.launch {
        io.launcher.running.drop(1).collect { running -> change { it.copy(gameRunning=running) } }
    }
    private var saveFailed=false
    @OptIn(FlowPreview::class)
    private val persistenceJob=io.persistence?.let { persistence -> worker.launch {
        state.debounce(350).collect { snapshot ->
            try { persistence.save(snapshot);saveFailed=false } catch(e: CancellationException){throw e} catch(e: Exception){ /* Report once; avoid a toast/save failure loop. */
                if(!saveFailed){saveFailed=true;toast("Could not save your changes: ${e.message}",ms=30000)}
            }
        }
    }}
    suspend fun flush(){io.persistence?.save(state.value);io.settingsFile.write(state.value.settings)}
    private fun change(f: (AppState)->AppState) { synchronized(lock){mutable.value=f(mutable.value)} }
    private fun background(block: suspend ()->Unit)=worker.launch { try{block()}catch(e: CancellationException){throw e}catch(e: Exception){toast(e.message?:"The action could not be completed.")} }
    private fun toast(text: String,action: String?=null,target: UiAction?=null,ms: Long=7000,undo: (() -> Unit)?=null) {
        val id=synchronized(lock){++sequence}
        change{it.copy(toasts=(it.toasts+Toast(id,text,action,target,ms,undo)).takeLast(3))}
        worker.launch{delay(ms);change{it.copy(toasts=it.toasts.filterNot{t->t.id==id})}}
    }
    fun dismissToast(id: Long){change{it.copy(toasts=it.toasts.filterNot{t->t.id==id})}}
    fun runToast(id: Long): UiAction? {val t=state.value.toasts.find{it.id==id}?:return null;dismissToast(id);t.callback?.invoke();return t.target}
    fun undo(){state.value.toasts.lastOrNull{it.action=="Undo"}?.let{runToast(it.id)}}
    private fun setProfileMod(s: AppState,id: String,p: String,on: Boolean?=null,version: String?=null): AppState {
        val m=s.installed[id]?:return s
        val old=m.profiles[p]?:ProfileMod(false,m.defaultVersion)
        val v=version?:old.version
        return s.copy(installed=s.installed+(id to m.copy(versions=m.versions+v,profiles=m.profiles+(p to old.copy(on=on?:old.on,version=v)))))
    }
    private fun applyAndSave() {
        val snapshot=state.value
        disk {
            if(io.persistence==null)io.settingsFile.write(snapshot.settings)
            if(!snapshot.gameRunning) {
                if(onCount(snapshot)>0&&!snapshot.game.loaderPresent) {
                    snapshot.game.folder?.let { folder ->
                        io.gameFolder.installLoader(folder)
                        val game=io.gameFolder.inspect(folder)
                        change{it.copy(game=game)}
                        toast("Installed the mod loader BepInEx 5.4.23.4. Mods need it to run.")
                    }
                }
                io.gameFolder.apply(snapshot,snapshot.profileId)
            }
        }
    }
    private fun queue(id: String,kind: String,p: String=state.value.profileId,version: String?=null,quiet: Boolean=false,with: List<String> = emptyList()) {
        synchronized(lock) {
            val s=state.value
            if(s.downloads.any{it.id==id&&it.percent<100&&it.error==null})return
            val x=s.mod(id);val v=version?:x.newestRelease!!.version
            val d=Download(id,kind,p,v,quiet=quiet,withDependencies=with)
            change{it.copy(downloads=it.downloads.filterNot{it.id==id}+d)}
            val job=worker.launch(start=CoroutineStart.LAZY) {
                try {
                    slots.withPermit {
                        val artifact=x.versions.firstOrNull{it.version==v}?:x.newestRelease!!.copy(version=v)
                        val file=io.downloader.download(x,artifact){pct->change{st->st.copy(downloads=st.downloads.map{if(it.id==id)it.copy(percent=pct.coerceIn(0,99)) else it})}}
                        diskAwait { io.modStore.put(id,v,file) }
                        var finished=false
                        change { st->
                            if(st.profiles.none{it.id==p} || ((kind=="update" || kind.startsWith("repair")) && id !in st.installed))return@change st.copy(downloads=st.downloads.map{if(it.id==id)it.copy(percent=100) else it})
                            finished=true
                            val next=if(kind=="update")setProfileMod(st,id,p,version=v)
                            else if(kind.startsWith("repair")) {
                                val m=st.installed[id]
                                if(m==null)st else st.copy(installed=st.installed+(id to m.copy(fileMissing=false)))
                            } else {
                                val old=st.installed[id]
                                val m=old?:InstalledMod(id,versions=setOf(v),defaultVersion=v,profiles=st.profiles.associate{it.id to ProfileMod(false,v)})
                                setProfileMod(st.copy(installed=st.installed+(id to m.copy(fileMissing=false))),id,p,true,v)
                            }
                            next.copy(downloads=next.downloads.map{if(it.id==id)it.copy(percent=100) else it})
                        }
                        if(finished&&!quiet) {
                            if(kind.startsWith("repair"))toast(if(kind=="repairProblem")"Repaired ${x.name}" else "Downloaded ${x.name} again")
                            else if(kind=="update")toast("Updated ${x.name} to ${x.newestRelease!!.version}","What's new",UiAction("openMod",id,"new"))
                            else toast("Installed ${x.name} and turned it on in ${state.value.profile(p).name}","Undo",undo={uninstallSilently(listOf(id)+with)})
                        }
                        applyAndSave()
                    }
                }catch(e: CancellationException){throw e}catch(e: Exception){change{it.copy(downloads=it.downloads.map{d->if(d.id==id)d.copy(error=e.message?:"Download failed") else d})};toast("Could not download ${x.name}.")}
            }
            jobs[id]=job;job.start()
        }
    }
    fun install(id: String,quiet: Boolean=false) {
        val s=state.value;if(s.connection.kind==ConnectionKind.OFFLINE)return
        val need=if(s.settings.bool("autoDeps"))s.mod(id).newestRelease!!.dependencies.map{it.id}.filter{it !in s.installed} else emptyList()
        need.forEach{queue(it,"install",s.profileId,quiet=true)}
        queue(id,"install",s.profileId,quiet=quiet,with=need)
        if(need.isNotEmpty()&&!quiet)toast("${s.mod(id).name} needs ${need.joinToString(" and "){s.mod(it).name}}. Installing both.")
        s.mod(id).newestRelease!!.dependencies.filter{it.id in s.installed&&!isOn(s,it.id)}.forEach{setOn(listOf(it.id),true,s.profileId)}
    }
    fun update(id: String){if(state.value.connection.kind!=ConnectionKind.OFFLINE)queue(id,"update")}
    fun updateAll(){updates(state.value).forEach(::update)}
    fun lookForUpdates(){toast("${updates(state.value).size} mods have an update","Show",UiAction("libraryFilter",value="upd"))}
    fun paletteToggle(id: String) {
        toggle(id)
        val s=state.value
        toast("${s.mod(id).name} is ${if(isOn(s,id))"on" else "off"}"+if(s.settings.bool("showProfiles")&&s.profiles.size>1)" in ${s.profile().name}" else "")
    }
    fun cancelDownload(id: String){synchronized(lock){jobs.remove(id)?.cancel()};change{it.copy(downloads=it.downloads.filterNot{it.id==id})}}
    fun clearFinished(){change{it.copy(downloads=it.downloads.filter{it.percent<100})}}
    fun setOn(ids: List<String>,on: Boolean,p: String=state.value.profileId) {
        val before=state.value;change{s->ids.fold(s){acc,id->setProfileMod(acc,id,p,on)}}
        if(ids.size>1)toast("${if(on)"Turned on" else "Turned off"} ${ids.size} mods","Undo",undo={change{s->ids.fold(s){acc,id->setProfileMod(acc,id,p,isOn(before,id,p))}};applyAndSave()})
        applyAndSave()
    }
    fun toggle(id: String,p: String=state.value.profileId)=setOn(listOf(id),!isOn(state.value,id,p),p)
    private fun uninstallSilently(ids: List<String>) {ids.forEach(::cancelDownload);change{it.copy(installed=it.installed-ids.toSet())};disk{io.modStore.remove(ids)};applyAndSave()}
    fun uninstall(ids: List<String>) {
        if(ids.isEmpty())return
        val s=state.value;val keep=s.installed.filterKeys{it in ids}
        val where=if(ids.size==1&&s.settings.bool("showProfiles")&&s.profiles.size>1)usedIn(s,ids[0]) else emptyList()
        uninstallSilently(ids)
        val text=if(ids.size==1)"Uninstalled ${s.mod(ids[0]).name}"+(if(where.isNotEmpty())". It was on in ${where.joinToString(" and ")}" else "") else "Uninstalled ${ids.size} mods"+(if(s.settings.bool("showProfiles")&&s.profiles.size>1)" from all profiles" else "")
        toast(text,"Undo",ms=8000,undo={change{it.copy(installed=it.installed+keep)};disk{io.modStore.restore(ids)};applyAndSave()})
    }
    fun changeVersion(id: String,version: String,p: String=state.value.profileId) {
        val s=state.value
        if(verOf(s,id,p)==version)return
        queue(id,"update",p,version,true)
        toast("Changing ${s.mod(id).name} to $version"+if(s.settings.bool("showProfiles")&&s.profiles.size>1)" in ${s.profile(p).name}" else "")
    }
    fun repair(id: String,fromProblem: Boolean=false) {
        queue(id,if(fromProblem)"repairProblem" else "repair",version=verOf(state.value,id))
    }
    fun useProfile(id: String) {
        if(state.value.trouble!=null){toast("Finish or stop the broken-mod test before you change the profile.","Show the test",UiAction("trouble"));return}
        if(state.value.profiles.none{it.id==id})return
        change{it.copy(profileId=id)};applyAndSave()
    }
    fun newProfile(name: String="",copyCurrent: Boolean=state.value.settings.general["newFrom"]?.content!="With all mods off",ownSettings: Boolean=state.value.settings.bool("ownSettings")): String {
        val id="p"+synchronized(lock){++sequence};val from=state.value.profileId
        val p=Profile(id,name.trim().ifEmpty{"New profile"})
        change{s->s.copy(profiles=s.profiles+p,profileId=id,installed=s.installed.mapValues{(_,m)->m.copy(profiles=m.profiles+(id to ProfileMod(copyCurrent&&(m.profiles[from]?.on==true),m.profiles[from]?.version?:m.defaultVersion)))},settings=s.settings.copy(overrides=s.settings.overrides+(id to ((if(copyCurrent)s.settings.overrides[from].orEmpty() else emptyMap())+("ownSettingsP" to JsonPrimitive(ownSettings))))))}
        toast("Made the profile ${p.name}");applyAndSave();return id
    }
    fun copyProfile(id: String): String {
        val s=state.value;val p=s.profile(id);val new="p"+synchronized(lock){++sequence}
        change{it.copy(profiles=it.profiles+Profile(new,p.name+" copy"),profileId=new,installed=it.installed.mapValues{(_,m)->m.copy(profiles=m.profiles+(new to (m.profiles[id]?:ProfileMod(false,m.defaultVersion))))},settings=it.settings.copy(overrides=it.settings.overrides+(new to it.settings.overrides[id].orEmpty())))}
        toast("Made ${p.name} copy");applyAndSave();return new
    }
    fun renameProfile(id: String,name: String){change{it.copy(profiles=it.profiles.map{p->if(p.id==id)p.copy(name=name.trim().ifEmpty{p.name}) else p})}}
    fun deleteProfile(id: String) {
        if(id=="default")return
        val s=state.value;val p=s.profile(id);val index=s.profiles.indexOf(p)
        change{it.copy(profiles=it.profiles-p,profileId=if(it.profileId==id)"default" else it.profileId)}
        toast("Deleted the profile ${p.name}. Its mods stay installed.","Undo",undo={change{st->st.copy(profiles=st.profiles.toMutableList().apply{add(index.coerceAtMost(size),p)})};applyAndSave()});applyAndSave()
    }
    fun setSetting(key: String,value: JsonPrimitive,profile: String?=null){
        change{nnomm.core.settings.setSetting(it,key,value,profile)}
        if(key=="gamePath")background {
            val game=if(value.content.isBlank())GameInfo() else io.gameFolder.inspect(value.content)
            change{it.copy(game=game)};applyAndSave()
        } else applyAndSave()
    }
    fun ovset(key: String,p: String=state.value.profileId){change{nnomm.core.settings.ovset(it,key,p)};applyAndSave()}
    fun ovreset(key: String,p: String=state.value.profileId){change{nnomm.core.settings.ovreset(it,key,p)};applyAndSave()}
    fun ovclear(p: String=state.value.profileId){change{nnomm.core.settings.ovclear(it,p)};applyAndSave()}
    fun play(withoutMods: Boolean=false) {
        val s=state.value;if(s.gameRunning)return
        change{it.copy(gameRunning=true)}
        toast(if(withoutMods)"Starting Nuclear Option without mods. Your profile stays as it is." else "Starting Nuclear Option with ${onCount(s)} mods",ms=4000)
        background{try{if(!withoutMods)diskAwait{io.gameFolder.apply(s,s.profileId)};io.launcher.start(s,withoutMods)}catch(e: Exception){change{it.copy(gameRunning=false)};throw e}}
    }
    fun gameClosed(){change{it.copy(gameRunning=false)}}
    fun startBrokenModTest(){change{startTrouble(it)};applyAndSave()}
    fun answerBrokenModTest(stillThere: Boolean){change{troubleAnswer(it,stillThere)};applyAndSave()}
    fun stopBrokenModTest(){change{restoreTrouble(it)};toast("Your mods are on again, as before");applyAndSave()}
    fun endBrokenModTest(choice: String="off") {
        val t=state.value.trouble?:return;val id=t.found
        change{it.copy(trouble=null)}
        if(id==null)return
        if(choice=="keep")setOn(listOf(id),true,t.profile)
        if(choice=="remove")uninstall(listOf(id)) else toast(if(choice=="off")"${state.value.mod(id).name} stays off" else "${state.value.mod(id).name} is on again")
    }
    fun closeBrokenModTest(){if(state.value.trouble?.phase=="nomod")change{it.copy(trouble=null)} else if(state.value.trouble?.phase=="found")endBrokenModTest()}
    fun importPack(pack: Pack,mode: String="new",keep: Map<String,String> = emptyMap()) {
        val rows=importRows(state.value,pack,mode).filterNot{it.local}
        if(mode=="new") {
            val id="p"+synchronized(lock){++sequence}
            change{it.copy(profiles=it.profiles+Profile(id,pack.name,PackInfo(pack.by,pack.version,mods=rows.map{it.id},followAddress=pack.followAddress)),profileId=id)}
        }
        var n=0
        rows.forEach{r->
            if(r.kind=="add"){install(r.id,true);n++}
            else {
                setOn(listOf(r.id),true)
                if(r.kind=="up"||r.kind=="other"||(r.kind=="conflict"&&keep[r.id]=="pack")){queue(r.id,"update",version=r.packVersion,quiet=true);n++}
            }
        }
        change{it.copy(toasts=emptyList())};toast("Importing ${pack.name}: ${plural(n,"download","downloads")}")
    }
    fun checkPackUpdates() {
        val source=io.packSource?:return
        state.value.profiles.forEach { profile -> val info=profile.pack
            if(info?.followAddress!=null)background {
                val next=source.following(info)
                change { s -> s.copy(profiles=s.profiles.map { p ->
                    if(p.id==profile.id && p.pack?.followAddress==info.followAddress)p.copy(pack=p.pack.copy(nextVersion=next?.version)) else p
                }) }
            }
        }
    }
    fun updatePack(pack: Pack) {
        val p=state.value.profile();val info=p.pack?:return;var managed=info.mods
        pack.items.forEach{i->when(i.kind){
            "add"->{if(i.id in state.value.installed)setOn(listOf(i.id),true) else install(i.id,true);if(i.id !in managed)managed=managed+i.id}
            "rem"->{setOn(listOf(i.id),false);managed=managed-i.id}
            "chg"->queue(i.id,"update",version=i.nextVersion,quiet=true)
        }}
        val v=info.nextVersion?:pack.version
        change{it.copy(profiles=it.profiles.map{q->if(q.id==p.id)q.copy(pack=info.copy(version=v,nextVersion=null,mods=managed)) else q},toasts=emptyList())}
        toast("${p.name} is now $v")
    }
    fun shareProfile(name: String=state.value.profile().name,version: String="1.0",by: String="",includeLocal: Boolean=false,includeSettings: Boolean=false,followAddress: String?=null): Pack {
        val s=state.value
        return Pack(name,by,version,s.installed.keys.filter{isOn(s,it)&&(includeLocal||s.installed[it]?.source!=ModSource.LOCAL)}.map{PackItem(if(s.installed[it]?.source==ModSource.LOCAL)"local" else "ver",it,verOf(s,it))},followAddress=followAddress,includeSettings=includeSettings)
    }
    /** Adopt explicitly selected pack payloads already materialized by pack IO. They remain off. */
    fun addPreparedLocalMods(mods: List<Pair<CatalogMod,InstalledMod>>) {
        require(mods.all{it.second.source==ModSource.LOCAL && it.first.id==it.second.id})
        change { s -> s.copy(catalog=s.catalog.filterNot{m->mods.any{it.first.id==m.id}}+mods.map{it.first},
            installed=s.installed+mods.associate{(mod,m)->mod.id to m.copy(profiles=s.profiles.associate{it.id to ProfileMod(false,m.defaultVersion)})}) }
        applyAndSave()
    }
    fun addLocal(path: String) {val p=state.value.profileId;background{
        val (mod,installed)=io.modStore.addLocal(path)
        change{it.copy(catalog=it.catalog.filterNot{m->m.id==mod.id}+mod,installed=it.installed+(mod.id to installed.copy(profiles=installed.profiles+(p to ProfileMod(true,installed.defaultVersion)))))}
        toast("Added ${installed.fileName} to your Library and turned it on","Undo",undo={uninstallSilently(listOf(mod.id))});applyAndSave()
    }}
    fun takeOverExistingMods(folder: String) {
        background {
            val game=io.gameFolder.inspect(folder)
            val existing=io.gameFolder.takeOver(folder)
            change { s ->
                val catalog=(s.catalog+existing.map{it.first}).distinctBy{it.id}
                val installed=existing.associate{(mod,m)->mod.id to m}
                val on=installed.values.count{it.profiles["default"]?.on==true}
                s.copy(game=game,catalog=catalog,installed=installed,firstStart=FirstStart(false,on,installed.size-on))
            }
        }
    }
    fun completeFirstStart(){change{it.copy(firstStart=it.firstStart.copy(complete=true))}}
    fun repairLoader(){val folder=state.value.game.folder?:return;background{io.gameFolder.repairLoader(folder);val game=io.gameFolder.inspect(folder);change{it.copy(game=game)}}}
    fun loadMetadata() { val snapshot=state.value; snapshot.catalog.forEach { mod -> listOfNotNull(mod.newestRelease).forEach { version ->
        if(version.downloadUrl!=null) background { slots.withPermit {
            val meta=io.meta.metadata(mod,version)
            change { s -> s.copy(catalog=s.catalog.map { m -> if(m.id!=mod.id)m else m.copy(versions=m.versions.map { v -> if(v.version!=version.version)v else v.copy(sizeKb=meta.sizeKb,dateMillis=meta.dateMillis) }) }) }
        }}
    } } }
    fun refreshRegistry(){change{it.copy(connection=CatalogConnection(ConnectionKind.LOADING))};background{
        try{val mods=cleanCatalogTags(io.registry.read());val now=io.clock.nowMillis();io.registry.save(mods,now);change{it.copy(catalog=mods+it.catalog.filter{m->m.localFile!=null},connection=CatalogConnection(ConnectionKind.READY,now),nowMillis=now)};loadMetadata()}
        catch(e: Exception){val saved=io.registry.saved();change{it.copy(catalog=(saved?.first?:it.catalog.filter{m->m.localFile==null})+it.catalog.filter{m->m.localFile!=null},connection=CatalogConnection(ConnectionKind.OFFLINE,saved?.second?:it.connection.readAtMillis))}}
    }}
    override fun close(){persistenceJob?.cancel();launcherObserver.cancel();diskQueue.close();diskWorker.cancel();if(owned)worker.cancel() else synchronized(lock){jobs.values.forEach{it.cancel()}}}
}
