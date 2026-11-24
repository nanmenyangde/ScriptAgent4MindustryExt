@file:Depends("wayzer/competition")

package wayzer.competition

import arc.Core
import arc.Events
import arc.util.I18NBundle
import cf.wayzer.placehold.DynamicVar
import cf.wayzer.scriptAgent.ScriptManager
import cf.wayzer.scriptAgent.define.annotations.Depends
import cf.wayzer.scriptAgent.listenTo
import cf.wayzer.scriptAgent.util.Services
import coreLibrary.lib.notNull
import coreLibrary.lib.registerVar
import coreLibrary.lib.with
import coreMindustry.lib.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mindustry.Vars
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.Iconc
import mindustry.gen.Player
import mindustry.world.blocks.storage.CoreBlock
import wayzer.map.AssignTeamEvent
import wayzer.map.TeamService
import java.util.Locale


object TeamControl {
    private val base by Services.get<CompetitionTeamService>().notNull
    private val teams by Services.get<TeamService>().notNull
    private val observerTeams = (ScriptManager.getScript("wayzer/ext/observer") as wayzer.ext.Observer).obTeam
    private val nameExt = ScriptManager.getScript("wayzer/user/nameExt") as wayzer.user.NameExt
    private val state by Services.get<CompetitionStateService>().notNull
    private val teamsBak get() = state.teamsBak
    private val nameBak get() = state.nameBak
    private val codeNames get() = state.codeNames
    private val eliminatedTeams get() = state.eliminatedTeams
    private val chineseBundle by lazy {
        I18NBundle.createBundle(Core.files.internal("bundles/bundle"), Locale.SIMPLIFIED_CHINESE)
    }

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
        registerVar("competition.teamState", "比赛选队信息", DynamicVar {
            allTeam.joinToString(" ") { t ->
                val v = Groups.player.count { it.team() == t }
                "[#${t.color}]${Iconc.players}$v[]"
            }
        })
        listen<EventType.PlayEvent> {
            if (CompetitionService.gaming) {
                Groups.player.forEach {
                    MindustryDispatcher.safeBlocking {
                        base.changeTeam(it, teamsBak[it.uuid()] ?: AssignTeamEvent.spectateTeam, true)
                    }
                    it.updateTeamName()
                }
            }
        }
        listenTo<AssignTeamEvent> {
            team = when {
                !gaming && !loading -> {
                    AssignTeamEvent.spectateTeam
                }
                teamsBak[player.uuid()] == null -> {
                    player.sendMessage("[yellow]比赛已经开始,自动切换为观察者".with(), MsgType.InfoMessage)
                    AssignTeamEvent.spectateTeam
                }
                else -> teamsBak[player.uuid()]
            }
        }
        listenTo<PlayerTeamChangeEvent> {
            if (gaming && to == AssignTeamEvent.spectateTeam && teamsBak[player.uuid()] != null) {
                player.sendMessage("[yellow]比赛过程禁止切换为观察者".with(), MsgType.InfoMessage)
                cancelled = true
                observerTeams.remove(player)
            }
            launch(Dispatchers.game) {
                player.updateTeamName()
            }
        }
        listen<EventType.BlockDestroyEvent> { e ->
            if (!CompetitionService.gaming) return@listen
            val destroyedCore = e.tile.build as? CoreBlock.CoreBuild ?: return@listen
            val team = destroyedCore.team
            if (team == Team.derelict) return@listen
            if (team.data().cores.none { core -> core != destroyedCore }) {
                eliminateTeam(team, destroyedCore.lastDamage)
            }
        }
        listen<EventType.GameOverEvent> {
            Groups.player.filter { nameBak.containsKey(it.uuid()) }.forEach { p ->
                teamsBak.remove(p.uuid())
                MindustryDispatcher.safeBlocking {
                    base.changeTeam(p, AssignTeamEvent.spectateTeam)
                }
                p.clearTeamName()
                nameBak.remove(p.uuid())
            }
            clearSnapshots()
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

    fun eliminateTeam(team: Team, killer: Team? = null): Boolean {
        if (!gaming || team == Team.derelict || team == AssignTeamEvent.spectateTeam) return false
        if (!eliminatedTeams.add(team)) return false
        if (killer == null) {
            broadcast("{team.colorizeName}[]被管理员淘汰！".with("team" to team), quite = true)
        } else {
            broadcast(
                "{team.colorizeName}[]被{killer.colorizeName}[]淘汰！".with("team" to team, "killer" to killer),
                quite = true
            )
        }
        Groups.player.filter { it.team() == team }.forEach { p ->
            teamsBak.remove(p.uuid())
            MindustryDispatcher.safeBlocking {
                base.changeTeam(p, AssignTeamEvent.spectateTeam)
            }
            p.clearTeamName()
            nameBak.remove(p.uuid())
        }
        allTeam.filter { other ->
            other != team && Groups.player.any { it.team() == other }
        }.let {
            if (it.size == 1) {
                Events.fire(EventType.GameOverEvent(it.first()))
            }
        }
        return true
    }

    fun clearSnapshots() {
        Groups.player.forEach { p ->
            if (p.uuid() in nameBak) p.clearTeamName()
        }
        teamsBak.clear()
        nameBak.clear()
        codeNames.clear()
        eliminatedTeams.clear()
    }

    private fun availableUnitNames(): List<String> = Vars.content.units().map {
        chineseBundle.get("unit.${it.name}.name", it.localizedName)
    }.distinct().toList()

    private fun assignCodeName(player: Player, unitNames: List<String>) {
        val index = codeNames.size
        if (unitNames.isEmpty()) {
            codeNames[player.uuid()] = "玩家-${index + 1}"
            return
        }
        val baseName = unitNames[index % unitNames.size]
        var suffix = if (index < unitNames.size) 1 else index / unitNames.size + 1
        var name = if (suffix == 1) baseName else "$baseName-$suffix"
        while (name in codeNames.values) {
            suffix++
            name = "$baseName-$suffix"
        }
        codeNames[player.uuid()] = name
    }

    fun beforeStart() {
        clearSnapshots()
        val players = Groups.player.filter { it.team() != Team.derelict && it.team() != AssignTeamEvent.spectateTeam }
        players.forEach {
            teamsBak[it.uuid()] = it.team()
            nameBak[it.uuid()] = it.name
        }
        if (CompetitionService.anonymous) {
            val unitNames = availableUnitNames().shuffled()
            players.forEach { assignCodeName(it, unitNames) }
            val allTeam = allTeam
            val newTeam = allTeam.shuffled()
            teamsBak.replaceAll { _, t ->
                newTeam[allTeam.indexOf(t)]
            }
        }
    }

    fun onAdminTeamChange(player: Player, team: Team) {
        if (team != AssignTeamEvent.spectateTeam) observerTeams.remove(player)
        if (!gaming) return
        val uuid = player.uuid()
        teamsBak[uuid] = team
        if (team == AssignTeamEvent.spectateTeam) {
            player.clearTeamName()
            return
        }
        nameBak.putIfAbsent(uuid, player.name)
        if (CompetitionService.anonymous && uuid !in codeNames) {
            assignCodeName(player, availableUnitNames().shuffled())
        }
        player.updateTeamName()
    }

    fun Player.updateTeamName() {
        if (!CompetitionService.anonymous || !CompetitionService.gaming) return
        if (team() == AssignTeamEvent.spectateTeam) return
        if (uuid() !in nameBak) return
        val codeName = codeNames[uuid()] ?: return
        nameExt.nameOverrides[uuid()] = "[#${team().color}]$codeName"
        with(nameExt) { this@updateTeamName.updateName() }
    }

    fun Player.clearTeamName() {
        nameExt.nameOverrides.remove(uuid())
        with(nameExt) { this@clearTeamName.updateName() }
    }
}
