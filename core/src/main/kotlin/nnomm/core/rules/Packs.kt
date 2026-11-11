package nnomm.core.rules

import nnomm.core.model.*
import kotlin.math.*

data class ImportRow(val kind: String,val id: String,val packVersion: String?,val have: String?,val text: String,val local: Boolean=false,val defaultOn: Boolean=true)
fun importRows(s: AppState,pack: Pack,mode: String="new"): List<ImportRow> = pack.items.map { i->
    if(i.kind=="local")return@map ImportRow("local",i.id,null,null,"Not from the registry",true,false)
    val have=verOf(s,i.id)
    val k=if(i.kind!="ver")i.kind else if(mode=="new")"other" else if(compareVersions(i.version?:"",have?:"")>0)"up" else "conflict"
    val text=when(k){"add"->size(s.mod(i.id).newestRelease?.sizeKb?:0);"same"->"you have it";"up"->"$have to ${i.version}";"other"->"pack uses ${i.version}, ${s.profile().name} keeps $have";else->"${s.profile().name} has a newer version than the pack"}
    ImportRow(k,i.id,i.version,have,text)
}
data class PackUpdateRow(val kind: String,val id: String,val text: String)
fun packUpdateRows(s: AppState,pack: Pack)=pack.items.map { i->PackUpdateRow(i.kind,i.id,when(i.kind){"add"->size(s.mod(i.id).newestRelease?.sizeKb?:0);"rem"->"turned off, not deleted";"chg"->"${i.version} to ${i.nextVersion}";"newer"->"pack asks for ${i.nextVersion}, you keep ${i.version}";else->"you have it"}) }
fun startTrouble(s: AppState): AppState {
    val on=s.installed.keys.filter{isOn(s,it)}
    val t=TroubleSession(s.profileId,on,on,rounds=max(1,ceil(log2(max(2,on.size).toDouble())).toInt()))
    return applyTrouble(s.copy(trouble=t),emptyList())
}
private fun applyTrouble(s: AppState,on: List<String>): AppState {
    val t=s.trouble?:return s
    return s.copy(installed=s.installed.mapValues{(id,m)->if(id !in t.saved)m else m.copy(profiles=m.profiles+(t.profile to ProfileMod(id in on,verOf(s,id,t.profile)!!)))})
}
fun troubleRound(s: AppState): AppState {
    val t=s.trouble?:return s; val keep=t.suspects.take(ceil(t.suspects.size/2.0).toInt()).toMutableSet()
    do { val before=keep.size;keep.toList().forEach{id->s.mod(id).newestRelease?.dependencies?.forEach{if(it.id in t.saved)keep+=it.id}} }while(keep.size!=before)
    val on=t.saved.filter{it in keep}
    return applyTrouble(s.copy(trouble=t.copy(phase="test",round=t.round+1,on=on,off=t.saved-on.toSet())),on)
}
fun troubleAnswer(s: AppState,still: Boolean): AppState {
    val t=s.trouble?:return s;val stopped=s.copy(gameRunning=false)
    if(t.phase=="base")return if(still)applyTrouble(stopped.copy(trouble=t.copy(phase="nomod")),t.saved) else troubleRound(stopped)
    if(t.phase!="test")return s
    val suspects=t.suspects.filter{it in if(still)t.on else t.off}
    if(suspects.size>1)return troubleRound(stopped.copy(trouble=t.copy(suspects=suspects)))
    val found=suspects.firstOrNull()?:t.saved.lastOrNull()
    return applyTrouble(stopped.copy(trouble=t.copy(phase="found",suspects=suspects,found=found)),t.saved.filter{it!=found})
}
fun restoreTrouble(s: AppState)=s.trouble?.let{applyTrouble(s,it.saved).copy(trouble=null)}?:s
