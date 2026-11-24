package wayzer.competition

import arc.Events
import coreLibrary.lib.with
import cf.wayzer.scriptAgent.thisContextScript
import cf.wayzer.scriptAgent.util.Services
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.Commands
import coreLibrary.lib.config
import coreLibrary.lib.notNull
import coreLibrary.lib.returnReply
import coreLibrary.lib.util.loop
import coreMindustry.lib.game
import coreMindustry.lib.player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import mindustry.game.EventType
import mindustry.game.Gamemode
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import wayzer.MapInfo
import wayzer.MapManager
import wayzer.VoteEvent
import wayzer.map.AssignTeamEvent
import wayzer.map.TeamService
import kotlin.time.Duration.Companion.minutes

object CompetitionService {
    val script = thisContextScript()
    val commands = Commands()
    val teams by Services.get<TeamService>().notNull
    private val state by Services.get<CompetitionStateService>().notNull

    private val config get() = script.config

    val startByAdmin by config.key(false, "是否必须由管理员开始")
    val selectTeam by config.key(true, "是否允许选队")
    val anonymous by config.key(false, "是否开启匿名模式")

    var loading
        get() = state.loading
        set(value) {
            state.loading = value
        }
    var gaming
        get() = state.gaming
        set(value) {
            state.gaming = value
        }
    var nextMap: MapInfo? = null

    fun updateHud() {
        if (loading || gaming) Call.hideHudText()
        else {
            val state = when {
                Groups.player.size() < 2 -> "[green]等待玩家中"
                startByAdmin -> "[red]人数已够，等待管理员开始"
                else -> "[green]使用 /vote start 投票开始"
            }
            Call.setHudTextReliable(
                """
                | [green]当前地图是{map.name}
                | [yellow]点击核心选择队伍
                | [yellow]观察者请选择灰队或ob
                | {state}
                |        {competition.teamState}
            """.trimMargin().with("state" to state).toString()
            )
        }
    }

    fun onEnable() = with(script) {
        loop(Dispatchers.game) {
            delay(1_000)
            updateHud()
        }
        var prev = false
        loop(Dispatchers.game) {
            delay(1.minutes)
            if (gaming && teams.allTeam.all { team -> Groups.player.none { it.team() == team } }) {
                if (prev) {
                    Events.fire(EventType.GameOverEvent(Team.derelict))
                } else prev = true
            } else prev = false
        }
        VoteEvent.VoteCommands += CommandInfo(this, "start", "立即开始比赛") {
            aliases = listOf("开始")
            usage = ""
            body {
                if (gaming) returnReply("[red]游戏已经开始".with())
                if (startByAdmin) returnReply("[red]比赛模式需要由管理员开始".with())
                val event = VoteEvent(
                    this@CommandInfo.script!!, player!!, this@CommandInfo.description,
                    canVote = { it.team() != AssignTeamEvent.spectateTeam },
                )
                if (event.awaitResult()) {
                    startGame()
                }
                this.player?.let {
                    VoteEvent.coolDowns[it.uuid()] = System.currentTimeMillis()
                }
            }
        }
    }
    fun onDisable() = with(script) {
        Call.hideHudText()
        VoteEvent.VoteCommands.removeAll(this)
    }

    suspend fun startGame() {
        with(script) {
            if (loading || gaming) return
            loading = true
            gaming = true
            TeamControl.beforeStart()
            if (!MapManager.loadMapSync(MapManager.current.copy(mode = Gamemode.pvp))) {
                loading = false
                gaming = false
                TeamControl.clearSnapshots()
            }
        }
    }

}
