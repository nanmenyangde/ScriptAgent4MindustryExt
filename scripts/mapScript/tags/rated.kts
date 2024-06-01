@file:Depends("coreLibrary/extApi/mongoApi", "数据存储")
@file:Depends("wayzer/user/userService", "发送通知")
@file:Depends("wayzer/maps", "获取当前地图")
@file:Depends("wayzer/map/betterTeam")
@file:Depends("wayzer/competition/group", "获取组队信息", soft = true)
package mapScript.tags

//改自way_zer的赤潮计分脚本
import cf.wayzer.placehold.DynamicVar
import com.google.common.cache.CacheBuilder
import coreLibrary.extApi.MongoApi
import mindustry.game.Team
import mindustry.gen.Iconc
import mindustry.world.blocks.storage.CoreBlock
import org.bson.codecs.pojo.annotations.BsonId
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.litote.kmongo.Id
import org.litote.kmongo.eq
import org.litote.kmongo.and
import org.litote.kmongo.newId
import wayzer.MapManager
import java.time.Duration
import java.util.*
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

registerMapTag("@rated")

val initRating by config.key(1500, "基础分数")
val rankLimit by config.key(10, "排行榜显示条数")

val teams = contextScript<wayzer.map.BetterTeam>()

data class RatingProfile (
    @BsonId val id : Id<RatingProfile> = newId(),
    val players : Set<String>,
    val map : Int,
    var rating : Int
)

data class RatingLogUser(
    val user: Id<RatingProfile>,
    val rank: Int,//名次
    val before: Int,
    val delta: Int
)

data class RatingLog(
    @BsonId val id: Id<RatingProfile> = newId(),
    val users: List<RatingLogUser>, //按排名顺序
    val duration: Duration,
    val map: Int,//Map id
    val time: Date = Date(),
)

data class TempData(val players : Set<Player>) {
    var rating: Int = -1
    lateinit var rankProfile: RatingProfile
    lateinit var logUser: RatingLogUser
}


suspend fun Set<String>.getOrCreateRatingProfile(map: Int): RatingProfile {
    return MongoApi.Mongo.collection<RatingProfile>().findOne(and(RatingProfile::map eq map, RatingProfile::players eq this))
        ?: RatingProfile(players = this, rating = initRating, map = map).also {
            MongoApi.Mongo.collection<RatingProfile>().insertOne(it)
        }
}

val ratingCache = CacheBuilder.newBuilder()
    .expireAfterWrite(Duration.ofMinutes(1))
    .build<String, Int>()!!
registerVarForType<Player>().apply {
    registerChild("prefix.3-rank", "显示排位分", DynamicVar.obj {
        if (!enabled) return@obj null
        val score = ratingCache.get(it.uuid()) {
            val players = depends("wayzer/competition/group")
                ?.import<(Player) -> Set<String>>("getTeammates")?.invoke(it)
                ?: setOf(it.uuid())
            runBlocking {
                players.getOrCreateRatingProfile(MapManager.current.id).rating
            }
        }
        return@obj "${Iconc.statusOverclock}[gold]$score[]"
    })
}

lateinit var ranks : MutableList<Pair<Team, Set<Player>>>
onEnable {
    ranks = mutableListOf()
}

val allTeam: Set<Team>
    get() {
        if (!state.rules.pvp) return setOf(state.rules.defaultTeam)
        return state.teams.getActive().mapTo(mutableSetOf()) { it.team }.apply {
            remove(Team.derelict)
            removeIf { !it.data().hasCore() }
            removeAll(teams.bannedTeam)
            removeIf { it.data().players.isEmpty }
        }
    }

listen<EventType.CoreChangeEvent> {
    val team = it.core.team
    if (team == Team.derelict) return@listen
    if (team.data().cores.none { core -> core != it.core } && !team.data().players.isEmpty) {
        ranks.add(team to team.data().players.toSet())
        broadcast("[green]队伍[] {name} [green]淘汰！排名为第[][yellow] {rank} [][green]名[]".with("name" to team.coloredName(), "rank" to allTeam.size))
    }
}

