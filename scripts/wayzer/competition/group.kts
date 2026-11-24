@file:Depends("wayzer/map/betterTeam")
@file:Depends("wayzer/competition/competition")

package wayzer.competition

import coreLibrary.lib.with
import wayzer.map.TeamService

val base by Services.get<CompetitionTeamService>().notNull
val teams by Services.get<TeamService>().notNull

val group get() = GroupChat.group

fun isLeader(p: Player): Boolean {
    return group[p.uuid()]?.leader?.uuid() == p.uuid()
}
fun inSameGroup(players: Set<Player>): Boolean {
    if (players.size == 1) return true
    return players.firstOrNull()?.let {
        group[it.uuid()]?.member?.containsAll(players) ?: false
    } ?: false
}

export(::isLeader)
export(::inSameGroup)

registerVarForType<PlayerGroup>().apply {
    registerChild("name", "队伍名") { it.name }
    registerChild("leader", "队长") { it.leader }

}

registerVarForType<Player>().apply {
    registerChild("group", "所在队伍") { group[it.uuid()] }
    registerChild("prefix.2groupName", "队伍名") {
        if (!enabled) return@registerChild null
        group.getOrDefault(it.uuid(), null)?.name?.let { "<$it>" }
    }
}

val PlayerGroup.onlineMembers get() = member.filter { it.con != null && it.con.isConnected }

fun PlayerGroup.disband() {
    broadcast("{group.name}[red]队伍已被解散！[]".with("group" to this), players = onlineMembers, quite = true)
    for (player in member) {
        group.remove(player.uuid())
    }
}
fun Player.leaveGroup() {
    group[uuid()]?.let {
        if (it.leader.uuid() == uuid()) it.disband()
        else {
            group.remove(uuid(), it)
            it.member.remove(this)
            broadcast("{name}[yellow]已离开队伍[]".with("name" to coloredName()), players = it.onlineMembers, quite = true)
        }
    }
}

GroupChat.onLoad()

listen<EventType.PlayerJoin> {
    val player = it.player
    val playerGroup = group[player.uuid()] ?: return@listen
    playerGroup.member.removeAll { it.uuid() == player.uuid() }
    playerGroup.member.add(player)
    if (playerGroup.leader.uuid() == player.uuid()) playerGroup.leader = player
}

