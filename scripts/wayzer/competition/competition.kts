@file:Implement(CompetitionStateService::class)
@file:Depends("wayzer/competition", "比赛基础模块")
@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/maps", "地图管理")
@file:Depends("wayzer/ext/observer")
@file:Depends("wayzer/user/nameExt", "匿名模式名称更新")

package wayzer.competition

import arc.util.Strings
import coreLibrary.lib.with
import mindustry.Vars.*
import mindustry.game.Gamemode
import mindustry.game.Team
import wayzer.*
import wayzer.competition.TeamControl.allTeam

val base by Services.get<CompetitionTeamService>().notNull

@Savable
var loading = false

@Savable
var gaming = false

@Savable(serializable = false)
var teamsBak = mutableMapOf<String, Team>()

@Savable
var nameBak = mutableMapOf<String, String>()

@Savable
var codeNames = mutableMapOf<String, String>()

@Savable(serializable = false)
var eliminatedTeams = mutableSetOf<Team>()

listenTo<GetNextMapEvent> {
    mapInfo = (CompetitionService.nextMap ?: mapInfo).copy(mode = Gamemode.survival)
    CompetitionService.nextMap = null
    cancelled = true
}

listenTo<MapChangeEvent>(Event.Priority.After) {
    rules.pvp = CompetitionService.loading && info.mode == Gamemode.pvp//prevent rules in map
    if (!rules.pvp) {
        CompetitionService.gaming = false
        TeamControl.clearSnapshots()
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

TeamControl.onLoad()
onEnable {
    CompetitionService.onEnable()
    onDisable { CompetitionService.onDisable() }
    ScriptManager.getScriptNullable("wayzer/cmds/voteMap")?.let {
        var restoreVoteMap: UserDemandState? = null
        ScriptManager.afterTransaction {
            if (it.enabled) {
                ScriptManager.transactionV2 {
                    restoreVoteMap = it.scriptInfo.userDemand
                    disable(it.scriptInfo)
                }
            }
        }
        onDisable {
            restoreVoteMap?.let { demand ->
                ScriptManager.getTransaction(it.scriptInfo).setUserDemand(demand)
                ScriptManager.enableScript(it)
            }
        }
        VoteEvent.VoteCommands += CommandInfo(this, "map", "投票换图") {
            aliases = listOf("换图")
            usage = "<mapId>"
            requirePermission("wayzer.vote.map")
            body {
                if (arg.isEmpty()) returnReply("[red]请输入地图序号".with())
                val map = arg[0].toIntOrNull()?.let { MapRegistry.findById(it, reply) } ?: returnReply("[red]地图序号错误,可以通过/maps查询".with())
                if (map.mode != Gamemode.pvp) returnReply("[red]pvp服仅支持PVP模式的地图".with())
                val desc = "下张地图([green]{nextMap.id}[]: [green]{nextMap.name}[yellow]|[green]{nextMap.mode}[])".with("nextMap" to map)
                val extdesc = "[white]地图作者: [lightgrey]${Strings.stripColors(map.author)}[][]\n" +
                        "[white]地图简介: [lightgrey]${Strings.truncate(Strings.stripColors(map.description), 100, "...")}[][]"
                val event = VoteEvent(
                    this@CommandInfo.script!!, player!!,
                    desc, extdesc,
                    true
                )
                if (event.awaitResult()) {
                    if (CompetitionService.gaming) {
                        CompetitionService.nextMap = map
                    } else {
                        broadcast("[yellow]异步加载地图中，请耐心等待".with())
                        if (MapManager.loadMapSync(map)) {
                            broadcast("[green]换图成功,当前地图[yellow]{map.name}[green](id: {map.id})".with())
                        }
                    }
                }
            }
        }
        VoteEvent.VoteCommands.autoRemove(this)
    }
}
val commands = CompetitionService.commands
command("competition", "比赛管理指令") {
    requirePermission("competition.admin")
    body(commands)
}
commands += CommandInfo(this, "start", "开始比赛") {
    body {
        if (CompetitionService.gaming) returnReply("[red]比赛正在进行中".with())
        CompetitionService.startGame()
    }
}
commands += CommandInfo(this, "lobby", "回到准备阶段") {
    body {
        CompetitionService.gaming = false
        CompetitionService.loading = false
        TeamControl.clearSnapshots()
        MapManager.loadMapSync(MapManager.current.copy(mode = Gamemode.survival))
    }
}
commands += CommandInfo(this, "kill", "淘汰指定队伍") {
    usage = "<队伍ID>"
    body {
        if (!CompetitionService.gaming) returnReply("[red]比赛尚未开始".with())
        val team = arg.getOrNull(0)?.toIntOrNull()?.let { Team.all.getOrNull(it) }
            ?: returnReply("[red]请输入正确的队伍ID".with())
        if (team !in allTeam) returnReply("[red]该队伍不存在、已被淘汰或已被禁用".with())
        if (!TeamControl.eliminateTeam(team)) returnReply("[red]该队伍已被淘汰".with())
    }
}

PermissionApi.registerDefault("competition.admin", group = "@admin")

/**
 * Rewrite of the command in betterTeam
 */
command("team", "管理指令: 修改自己或他人队伍") {
    usage = "[队伍,不填列出] [玩家3位id,默认自己]"
    requirePermission("wayzer.ext.team.change")
    body {
        val team = arg.getOrNull(0)?.toIntOrNull()?.let { Team.get(it) } ?: let {
            val teams = allTeam.map { t -> "{id}({team.colorizeName}[])".with("id" to t.id, "team" to t) }
            returnReply("[yellow]可用队伍: []{list}".with("list" to teams))
        }
        val player = arg.getOrNull(1)?.let {
            PlayerData.findByShortId(it)?.player
                ?: returnReply("[red]找不到玩家,请使用/list查询正确的3位id".with())
        } ?: (player ?: returnReply("[red]请输入玩家ID".with()))
        base.changeTeam(player, team, CompetitionService.gaming)
        if (player.team() != team) return@body
        TeamControl.onAdminTeamChange(player, team)
        broadcast(
            "[green]管理员更改了{player.name}[green]为{team.colorizeName}".with("player" to player, "team" to team)
        )
    }
}
