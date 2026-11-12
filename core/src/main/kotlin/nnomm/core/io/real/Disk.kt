package nnomm.core.io.real

import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import nnomm.core.io.*
import nnomm.core.model.*
import java.nio.file.*
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.io.path.*

internal val json = Json { ignoreUnknownKeys=true; isLenient=true; coerceInputValues=true; encodeDefaults=true; prettyPrint=true }
fun settingsHome(env: Map<String,String> = System.getenv(), os: String = System.getProperty("os.name"), userHome: String = System.getProperty("user.home")): Path =
    Path.of(env["NNOMM_HOME"]?.takeIf{it.isNotBlank()} ?: if(os.startsWith("Windows",true))
        Path.of(env["APPDATA"] ?: "$userHome/AppData/Roaming", "NNOMM").toString()
    else Path.of(env["XDG_CONFIG_HOME"]?.takeIf{it.isNotBlank()} ?: "$userHome/.config", "NNOMM").toString()).toAbsolutePath().normalize()

/** Resolve untrusted names without permitting traversal, drive letters or symbolic links. */
internal fun safe(root: Path, name: String): Path {
    require(name.isNotBlank() && !name.contains('\\') && !name.contains(':')) { "Unsafe path: $name" }
    val base=root.toAbsolutePath().normalize(); val result=base.resolve(name).normalize()
    require(result.startsWith(base) && result!=base) { "Path leaves its folder: $name" }
    var at: Path?=result
    while(at!=null) { require(!Files.isSymbolicLink(at)) { "Symbolic links are not managed: $at" }; at=at.parent }
    return result
}
internal fun component(name: String): String { require(name !in listOf(".","..") && name.isNotBlank() && name.none{it in "/\\:"}){"Unsafe identifier: $name"}; return name }
internal fun atomic(path: Path, text: String) {
    safe(path.parent,path.fileName.toString()); Files.createDirectories(path.parent)
    val tmp=Files.createTempFile(path.parent,".nnomm-",".tmp")
    try {
        FileChannel.open(tmp,StandardOpenOption.WRITE).use{ch->val buffer=StandardCharsets.UTF_8.encode(text);while(buffer.hasRemaining())ch.write(buffer);ch.force(true)}
        try { Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        catch(_: AtomicMoveNotSupportedException){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING)}
    } finally { Files.deleteIfExists(tmp) }
}
/** A malformed primary is retained verbatim. A failed backup load blocks writes. */
internal class JsonFile<T>(val path: Path, val serializer: KSerializer<T>, val notice: (String)->Unit = {}) {
    @Synchronized fun read(): T? {
        val backup=path.resolveSibling(path.fileName.toString()+".bak")
        if(!path.exists() && !backup.exists())return null
        for(p in listOf(path,backup)) if(p.exists()) {
            try { val value=json.decodeFromString(serializer,p.readText()); if(p==backup)notice("Recovered ${path.fileName} from its backup. The original file is kept.");return value }
            catch(e: Exception){notice("Could not read ${p.fileName}: ${e.message}. The file is kept.")}
        }
        error("Cannot load ${path.fileName} or its backup; the files are kept. Restore a valid backup before continuing.")
    }
    @Synchronized fun write(value: T) {
        if(path.exists()) {
            val old=path.readText()
            if(runCatching{json.decodeFromString(serializer,old)}.isSuccess)atomic(path.resolveSibling(path.fileName.toString()+".bak"),old)
            else {
                val preserved=path.resolveSibling(path.fileName.toString()+".broken-${System.nanoTime()}")
                Files.copy(path,preserved); notice("Kept the damaged ${path.fileName} as ${preserved.fileName}.")
            }
        }
        atomic(path,json.encodeToString(serializer,value))
    }
}
internal fun digest(path: Path): String {
    val md=MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;md.update(buffer,0,n)} }
    return md.digest().joinToString(""){"%02x".format(it)}
}
internal fun normalizedHash(hash: String?)=hash?.trim()?.replace(Regex("^sha256:",RegexOption.IGNORE_CASE),"")?.lowercase()
internal fun copyTree(from: Path,to: Path,link: Boolean=false) {
    require(!Files.isSymbolicLink(from))
    if(from.isDirectory()) { Files.createDirectories(to); Files.list(from).use{it.forEach{child->copyTree(child,safe(to,child.fileName.toString()),link)}} }
    else { Files.createDirectories(to.parent); if(link) { try {Files.createLink(to,from);return}catch(_: Exception){} }; Files.copy(from,to) }
}
internal fun deleteTree(path: Path) { if(!path.exists())return; require(!Files.isSymbolicLink(path)); if(path.isDirectory())Files.list(path).use{it.forEach(::deleteTree)};Files.delete(path) }

@Serializable internal data class SavedState(val settings: SettingsValues, val installed: Map<String,InstalledMod>,val profiles: List<Profile>,val profileId: String,val game: GameInfo,val firstStart: FirstStart,val localCatalog: List<CatalogMod>)
class RealPersistence(home: Path, notice: (String)->Unit = {}): StatePersistence, SettingsFile {
    private val state=JsonFile(home.resolve("state.json"),SavedState.serializer(),notice)
    private val settings=JsonFile(home.resolve("settings.json"),SettingsValues.serializer(),notice)
    override suspend fun read()=withContext(Dispatchers.IO){settings.read()}
    override suspend fun write(values: SettingsValues)=withContext(Dispatchers.IO){settings.write(values)}
    override suspend fun load(): AppState?=withContext(Dispatchers.IO){state.read()?.let{AppState(settings=it.settings,installed=it.installed,profiles=it.profiles,profileId=it.profileId,game=it.game,firstStart=it.firstStart,catalog=it.localCatalog)}}
    override suspend fun save(state: AppState)=withContext(Dispatchers.IO){if(settings.read()!=state.settings)settings.write(state.settings);this@RealPersistence.state.write(SavedState(state.settings,state.installed,state.profiles,state.profileId,state.game,state.firstStart,state.catalog.filter{it.localFile!=null}))}
}
