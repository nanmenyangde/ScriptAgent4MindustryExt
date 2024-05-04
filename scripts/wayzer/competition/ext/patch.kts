@file:Depends("wayzer/maps", "获取地图信息")
@file:Depends("coreMindustry/menu", "菜单选人")
@file:Depends("wayzer/competition/competition", "比赛系统")
@file:Depends("wayzer/map/betterTeam")
package wayzer.competition.ext

import cf.wayzer.placehold.DynamicVar
import cf.wayzer.placehold.PlaceHoldApi.with
import coreMindustry.PagedMenuBuilder
import wayzer.VoteEvent
import wayzer.competition.CompetitionService

var patchesToLoad : Collection<MapPatch> = setOf()
export(::patchesToLoad)

@Savable
var loadedPatches : Collection<MapPatch> = setOf()

registerVarForType<MapPatch>().apply {
    registerChild("name", "突变名", DynamicVar.obj { it.name })
    registerChild("desc", "突变描述", DynamicVar.obj { it.desc })
    registerChild("env", "适用环境", DynamicVar.obj { it.env.joinToString { it.name } })
}
registerVar("scoreBroad.ext.mapPatch", "地图突变", DynamicVar.v {
    "[violet]本地图带有以下突变：${loadedPatches.joinToString { it.name }}".takeIf { loadedPatches.isNotEmpty() }
})

BuiltinPatch.onLoad(thisContextScript())

listen<EventType.WorldLoadBeginEvent> {
    if (patchesToLoad.isEmpty()) return@listen
    patchesToLoad.forEach{it()}
    loadedPatches = patchesToLoad
    patchesToLoad = setOf()
}

listen<EventType.PlayerJoin> {
    if (loadedPatches.isEmpty()) return@listen
    with(it.player) {
        var msg = ("" +
            "| [violet]本局游戏地图带有以下突变[]" +
        "").trimMargin()
        for (patch in loadedPatches) {
            msg += """
                | [accent][gold]{mapPatch.name}[]
                | [gold]{mapPatch.desc}[]
            """.trimMargin().with("mapPatch" to patch)
        }
        sendMessage(msg.with(), MsgType.InfoMessage)
    }
}

command("patch", "地图突变指令") {
    body(commands)
}
val commands = Commands()
commands += CommandInfo(this, "list", "突变列表") {
    body {
        if (player != null) {
            PagedMenuBuilder(PatchManager.patches, selectedPage = arg.firstOrNull()?.toIntOrNull() ?: 1) { patch ->
                option(buildString {
                    append("[gold]${patch.name}")
                    appendLine(" [white]${patch.desc}")
                    append("[cyan]适用环境：${patch.env.joinToString { it.name }}")
                }) {
                    close()
                }
            }.sendTo(player!!, 60_000)
        } else {
            val page = arg.firstOrNull()?.toIntOrNull() ?: 1
            reply(menu("地图突变列表", PatchManager.patches, page, 10) {
                "[light_yellow]{name} [white]{desc}  [light_cyan]{env}".with(
                        "name" to it.name, "desc" to it.desc,
                        "env" to it.env.joinToString { env -> env.name }
                )
            })
        }
    }
}

fun setPatch(patch: MapPatch) {
    patchesToLoad = setOf(patch)
    val msg = """
                    | [green]本场游戏添加突变：
                    | [accent][gold]{mapPatch.name}[]
                    | [gold]{mapPatch.desc}[]
                """.trimMargin().with("mapPatch" to patch)
    broadcast(msg, quite=true)
    broadcast(msg, MsgType.InfoMessage, quite=true)
}

ScriptManager.getScriptNullable("wayzer/competition/competition").let {
    onEnable {
        VoteEvent.VoteCommands += CommandInfo(this, "randompatch", "添加随机突变") {
            body {
                if (CompetitionService.loading || CompetitionService.gaming) returnReply("[red]只能在准备阶段添加".with())
                val event = VoteEvent(
                        script!!, player!!, this@CommandInfo.description,
                        canVote = { it.team() != CompetitionService.teams.spectateTeam },
                )
                if (event.awaitResult()) {
                    PatchManager.randomOrNull(state.rules)?.let{
                        setPatch(it)
                    } ?: player?.sendMessage("没有适用于当前地图的突变".with())
                }
            }
        }
        VoteEvent.VoteCommands.autoRemove(this)
    }
    contextScript<wayzer.competition.Competition>().commands += CommandInfo(this, "patch", "添加随机突变") {
        usage = "[突变名，不填为随机]"
        body {
            if (CompetitionService.loading || CompetitionService.gaming) returnReply("[red]只能在准备阶段添加".with())
            if (arg.isEmpty()) {
                PatchManager.randomOrNull(state.rules)?.let{
                    setPatch(it)
                } ?: player?.sendMessage("没有适用于当前地图的突变".with())
            } else {
                if (arg.size > 1) replyUsage()
                PatchManager.findOrNull(arg[0])?.let {
                    setPatch(it)
                } ?: player?.sendMessage("未找到该突变".with())
            }
        }
    }
}