val commands = Commands()
command("group", "队伍管理指令") {
    aliases = listOf("队伍")
    attr(ClientOnly)
    body(commands)
}
commands += CommandInfo(this, "create", "创建队伍") {
    aliases = listOf("创建")
    usage = "<队伍名>"
    body {
        group[player!!.uuid()]?.let {
            returnReply("[red]你已经在队伍[]{group.name}[red]中了！[]".with("group" to it))
        }
        val name = colorClearedName(arg.getOrNull(0) ?: replyUsage())
        if (group.values.any { it.name == name }) returnReply("[red]该队伍名已被使用".with())
        player!!.let {
            val newGroup = PlayerGroup(it, mutableSetOf(it), name)
            group[it.uuid()] = newGroup
            returnReply("[yellow]队伍[]{group.name}[yellow]已创建成功[]".with("group" to newGroup))
        }
    }
}
val adminCommands = Commands()
commands += CommandInfo(this, "admin", "队伍管理指令") {
    requirePermission("competition.admin")
    body(adminCommands)
}
adminCommands += CommandInfo(this, "create", "指定队长创建队伍") {
    usage = "<队长3位ID> <队伍名>"
    body {
        val target = arg.getOrNull(0)?.let {
            PlayerData.findByShortId(it)?.player
                ?: returnReply("[red]找不到玩家,请使用/list查询正确的3位id[]".with())
        } ?: returnReply("[red]请输入正确的{option}[]".with("option" to "队长ID"))
        group[target.uuid()]?.let {
            returnReply("[red]目标玩家已经在队伍[]{group.name}[red]中了！[]".with("group" to it))
        }
        val name = colorClearedName(arg.getOrNull(1) ?: replyUsage())
        if (group.values.any { it.name == name }) returnReply("[red]该队伍名已被使用".with())
        val newGroup = PlayerGroup(leader = target, member = mutableSetOf(target), name = name)
        group[target.uuid()] = newGroup
        if (target != player) {
            target.sendMessage("[yellow]管理员已为你创建队伍[]{group.name}[yellow]，你是该队伍队长[]".with("group" to newGroup), MsgType.InfoMessage)
        }
        returnReply("[yellow]已创建队伍[]{group.name}[yellow]，队长为[]{player.name}[]".with("player" to target, "group" to newGroup))
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
            val p = PlayerData.findByShortId(it)?.player
                ?: returnReply("[red]找不到玩家,请使用/list查询正确的3位id[]".with())
            group[p.uuid()]?.leader ?: returnReply("[red]该玩家不在队伍中".with())
        } ?: returnReply("[red]请输入正确的{option}[]".with("option" to "玩家ID"))
        if (CompetitionService.gaming && player!!.team() != leader.team()) returnReply("[red]比赛中不能切换队伍！".with())
        group[leader.uuid()]!!.let {
            it.member.add(player!!)
            broadcast("{name}[yellow]已加入队伍[][]".with("name" to player!!.coloredName()), players = it.onlineMembers, quite = true)
        }
        group[player!!.uuid()] = group[leader.uuid()]!!
        if (player!!.team() != leader.team()) base.changeTeam(player!!, leader.team(), true)
    }
}
commands += CommandInfo(this, "kick", "踢出队伍成员") {
    aliases = listOf("踢出")
    usage = "<3位ID>"
    body {
        val player = player!!
        val playerGroup = group[player.uuid()] ?: returnReply("[red]你还没有加入队伍！".with())
        if (playerGroup.leader.uuid() != player.uuid()) returnReply("[red]仅队长可踢出队伍成员".with())
        val target = arg.getOrNull(0)?.let {
            PlayerData.findByShortId(it)?.player
                ?: returnReply("[red]找不到玩家,请使用/list查询正确的3位id[]".with())
        } ?: returnReply("[red]请输入正确的{option}[]".with("option" to "玩家ID"))
        if (target.uuid() == player.uuid()) returnReply("[red]不能踢出自己，请使用/group leave解散队伍".with())
        if (group[target.uuid()] != playerGroup) returnReply("[red]该玩家不在你的队伍中".with())
        group.remove(target.uuid(), playerGroup)
        playerGroup.member.remove(target)
        target.sendMessage("[yellow]你已被队长踢出队伍[]{group.name}[]".with("group" to playerGroup), MsgType.InfoMessage)
        broadcast(
            "{name}[yellow]已被队长踢出队伍[]".with("name" to target.coloredName()),
            players = playerGroup.onlineMembers, quite = true
        )
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
            """.trimMargin().with("group" to it, "member" to it.member.filter { p -> p != it.leader }.joinToString { it.coloredName() })
            this@body.player.sendMessage(msg, MsgType.InfoMessage)
            return@body
        } ?: returnReply("[red]你还没有加入队伍！".with())
    }
}
commands += CommandInfo(this, "rename", "修改队伍名") {
    aliases = listOf("改名")
    usage = "<队伍名>"
    body {
        val name = arg.getOrNull(0) ?: replyUsage()
        group[player!!.uuid()]?.let {
            if (!isLeader(player!!)) returnReply("[red]仅队长可修改队伍名！".with())
            it.name = name
        } ?: returnReply("[red]你还未加入队伍！".with())
    }
}

listenTo<PlayerTeamChangeEvent> {
    if (!CompetitionService.gaming && group[player.uuid()] != null) {
        if (!isLeader(player)) {
            cancelled = true
            player.sendMessage("[yellow]仅队长可选择队伍".with(), MsgType.InfoMessage)
        } else {
            group[player.uuid()]!!.onlineMembers.forEach { member ->
                if (member != player) {
                    launch(Dispatchers.game) {
                        base.changeTeam(member, to, true)
                    }
                }
            }
        }
    }
}
