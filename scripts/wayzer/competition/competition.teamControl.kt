@file:Depends("wayzer/competition/module")

package wayzer.competition

import arc.Events
import cf.wayzer.placehold.DynamicVar
import cf.wayzer.scriptAgent.contextScript
import cf.wayzer.scriptAgent.define.annotations.Depends
import cf.wayzer.scriptAgent.define.annotations.Savable
import cf.wayzer.scriptAgent.listenTo
import coreLibrary.lib.registerVar
import coreLibrary.lib.with
import coreMindustry.lib.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mindustry.Vars
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.Iconc
import mindustry.gen.Player
import mindustry.world.blocks.storage.CoreBlock
import wayzer.map.BetterTeam


object TeamControl {
    private val base = contextScript<Module>()
    private val teams = contextScript<BetterTeam>()
    private val ob = contextScript<wayzer.ext.Observer>()
    @Savable
    private var teamsBak = mutableMapOf<String, Team>()
    @Savable
    private var nameBak = mutableMapOf<String, String>()

    private val gaming get() = CompetitionService.gaming
    private val loading get() = CompetitionService.loading
    val allTeam: Set<Team>
        get() {
            return Vars.state.teams.getActive().mapTo(mutableSetOf()) { it.team }.apply {
                remove(Team.derelict)
                removeIf { !it.data().hasCore() }
                removeAll(teams.bannedTeam)
            }
        }

    fun onLoad() = with(CompetitionService.script) {
        registerVar("competition.teamState", "比赛选队信息", DynamicVar.v {
            allTeam.joinToString(" ") { t ->
                val v = Groups.player.count { it.team() == t }
                "[#${t.color}]${Iconc.players}$v[]"
            }
        })
        listen<EventType.PlayEvent> {
            if (CompetitionService.gaming) {
                Groups.player.forEach {
                    runBlocking(Dispatchers.game) {
                        base.changeTeam(it, teamsBak[it.uuid()] ?: teams.spectateTeam, true)
                    }
                }
            }
        }
        listenTo<BetterTeam.AssignTeamEvent> {
            team = when {
                !gaming && !loading -> {
                    if (Team.derelict.active() || !CompetitionService.selectTeam) Team.derelict
                    else allTeam.random()
                }
                teamsBak[player.uuid()] == null -> {
                    player.sendMessage("[yellow]比赛已经开始,自动切换为观察者".with(), MsgType.InfoMessage)
                    teams.spectateTeam
                }
                else -> teamsBak[player.uuid()]
            }
        }
        listenTo<Module.PlayerTeamChangeEvent> {
            if (gaming && to == teams.spectateTeam && teamsBak[player.uuid()] != null) {
                player.sendMessage("[yellow]比赛过程禁止切换为观察者".with(), MsgType.InfoMessage)
                cancelled = true
                ob.obTeam.remove(player)
            }
            launch(Dispatchers.gamePost) {
                player.updateTeamName()
            }
        }
        listen<EventType.CoreChangeEvent> { e ->
            if (!CompetitionService.gaming) return@listen
            val team = e.core.team
            if (team == Team.derelict) return@listen
            if (team.data().cores.none { core -> core != e.core }) {
                Groups.player.filter { it.team() == team }.forEach { p ->
                    teamsBak.remove(p.uuid())
                    runBlocking(Dispatchers.game) {
                        base.changeTeam(p, teams.spectateTeam)
                    }
                    p.name = nameBak[p.uuid()] ?: p.name
                    nameBak.remove(p.uuid())
                }
                allTeam.filter { it != team && !it.data().players.isEmpty }.let {
                    if (it.size == 1) {
                        Events.fire(EventType.GameOverEvent(it.first()))
                    }
                }
            }
        }
        listen<EventType.GameOverEvent> {
            Groups.player.filter { nameBak.containsKey(it.uuid()) }.forEach { p ->
                teamsBak.remove(p.uuid())
                runBlocking(Dispatchers.game) {
                    base.changeTeam(p, teams.spectateTeam)
                }
                p.name = nameBak[p.uuid()] ?: p.name
                nameBak.remove(p.uuid())
            }
            teamsBak.clear()
            nameBak.clear()
        }
        listen<EventType.TapEvent> { e ->
            if (!gaming && e.tile.block() is CoreBlock) {
                val team = e.tile.team()
                when {
                    e.player.team() == team -> return@listen
                    !CompetitionService.selectTeam -> e.player.sendMessage("[yellow]选队已关闭，所有玩家将随机分配")
                    team !in allTeam -> e.player.sendMessage("[red]该队伍已被禁用")
                    else -> launch(Dispatchers.game) {
                        base.changeTeam(e.player, team)
                    }
                }
            }
        }
    }

    fun beforeStart() {
        Groups.player.filter { it.team() != Team.derelict && it.team() != teams.spectateTeam }.forEach {
            teamsBak[it.uuid()] = it.team()
            nameBak[it.uuid()] = it.name
        }
        if (CompetitionService.anonymous) {
            val allTeam = allTeam
            val newTeam = allTeam.shuffled()
            teamsBak.replaceAll { _, t ->
                newTeam[allTeam.indexOf(t)]
            }
        }
    }

    fun Player.updateTeamName() {
        if (!CompetitionService.anonymous || !CompetitionService.gaming) return
        if (team() == teams.spectateTeam) return
        if (uuid() !in nameBak) return
        name = "[#${team().color}]${team().name}-${this.id}"
    }
}