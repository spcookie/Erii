package uesugi.plugin.builtin.chat

import org.pf4j.Extension
import uesugi.core.state.volition.speakV
import uesugi.plugin.builtin.Builtin
import uesugi.plugin.builtin.BuiltinExtension
import uesugi.spi.AgentExtension
import uesugi.spi.PluginContext
import uesugi.spi.RouteExtension
import kotlin.uuid.ExperimentalUuidApi

@Extension(points = [AgentExtension::class])
class Chat : RouteExtension<Builtin>, BuiltinExtension {
    override val name: String
        get() = "builtin_chat"

    override val matcher: Pair<String, String>
        get() = "CHAT" to """
                当消息不属于其他类型时，默认归类为 CHAT。
        
                包括但不限于：
                - 群内日常闲聊、接话、调侃
                - 情绪表达、吐槽、发泄
                - 玩梗、辱骂、攻击性语言
                - 不追求严谨或标准答案的对话
        
                判断要点：
                - 更偏向互动、情绪或气氛
                - 即使内容不雅、粗俗或激烈
                - 只要不是索要图片，也不是严肃求解答
                都应归类为 CHAT，并进入人格 Agent 处理。
                """.trimIndent()

    @OptIn(ExperimentalUuidApi::class)
    override fun onLoad(context: PluginContext) {
        context.chain { meta ->
            speakV(
                meta.botId,
                meta.groupId,
                meta.senderId,
                meta.input,
                meta.echo
            )
        }
    }
}
