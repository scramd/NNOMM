package nnomm.core.io.real

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import nnomm.core.io.*
import nnomm.core.model.*
import nnomm.core.settings.defaultSettings
import nnomm.core.store.AppStore
import java.nio.file.*
import kotlin.io.path.*

/** Owns HTTP and process-watch resources. Call shutdown to flush state before closing the UI. */
class RealRuntime private constructor(val store: AppStore, val mods: RealModStore,val packs: RealPacks,private val scope: CoroutineScope,private val client: io.ktor.client.HttpClient) {
    suspend fun shutdown(){store.flush();store.close();scope.cancel();client.close()}
    companion object {
        suspend fun open(home: Path=settingsHome(),version: String="0.1.0",fakeLauncher: List<String>?=null,notice: (String)->Unit = {}): RealRuntime=withContext(Dispatchers.IO){
            Files.createDirectories(home)
            val persistence=RealPersistence(home,notice)
            val initial=persistence.load()?:AppState(settings=persistence.read()?:defaultSettings().let{it.copy(general=it.general+mapOf("gamePath" to JsonPrimitive(""),"store" to JsonPrimitive(home.resolve("mods").toString()),"sources" to JsonPrimitive(DEFAULT_REGISTRY)))},firstStart=FirstStart(false))
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val client=networkClient(version)
            var app: AppStore?=null
            fun current()=app?.state?.value?:initial
            val registry=RealRegistry(client,home,{
                current().settings.general["sources"]?.content?.split(Regex("[\\n,;]+"))?.map{it.trim()}?.filter{it.isNotEmpty()}?.takeIf{it.isNotEmpty()}?:listOf(DEFAULT_REGISTRY)
            },notice)
            val saved=registry.saved()
            val mods=RealModStore(home){current().catalog}
            val downloader=RealDownloader(client,home)
            val launcher=RealGameLauncher(home,scope,fakeLauncher)
            val game=RealGameFolder(home,mods,downloader,{current().catalog},{launcher.running.value||gameProcessRunning()},notice)
            val folder=initial.settings.general["gamePath"]?.content?.takeIf{it.isNotBlank()}?:initial.game.folder?:findGameFolder(initial.settings)
            val loaded=initial.copy(catalog=saved?.first.orEmpty()+initial.catalog,connection=CatalogConnection(ConnectionKind.OFFLINE,saved?.second),game=folder?.let{game.inspect(it)}?:GameInfo(),appVersion=version,nowMillis=System.currentTimeMillis(),gameRunning=launcher.running.value)
            val packs=RealPacks(home,mods,client)
            app=AppStore(loaded,AppIo(registry,RealMetaSource(client,home),RealReleaseNotes(client,home),downloader,mods,game,launcher,persistence,Clock{System.currentTimeMillis()},persistence,packs))
            RealRuntime(app,mods,packs,scope,client)
        }
    }
}
