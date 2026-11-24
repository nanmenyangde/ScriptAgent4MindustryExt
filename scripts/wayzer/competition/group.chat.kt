package wayzer.competition

import cf.wayzer.scriptAgent.thisContextScript
import coreLibrary.lib.command
import coreMindustry.lib.ClientOnly
import coreMindustry.lib.player
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.net.Administration
import wayzer.map.AssignTeamEvent

object GroupChat {
    val script = thisContextScript()
    val group = mutableMapOf<String, PlayerGroup>()

    fun sendMessage(from: Player, message: String, team: Boolean) {
        val fromGroup = group[from.uuid()]
        for (p in Groups.player) {
            val channel = if (CompetitionService.gaming) when {
                !team && from.team().id == 255 -> "[cyan][公屏][观战]"
                !team -> "[cyan][公屏][[${from.team().coloredName()}]"
                fromGroup != null && fromGroup.member.contains(p) -> "[violet][[${fromGroup.name}[violet]队内]"
                fromGroup == null && from.team() == AssignTeamEvent.spectateTeam && p.team() == AssignTeamEvent.spectateTeam -> "[violet][观战]"
                fromGroup == null && from.team() == p.team() -> "[violet][[${from.team().chatName()}[violet]队内]"
                p.team() == AssignTeamEvent.spectateTeam -> "[violet][[${if (fromGroup == null) from.team().chatName() else fromGroup.name}[violet]队内]"
                else -> continue
            } else when {
                !team && from.team().id == 255 -> "[cyan][公屏][观战]"
                !team -> "[cyan][公屏][[${from.team().coloredName()}]"
                fromGroup != null && fromGroup.member.contains(p) -> "[violet][[${fromGroup.name}[violet]队内]"
                fromGroup == null && from.team().id == 255 -> "[cyan][公屏][观战]"
                fromGroup == null -> "[cyan][公屏][[${from.team().coloredName()}]"
                p.team() == AssignTeamEvent.spectateTeam -> "[violet][[${if (fromGroup == null) from.team().chatName() else fromGroup.name}[violet]队内]"
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
    fun Team.chatName(): String {
        return emoji + "[#" + color + "]" + (teamName[name] ?: name) + "[]"
    }

    fun onLoad() = with(script) {
        command("t", "公屏聊天") {
            attr(ClientOnly)
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
