package nnomm.core.rules

import nnomm.core.model.*
import java.util.Locale
import kotlin.math.*

/** The mock's numeric comparison: suffixes do not affect pack conflicts. */
fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = v.split('.').map { Regex("^[+-]?\\d+").find(it)?.value?.toIntOrNull() ?: 0 }
    val x = parts(a); val y = parts(b)
    for (i in 0 until max(x.size,y.size)) {
        val d = x.getOrElse(i){0}.compareTo(y.getOrElse(i){0}); if (d != 0) return d
    }
    return 0
}
fun cleanTags(tags: List<String>, authors: List<String>): List<String> {
    val hidden = (authors + "mod").map { it.trim().lowercase() }.toSet()
    return tags.map(String::trim).filter { it.isNotEmpty() && it.lowercase() !in hidden }.distinctBy { it.lowercase() }
}
/** Merge spelling across the full registry and hide all registry author tags. */
fun cleanCatalogTags(mods: List<CatalogMod>): List<CatalogMod> {
    val authors=mods.flatMap{it.authors}
    val canonical=linkedMapOf<String,String>()
    return mods.map { mod ->
        val tags=cleanTags(mod.tags,authors).map { tag ->
            canonical.getOrPut(tag.lowercase()){tag}
        }
        mod.copy(tags=tags)
    }
}
data class FuzzyMatch(val score: Int, val indices: List<Int>)
fun fuzzy(query: String, text: String): FuzzyMatch? {
    val q=query.lowercase(); val t=text.lowercase(); val at=t.indexOf(q)
    if(at>=0) return FuzzyMatch(200-at+if(at==0 || t[at-1]==' ')30 else 0, (at until at+q.length).toList())
    if(q.length<3) return null
    var i=0; var last=-2; var score=60; val idx= mutableListOf<Int>()
    for(j in t.indices) { if(i==q.length) break; if(t[j]==q[i]) { idx+=j; if(j==last+1)score+=6; if(j==0||t[j-1]==' ')score+=8; last=j;i++ } }
    if(i<q.length)return null
    val spread=idx.last()-idx.first()-q.length
    return if(spread>max(2,q.length-1))null else FuzzyMatch(score-spread*2,idx)
}
data class MatchReason(val kind: String, val text: String = "", val indices: List<Int> = emptyList(), val snippet: String? = null, val highlightRange: IntRange? = null)
data class ModMatch(val score: Int, val reason: MatchReason? = null)
fun matchMod(x: CatalogMod,q: String): ModMatch? {
    if(q.isEmpty())return ModMatch(0)
    fuzzy(q,x.name)?.let { return ModMatch(it.score+100,MatchReason("name",x.name,it.indices)) }
    x.tags.firstOrNull{it.contains(q,true)}?.let{return ModMatch(90,MatchReason("tag","Tag: $it"))}
    val author=x.authors.joinToString(", ")
    if(author.contains(q,true))return ModMatch(80,MatchReason("author","Author: $author"))
    val at=x.description.indexOf(q,ignoreCase=true)
    if(at<0)return null
    val a=max(0,at-18);val b=min(x.description.length,at+q.length+22)
    val prefix=if(a>0)"…" else "";val snippet=prefix+x.description.substring(a,b)+(if(b<x.description.length)"…" else "")
    return ModMatch(60,MatchReason("description","In the description: ",snippet=snippet,highlightRange=(at-a+prefix.length until at-a+prefix.length+q.length)))
}
fun ago(d: Int): String = when { d<=0->"today";d==1->"yesterday";d<14->"$d days ago";d<60->"${floor(d/7.0+0.5).toInt()} weeks ago";d<365->"${floor(d/30.0+0.5).toInt()} months ago";else->"${floor(d/365.0+0.5).toInt()} years ago" }
fun size(kb: Long): String = if(kb>=1024) String.format(Locale.ROOT,"%.${if(kb>=10240)0 else 1}f MB",kb/1024.0) else "$kb KB"
fun num(n: Long): String = when {n>=1000000->String.format(Locale.ROOT,"%.1fM",n/1e6);n>=10000->"${floor(n/1000.0+0.5).toLong()}k";n>=1000->String.format(Locale.ROOT,"%.1fk",n/1000.0);else->n.toString()}
fun plural(n: Int, one: String, many: String) = "$n ${if(n==1)one else many}"
fun hash(s: String): Long { var h=0L;s.forEach { h=(h*31+it.code) and 0xffffffffL }; return h }
