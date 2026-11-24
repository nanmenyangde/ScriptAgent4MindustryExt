package wayzer.competition

import mindustry.gen.Player
import java.io.Serializable

data class PlayerGroup(var leader: Player, val member: MutableSet<Player>, var name: String) : Serializable
