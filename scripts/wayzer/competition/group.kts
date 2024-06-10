@file:Depends("wayzer/map/betterTeam")
@file:Depends("wayzer/competition/competition")

package wayzer.competition

import cf.wayzer.placehold.DynamicVar
import cf.wayzer.placehold.PlaceHoldApi.with
import java.io.Serializable

val base = contextScript<Module>()
val teams = contextScript<wayzer.map.BetterTeam>()

data class Group(var leader: Player, val member: MutableSet<Player>, var name: String) : Serializable

var group = mutableMapOf<String, Group>()

fun isLeader(p: Player): Boolean {
    return group[p.uuid()]?.leader == p
}
fun inSameGroup(players: Set<Player>): Boolean {
    if (players.size == 1) return true
    return players.firstOrNull()?.let {
        group[it.uuid()]?.member?.containsAll(players) ?: false
    } ?: false
}

export(::isLeader)
export(::inSameGroup)

registerVarForType<Group>().apply {
    registerChild("name", "队伍名", DynamicVar.obj { it.name })
    registerChild("leader", "队长", DynamicVar.obj { it.leader })

}

registerVarForType<Player>().apply {
    registerChild("group", "所在队伍", DynamicVar.obj { group[it.uuid()] })
    registerChild("prefix.2groupName", "队伍名", DynamicVar.obj {
        if (!enabled) return@obj null
        group.getOrDefault(it.uuid(), null)?.name?.let { "<$it>" }
    })
}

fun Group.disband() {
    broadcast("{group.name}[red]队伍已被解散！[]".with("group" to this), players=member, quite=true)
    for (player in member) {
        group.remove(player.uuid())
    }
}
fun Player.leaveGroup() {
    group[uuid()]?.let{
        if (it.leader == this) it.disband()
        else {
            group.remove(uuid(), it)
            it.member.remove(this)
            broadcast("{name}[yellow]已离开队伍[]".with("name" to coloredName()), players=it.member, quite=true)
        }
    }
}

GroupChat.onLoad()

listen<EventType.PlayerLeave> {
    it.player.leaveGroup()
}

command("group", "队伍管理指令") {
    aliases = listOf("队伍")
    type = CommandType.Client
    body(commands)
}
val commands = Commands()
commands += CommandInfo(this,"create", "创建队伍") {
    aliases = listOf("创建")
    usage = "<队伍名>"
    body {
        group[player!!.uuid()]?.let {
            returnReply("[red]你已经在队伍[]{group.name}[red]中了！[]".with("group" to it))
        }
        val name = arg.getOrNull(0) ?: replyUsage()
        player!!.let {
            group[it.uuid()] = Group(it, mutableSetOf(it), colorClearedName(name))
            returnReply("[yellow]队伍[]{group.name}[yellow]已创建成功[]".with("group" to group[it.uuid()]))
        }
    }
}
commands += CommandInfo(this, "join", "加入队伍") {
    aliases = listOf("加入")
    usage = "<3位ID>"
    body {
        group[player!!.uuid()]?.let {
            returnReply("[red]你已经在队伍[]{group.name}[red]中了！[]".with("group" to it))
        }
        val leader = arg.getOrNull(0)?.let {
            val p = depends("wayzer/user/shortID")?.import<(String) -> String?>("getUUIDbyShort")?.invoke(it)
                    ?.let { id -> Groups.player.find { it.uuid() == id } }
                    ?: returnReply("[red]找不到玩家,请使用/list查询正确的3位id[]".with())
            group[p.uuid()]?.leader ?: returnReply("[red]该玩家不在队伍中".with())
        } ?: returnReply("[red]请输入正确的{option}[]".with("option" to "玩家ID"))
        if (CompetitionService.gaming && player!!.team() != leader.team()) returnReply("[red]比赛中不能切换队伍！".with())
        group[leader.uuid()]!!.let {
            it.member.add(player!!)
            broadcast("{name}[yellow]已加入队伍[][]".with("name" to player!!.coloredName()), players=it.member, quite=true)
        }
        group[player!!.uuid()] = group[leader.uuid()]!!
        if (player!!.team() != leader.team()) base.changeTeam(player!!, leader.team(), true)
    }
}
commands += CommandInfo(this, "leave", "离开队伍") {
    aliases = listOf("离开")
    body {
        group[player!!.uuid()]?.let {
            player.sendMessage("[yellow]你已离开[]{group.name}[yellow]队伍[]".with("group" to it))
            player!!.leaveGroup()
        } ?: returnReply("[red]你还未加入队伍！[]".with())
    }
}
commands += CommandInfo(this, "info", "队伍信息") {
    aliases = listOf("详情")
    body {
        group[player!!.uuid()]?.let {
            val msg = """
                | [yellow]==== 队伍 []{group.name}[yellow] ====[]
                | [yellow]队长：{group.leader.name}[]
                | [cyan]队员：{member}[]
            """.trimMargin().with("group" to it, "member" to it.member.filter{ p -> p != it.leader }.joinToString { it.coloredName() })
            this@body.player.sendMessage(msg, MsgType.InfoMessage)
            return@body
        } ?: returnReply("[red]你还没有加入队伍！".with())
    }
}
commands += CommandInfo(this, "rename", "修改队伍名") {
    aliases = listOf("改名")
    usage = "<队伍名>"
    body {
        val name = arg.getOrNull(0)?:replyUsage()
        group[player!!.uuid()]?.let {
            if (player != it.leader) returnReply("[red]仅队长可修改队伍名！".with())
            it.name = name
        } ?: returnReply("[red]你还未加入队伍！".with())
    }
}

commands += CommandInfo(this, "debug", "") {
    body {
        player.sendMessage(arg[0].with("group" to group[player!!.uuid()], "player" to player))
    }
}

listenTo<Module.PlayerTeamChangeEvent> {
    if (!CompetitionService.gaming && group[player.uuid()] != null) {
        if (!isLeader(player)) {
            cancelled = true
            player.sendMessage("[yellow]仅队长可选择队伍".with(), MsgType.InfoMessage)
        } else {
            group[player.uuid()]!!.member.forEach { member ->
                if (member != player) {
                    launch(Dispatchers.game) {
                        base.changeTeam(member, player.team(), true)
                    }
                }
            }
        }
    }
}

