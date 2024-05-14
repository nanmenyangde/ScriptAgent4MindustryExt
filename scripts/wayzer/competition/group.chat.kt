package wayzer.competition

import cf.wayzer.scriptAgent.contextScript
import coreMindustry.lib.CommandType
import coreMindustry.lib.command
import coreMindustry.lib.player
import coreMindustry.lib.type
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.net.Administration

object GroupChat{
    val script = contextScript<Group>()

    fun Group.sendMessage(from: Player, message: String, team: Boolean) {
        for (p in Groups.player) {
            val channel = when {
                !team && from.team().id == 255 -> "[cyan][公屏][观战]"
                !team -> "[cyan][公屏][[${from.team().coloredName()}]"
                inSameGroup(from, p) -> "[violet][[${group[from.uuid()]!!.name}[violet]队内]"
                group[from.uuid()] == null && from.team() == teams.spectateTeam && p.team() == teams.spectateTeam -> "[violet][观战]"
                group[from.uuid()] == null && from.team() == p.team() -> "[violet][[${from.team().chatName()}[violet]队内]"
                p.team() == teams.spectateTeam -> "[violet][[${if(group[from.uuid()]==null) from.team().chatName() else group[from.uuid()]!!.name}[violet]队内]"
                else -> continue
            }
            p.sendMessage(channel + Vars.netServer.chatFormatter.format(from, message), from, message)
        }
    }

    val teamName = mapOf(
            "sharded" to "黄",
            "blue" to "蓝",
            "malis" to "紫",
            "green" to "绿",
            "crux" to "红"
    )
    fun Team.chatName() : String {
        return emoji + "[#" + color + "]" + (teamName[name] ?: name) + "[]"
    }

    fun onLoad() = with(script) {
        command("t", "公屏聊天") {
            type = CommandType.Client
            body {
                val message = arg.joinToString(" ")
                sendMessage(player!!, message, false)
            }
        }
        onEnable {
            val filter = Administration.ChatFilter { p, t ->
                sendMessage(p, t, true)
                return@ChatFilter null
            }
            Vars.netServer.admins.chatFilters.add(filter)
            onDisable {
                Vars.netServer.admins.chatFilters.remove(filter)
            }
        }
    }
}