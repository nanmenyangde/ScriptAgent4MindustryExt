package wayzer.competition

import cf.wayzer.scriptAgent.Event
import mindustry.game.Team
import mindustry.gen.Player

class PlayerTeamChangeEvent(val player: Player, val from: Team, val to: Team) : Event, Event.Cancellable {
    override var cancelled: Boolean = false
    companion object : Event.Handler()
}

interface CompetitionTeamService {
    /** Should call in [Dispatchers.game] */
    suspend fun changeTeam(p: Player, t: Team, force: Boolean = false)
}

interface CompetitionStateService {
    var loading: Boolean
    var gaming: Boolean
    var teamsBak: MutableMap<String, Team>
    var nameBak: MutableMap<String, String>
    var codeNames: MutableMap<String, String>
    var eliminatedTeams: MutableSet<Team>
}
