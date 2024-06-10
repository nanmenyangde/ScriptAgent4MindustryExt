@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/maps", "地图管理")
@file:Depends("wayzer/ext/observer")
@file:Depends("wayzer/ext/voteMap", soft = true)

package wayzer.competition

import arc.util.Strings
import cf.wayzer.placehold.PlaceHoldApi.with
import mindustry.Vars.*
import mindustry.game.Gamemode
import mindustry.game.Team
import mindustry.io.SaveIO
import wayzer.*
import wayzer.competition.TeamControl.allTeam

val base = contextScript<Module>()

listenTo<GetNextMapEvent> {
    if (mapInfo !is MapInfo) return@listenTo
    mapInfo = (CompetitionService.nextMap ?: mapInfo as MapInfo).copy(mode = Gamemode.survival)
    CompetitionService.nextMap = null
    cancelled = true
}

listenTo<MapChangeEvent>(Event.Priority.After) {
    rules.pvp = CompetitionService.loading && info.mode == Gamemode.pvp//prevent rules in map
    if (!rules.pvp) {
        CompetitionService.gaming = false
    } else {
        CompetitionService.loading = false
    }
}

listen<EventType.WorldLoadEvent> {
    if (!CompetitionService.gaming) {
        state.rules.apply {
            pvp = false
            waves = false
            canGameOver = false
            blockDamageMultiplier = 0f
            unitDamageMultiplier = 0f
            buildSpeedMultiplier = 0f
            modeName = "准备阶段"
            disableWorldProcessors = true
            enemyCoreBuildRadius = 0f
            polygonCoreProtection = false
            placeRangeCheck = false
            lighting = false
            fog = false
            staticFog = false
        }
    }
}

listen<EventType.CoreChangeEvent> {
    if (!CompetitionService.gaming) return@listen
    val team = it.core.team
    if (team == Team.derelict) return@listen
    if (team.data().cores.isEmpty) {
        broadcast(
            "{team.colorizeName}[]被{killer.colorizeName}[]淘汰！".with("team" to team, "killer" to it.core.lastDamage),
            quite = true
        )
    }
}
TeamControl.onLoad()
onEnable {
    CompetitionService.onEnable()
    ScriptManager.getScriptNullable("wayzer/ext/voteMap")?.let {
        ScriptManager.disableScript(it, "比赛系统接管")
        onDisable { ScriptManager.enableScript(it) }
        VoteEvent.VoteCommands += CommandInfo(this, "map", "投票换图") {
            aliases = listOf("换图")
            usage = "<mapId>"
            permission = "wayzer.vote.map"
            body {
                if (arg.isEmpty()) returnReply("[red]请输入地图序号".with())
                val map = arg[0].toIntOrNull()?.let { MapRegistry.findById(it, reply) } ?: returnReply("[red]地图序号错误,可以通过/maps查询".with())
                if (map.mode != Gamemode.pvp) returnReply("[red]pvp服仅支持PVP模式的地图".with())
                val desc = "下张地图([green]{nextMap.id}[]: [green]{nextMap.map.name}[yellow]|[green]{nextMap.mode}[])".with("nextMap" to map)
                val extdesc = "[white]地图作者: [lightgrey]${Strings.stripColors(map.map.author())}[][]\n" +
                        "[white]地图简介: [lightgrey]${Strings.truncate(Strings.stripColors(map.map.description()), 100, "...")}[][]"
                val event = VoteEvent(
                    script!!, player!!,
                    desc, extdesc,
                    true
                )
                if (event.awaitResult()) {
                    if (CompetitionService.gaming) {
                        CompetitionService.nextMap = map
                    } else {
                        broadcast("[yellow]异步加载地图中，请耐心等待".with())
                        if (withContext(Dispatchers.Default) { map.map.file.exists() && !SaveIO.isSaveValid(map.map.file) })
                            return@body broadcast("[red]换图失败,地图[yellow]{nextMap.name}[green](id: {nextMap.id})[red]已损坏".with("nextMap" to map.map))
                        MapManager.loadMap(map)
                        Core.app.post {
                            broadcast("[green]换图成功,当前地图[yellow]{map.name}[green](id: {map.id})".with())
                        }
                    }
                }
            }
        }
        VoteEvent.VoteCommands.autoRemove(this)
    }
}
command("competition", "比赛管理指令") {
    permission = "competition.admin"
    body(commands)
}
val commands = Commands()
commands += CommandInfo(this, "start", "开始比赛") {
    body {
        if (CompetitionService.gaming) returnReply("[red]比赛正在进行中".with())
        CompetitionService.startGame()
    }
}
commands += CommandInfo(this, "lobby", "回到准备阶段") {
    body {
        CompetitionService.gaming = false
        MapManager.loadMap(MapManager.current.copy(mode = Gamemode.survival))
    }
}

PermissionApi.registerDefault("competition.admin", group = "@admin")

/**
 * Rewrite of the command in betterTeam
 */
command("team", "管理指令: 修改自己或他人队伍(PVP模式)") {
    usage = "[队伍,不填列出] [玩家3位id,默认自己]"
    permission = "wayzer.ext.team.change"
    body {
        if (!state.rules.pvp) returnReply("[red]仅PVP模式可用".with())
        val team = arg.getOrNull(0)?.toIntOrNull()?.let { Team.get(it) } ?: let {
            val teams = allTeam.map { t -> "{id}({team.colorizeName}[])".with("id" to t.id, "team" to t) }
            returnReply("[yellow]可用队伍: []{list}".with("list" to teams))
        }
        val player = arg.getOrNull(1)?.let {
            depends("wayzer/user/shortID")?.import<(String) -> String?>("getUUIDbyShort")?.invoke(it)
                ?.let { id -> Groups.player.find { it.uuid() == id } }
                ?: returnReply("[red]找不到玩家,请使用/list查询正确的3位id".with())
        } ?: player ?: returnReply("[red]请输入玩家ID".with())
        base.changeTeam(player, team, true)
        broadcast(
            "[green]管理员更改了{player.name}[green]为{team.colorizeName}".with("player" to player, "team" to team)
        )
    }
}

