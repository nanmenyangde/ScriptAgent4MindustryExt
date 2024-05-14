@file:Depends("wayzer/maps")

package wayzer.ext

import arc.struct.StringMap
import arc.util.Strings
import arc.util.serialization.JsonReader
import arc.util.serialization.JsonValue
import arc.util.serialization.JsonWriter
import cf.wayzer.placehold.PlaceHoldApi.with
import com.google.common.cache.CacheBuilder
import mindustry.game.Gamemode
import mindustry.io.SaveIO
import mindustry.maps.Map
import wayzer.BaseMapInfo
import wayzer.MapInfo
import wayzer.MapProvider
import wayzer.MapRegistry
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.time.Duration
import java.util.zip.InflaterInputStream
import mindustry.maps.Map as MdtMap

name = "资源站配套脚本"

val token by config.key("", "Mindustry资源站服务器Token")
val webRoot by config.key("https://api.mindustry.top", "Mindustry资源站Api")

val tokenOk get() = token.isNotBlank()

var MdtMap.resourceId: String?
    get() = tags.get("resourceId")
    set(v) {
        tags.put("resourceId", v)
    }

fun JsonValue.toStringMap() = StringMap().apply {
    var node = child()
    do {
        put(node.name, node.toJson(JsonWriter.OutputType.minimal))
        node = node.next
    } while (node != null)
}

suspend fun httpGet(url: String, retry: Int = 3) = withContext(Dispatchers.IO) {
    var result: Result<ByteArray> = Result.failure(IllegalStateException("result not set"))
    repeat(retry + 1) {
        result = kotlin.runCatching {
            val stream = URL(url).openConnection()
                .apply { readTimeout = 1_000 }
                .getInputStream()
            runInterruptible { stream.readBytes() }
        }.onSuccess { return@withContext it }
    }
    result.getOrThrow()
}

fun loadMap(map: Map, hash: String) {
    val bs = runBlocking { httpGet("$webRoot/maps/$hash/downloadServer?token=$token", retry = 3) }
    @Suppress("INACCESSIBLE_TYPE")
    SaveIO.load(InflaterInputStream(ByteArrayInputStream(bs)), world.filterContext(map))
}

fun newMapInfo(id: Int, hash: String, tags: StringMap, mode: String): BaseMapInfo {
    val mode2 = Gamemode.all.find { it.name.equals(mode, ignoreCase = true) }
        ?: Gamemode.survival.takeUnless { mode.equals("unknown", true) }
    val map = Map(customMapDirectory.child("unknown"), tags.getInt("width"), tags.getInt("height"), tags, true).apply {
        resourceId = hash
    }
    return BaseMapInfo(id, map, mode2 ?: map.rules().mode())
}

val searchCache = CacheBuilder.newBuilder()
    .expireAfterWrite(Duration.ofHours(1))
    .build<String, List<BaseMapInfo>>()!!

MapRegistry.register(this, object : MapProvider() {
    override suspend fun searchMaps(search: String?): Collection<BaseMapInfo> {
        return emptyList()
        /*
        if (!tokenOk) return emptyList()
        val mappedSearch = when (search) {
            "all", "display", "site", null -> ""
            "pvp", "attack", "survive" -> "@mode:${Strings.capitalize(search)}"
            else -> search
        }
        searchCache.getIfPresent(mappedSearch)?.let { return it }
        val maps = httpGet("$webRoot/maps/list?prePage=100&search=${URLEncoder.encode(mappedSearch, "utf-8")}")
            .let { JsonReader().parse(it.toString(Charset.defaultCharset())) }
            .map {
                val id = it.getInt("id")
                val hash = it.getString("latest")
                newMapInfo(id, hash, it.toStringMap(), it.getString("mode", "unknown"))
            }
        searchCache.put(mappedSearch, maps)
        return maps
         */
    }

    override suspend fun findById(id: Int, reply: ((PlaceHoldString) -> Unit)?): MapInfo? {
        if (id !in 10000..99999) return null
        if (!tokenOk) {
            reply?.invoke("[red]本服未开启网络换图，请联系服主开启".with())
            return null
        }
        val info = httpGet("$webRoot/maps/thread/$id/latest")
            .let { JsonReader().parse(it.toString(Charset.defaultCharset())) }
        val hash = info.getString("hash")
        val tags = info.get("tags").toStringMap()
        return newMapInfo(id, hash, tags, info.getString("mode", "unknown")).run {
            MapInfo(id, map, mode) {
                loadMap(map, hash)
            }
        }
    }
})
