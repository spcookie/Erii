package uesugi

import kotlinx.coroutines.runBlocking
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.globalEventChannel
import top.mrxiaom.overflow.BotBuilder

fun main() {

    runBlocking {
        // 正向 WebSocket
        val bot = BotBuilder.positive("ws://127.0.0.1:3001").token("hG8dQqGk6jGC").connect()
        bot?.globalEventChannel()?.subscribeAlways<GroupMessageEvent> {
            for (singleMessage in it.message) {
                println(singleMessage.contentToString())
            }
            } ?: println("Bot is null")
    }

}