listen<EventType.PlayEvent> {
    if (!state.rules.pvp) return@listen
    schedule(10.seconds) {
        allTeam.forEach { team ->
            val players = team.data().players.toSet()
            if (!(depends("wayzer/competition/group")?.import<(Set<Player>) -> Boolean>("inSameGroup")?.invoke(players)
                    ?: (players.size == 1))) {
                return@forEach broadcast(" {name} [green]队非组队状态，本局游戏不计算积分[]".with("name" to team.coloredName()))
            }
        }
    }
}

val icons = listOf(
    "🥇|",
    "🥈|",
    "🥉|",
    "🏅|"
)
listen<EventType.PlayEvent> {
    if (state.rules.pvp) return@listen
    val scores = Groups.player.map {
        it.coloredName() to
        ratingCache.get(it.uuid()) {
            val players = depends("wayzer/competition/group")
                ?.import<(Player) -> Set<String>>("getTeammates")?.invoke(it)
                ?: setOf(it.uuid())
            runBlocking {
                players.getOrCreateRatingProfile(MapManager.current.id).rating
            }
        }
    }.sortedByDescending { it.second }.subList(0, rankLimit)
    val msg = buildString {
        appendLine("[goldenrod]排行榜")
        appendLine("[white]${state.map.name()}")
        appendLine()
        scores.forEachIndexed { index, p ->
            appendLine("${icons.getOrNull(index)}${p.first}[gold] ${p.second}分")
        }
    }
    Groups.player.forEach {
        Call.label(it.con, msg, 10 * 60f, (world.width()/2.0).toFloat(), (world.height()/2.0).toFloat())
    }
}

listen<EventType.GameOverEvent> {
    if (!state.rules.pvp) return@listen
    broadcast("[green]队伍[] {name} [green]获胜！[]".with("'name" to it.winner.coloredName()))
    ranks.add(it.winner to it.winner.data().players.toSet())
    val gameTime by PlaceHold.reference<Duration>("state.gameTime")
    var rate = 1.0
    if (gameTime < Duration.ofMinutes(10)) {
        val minutes = gameTime.seconds / 60.0
        if (minutes < 5) {
            broadcast("[yellow]比赛时间过短(不足5分钟)，不计算积分".with())
            return@listen
        } else {
            rate *= minutes / 10
            broadcast("[yellow]比赛时间过短(不足10分钟)，积分计算系数: {rate}".with("rate" to "%.2f".format(rate)))
        }
    }
    ranks.reverse()
    ranks.forEach { (team, players) ->
        if (!(depends("wayzer/competition/group")?.import<(Set<Player>) -> Boolean>("inSameGroup")?.invoke(players)
                ?: (players.size == 1))) {
            return@forEach broadcast(" {name} [green]队非组队状态，不计算积分[]".with("name" to team.coloredName()))
        }
    }
    val map = MapManager.current.id
    launch (Dispatchers.IO){
        val groups = newSuspendedTransaction {
            ranks.map {
                TempData(it.second).apply {
                    rankProfile = players.map { it.uuid() }.toSet().getOrCreateRatingProfile(map)
                    rating = rankProfile.rating
                }
            }
        }
        val deltas = groups.mapIndexed { index, g ->
            val rank = index + 1
            val opposites = groups.filter { it != g }
            val seed = getSeed(opposites, g.rating)
            val midRank = sqrt(seed * rank)
            val expectRating = getRatingToRank(opposites, midRank)
            val delta = ((expectRating - g.rating) * rate / 2).roundToInt()
            delta
        }
        val aver = deltas.sum() / deltas.size

        groups.forEachIndexed { index, g ->
            val rank = index + 1
            g.run {
                val delta = deltas[index] - aver
                logUser = RatingLogUser(rankProfile.id, rank, rating, delta)
            }
        }
        MongoApi.Mongo.collection<RatingLog>().insertOne(RatingLog(
            users = groups.map { it.logUser },
            duration = gameTime, map = map
        ))
        groups.forEach {
            val rankProfile = it.rankProfile
            rankProfile.rating += it.logUser.delta
            MongoApi.Mongo.collection<RatingProfile>().save(rankProfile)
        }
        groups.forEach {
            val rankProfile = it.rankProfile
            broadcast("[yellow]排位分更新: {a} -> {b}".with( "a" to it.rating, "b" to rankProfile.rating), players = it.players)
        }
        ratingCache.cleanUp()
    }
}

listen<EventType.ResetEvent> {
    ratingCache.cleanUp()
}