@file:Depends("wayzer/map/betterTeam")

package wayzer.competition

import mindustry.game.Team
import mindustryX.events.PlayerTeamChangedEvent

/**
 * @author by nanmenyangde
 */
name = "Competition Plugin"

val competition by config.key(false, "是否启用pvp系统")
val solo by config.key(false, "是否启用solo系统")

val teams = contextScript<wayzer.map.BetterTeam>()

class PlayerTeamChangeEvent(val player: Player,val from: Team,val to: Team) : Event, Event.Cancellable {
    override var cancelled: Boolean = false
    companion object : Event.Handler()
}
val forced = mutableSetOf<Player>()

/** Should call in [Dispatchers.game] */
suspend fun changeTeam(p : Player, t : Team, force: Boolean = false) {
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

onEnable{
    if (!competition) {
        ScriptManager.unloadScript(thisContextScript(), "pvp系统未启用")
    }
}