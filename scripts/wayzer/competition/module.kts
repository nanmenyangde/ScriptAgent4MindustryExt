@file:Implement(CompetitionTeamService::class)
@file:Depends("wayzer/map/betterTeam")

package wayzer.competition

import mindustry.game.Team
import mindustryX.events.PlayerTeamChangedEvent
import wayzer.map.TeamService

/**
 * @author by nanmenyangde
 */
name = "Competition Plugin"

val competitionEnabled by config.key(false, "是否启用pvp系统")
val solo by config.key(false, "是否启用solo系统")

val teams by Services.get<TeamService>().notNull

val forced = mutableSetOf<Player>()

/** Should call in [Dispatchers.game] */
suspend fun changeTeam(p: Player, t: Team, force: Boolean = false) {
    if (!force) {
        val event = PlayerTeamChangeEvent(p, p.team(), t).emitAsync()
        if (event.cancelled) return
    }
    forced.add(p)
    teams.changeTeam(p, t)
    forced.remove(p)
}
listen<PlayerTeamChangedEvent> {
    val p = it.player
    if (forced.contains(p)) return@listen
    launch(Dispatchers.game) {
        val event = PlayerTeamChangeEvent(p, it.previous, p.team()).emitAsync()
        if (event.cancelled) changeTeam(p, it.previous, true)
    }
}

onEnable {
    if (!competitionEnabled) {
        return@onEnable ScriptManager.disableScript(this, "pvp系统未启用")
    }
    val overriddenScripts = mutableMapOf<String, UserDemandState>()
    ScriptManager.afterTransaction {
        ScriptManager.transactionV2 {
            listOf("wayzer/pvp/pvpChat", "wayzer/pvp/autoGameover").forEach { id ->
                val script = ScriptManager.getScriptNullable(id) ?: return@forEach
                if (!script.enabled) return@forEach
                overriddenScripts[id] = script.scriptInfo.userDemand
                disable(script.scriptInfo)
            }
        }
    }
    onDisable {
        overriddenScripts.forEach { (id, demand) ->
            ScriptManager.getScriptNullable(id)?.let {
                ScriptManager.getTransaction(it.scriptInfo).setUserDemand(demand)
                ScriptManager.enableScript(it)
            }
        }
    }
}
