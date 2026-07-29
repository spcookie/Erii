# Erii SPI — 插件开发框架

基于 KSP 编译期代码生成的 PF4J 插件框架。所有注解定义在 `erii-spi-annotation`，运行时接口定义在 `erii-spi-core`。

## Gradle 配置

```kotlin
// erii-plugins/<your-plugin>/build.gradle.kts
plugins {
    id("uesugi.erii-plugin")
}

version = "1.0.0"

dependencies {
    // 按需添加外部依赖
}
```

然后在 `erii-plugins/settings.gradle.kts` 中 `include("your-plugin")`。

> `uesugi.erii-plugin` 是一个 Gradle 约定插件，自动配置 KSP、Kotlin 序列化、PF4J 打包等。

---

## 入口注解

### `@file:Definition`

**必须**放在 Kotlin 文件顶部，标记该文件需要 KSP 处理。

```kotlin
@file:Definition

package com.example.plugin
```

可选参数（会覆盖 `gradle.properties` 中的默认值）：

| 参数            | 说明             |
|---------------|----------------|
| `pluginId`    | 插件 ID          |
| `version`     | 版本号            |
| `requires`    | 依赖的插件 ID（逗号分隔） |
| `description` | 插件描述           |
| `provider`    | 插件提供者          |
| `license`     | 许可证            |

---

## 生命周期注解

### `@OnLoad` / `@OnUnload`

扩展加载/卸载时调用。支持两种模式：

**全局模式**（无参数，应用到此文件所有扩展）：

```kotlin
@OnLoad
suspend fun init() {
    val kv = useKv()
    val config = useConfig()()
    // ...
}

@OnUnload
fun cleanup() {
    // 释放资源
}
```

**命名模式**（通过 `value` 参数指定，按需引用）：

```kotlin
@OnLoad("cacheWarm")
suspend fun warmCache() {
    // 预加载缓存，如从 KV 加载数据
}

@Route(key = "IMAGE_SEARCH", desc = "search images by keyword", onLoad = ["cacheWarm"])
suspend fun searchImage(meta: Meta): String {
    ...
}
```

> 全局 `@OnLoad`/`@OnUnload` 仅当文件内有至少一个扩展函数（`@Route`/`@Cmd`/`@Passive`/`@LLMTool`
> ）或被其他扩展引用时才生效。纯生命周期 + 无扩展时，KSP 自动生成 `GeneratedPassive_default` 承载。

### `@OnStart` / `@OnStop`

插件启动/停止时调用，在 `GeneratedPlugin` 中生成。**没有**插件上下文（不能调用 `useKv()`、`useConfig()` 等）。

```kotlin
@OnStart
suspend fun onStart() {
    // 初始化全局资源，不放这里用 @OnLoad 替代
}
```

### `@OnEvent`

订阅系统事件。函数必须接收一个 `IntegrationEvent` 子类参数。

```kotlin
@OnEvent(MessageReceivedEvent::class)
suspend fun handleMessage(event: MessageReceivedEvent) {
    // 处理消息事件
}
```

---

## 扩展注解

### `@Route` — LLM 路由扩展

由 `RoutingAgent` 根据 LLM 意图分类（`key` 为路由标识，`desc` 为 LLM 判断依据）自动匹配调用。

```kotlin
@Route(
    key = "REQUEST_R18_IMAGE",
    desc = "request an R18 image from lolisuki",
    description = "发送一张随机图片",
)
suspend fun lolisukiRoute(meta: Meta): String {
    // ...
}
```

| 参数                    | 说明                         |
|-----------------------|----------------------------|
| `key`                 | LLM 路由标识，用于匹配后的路由 key      |
| `desc`                | LLM 路由判断依据，描述什么情况下匹配到此路由   |
| `description`         | 面向用户展示的扩展说明（默认空字符串）        |
| `toolSets`            | 关联的工具集名称（默认 `["default"]`） |
| `onLoad` / `onUnload` | 引用的命名生命周期函数名               |

函数签名要求：`suspend fun xxx(meta: Meta): String`。

### `@Cmd` — 命令扩展

通过 `/cmd` 前缀匹配调用，适合聊天中的命令交互。

```kotlin
@Cmd(name = "ping", description = "检查插件是否在线", alias = ["p"])
suspend fun ping(meta: Meta, args: List<String>): String {
    return "pong!"
}
```

| 参数                    | 说明              |
|-----------------------|-----------------|
| `name`                | 命令名（匹配 `/name`） |
| `description`         | 面向用户展示的扩展说明（默认空字符串） |
| `alias`               | 命令别名            |
| `toolSets`            | 关联工具集名称         |
| `onLoad` / `onUnload` | 引用的命名生命周期函数名    |

函数签名要求：`suspend fun xxx(meta: Meta, args: List<String>): String`。

### `@Passive` — 被动扩展

常驻后台的扩展，通过 `context.chain {}` 注册消息处理器，或注册工具集和事件监听。

```kotlin
@Passive(description = "处理每条群消息")
suspend fun myHandler(meta: Meta) {
    // 每条消息都会经过这里
    val input = meta.input ?: return
    // 处理逻辑...
}
```

`description` 是面向用户展示的扩展说明，默认为空字符串；其余参数与 `@Cmd` 的 `toolSets`、生命周期引用一致。

函数签名要求：`suspend fun xxx(meta: Meta)`。

不需要被动处理消息时，`@Passive` 的最小形式是空函数，仅用于触发扩展生成以承载 `@OnLoad`。KSP 会在有无扩展但有 `@OnLoad`/
`@LLMTool` 时自动生成默认 `PassiveExtension`。

### `@LLMTool` / `@LLMDesc` — LLM 工具注册

将 Kotlin 函数暴露为 LLM 的 Function Calling 工具。

```kotlin
@LLMTool(name = "get_weather", set = "default")
@LLMDesc("Get current weather for a city")
suspend fun getWeather(
    @LLMDesc("City name") city: String,
    @LLMDesc("Temperature unit: celsius or fahrenheit") unit: String = "celsius",
): String {
    // 查询天气逻辑
    return "Weather in $city: 22°C"
}
```

| 注解         | 目标                   | 说明                                                  |
|------------|----------------------|-----------------------------------------------------|
| `@LLMTool` | Function             | 标记为 LLM 工具；`name` 指定工具名，`set` 指定工具集（默认 `"default"`） |
| `@LLMDesc` | Function / Parameter | 工具/参数的描述，会映射为 `@LLMDescription`                     |

> 当只有 `@LLMTool` 没有扩展时，KSP 自动生成 `GeneratedPassive_default` 并在 `onLoad()` 中注册工具集。

---

## 扩展类型总结

| 注解         | 对应接口               | 调用方式                 |
|------------|--------------------|----------------------|
| `@Route`   | `RouteExtension`   | LLM 意图匹配             |
| `@Cmd`     | `CmdExtension`     | `/cmd` 前缀匹配          |
| `@Passive` | `PassiveExtension` | 后台常驻 + 链式处理          |
| `@LLMTool` | `MetaToolSet`      | LLM Function Calling |

---

## 代码生成规则

KSP 处理时机：发现 `@file:Definition` 的文件。

| 文件内容                                   | 生成结果                                                   |
|----------------------------------------|--------------------------------------------------------|
| 仅 `@Route` / `@Cmd` / `@Passive`       | 各自生成对应扩展类                                              |
| 仅 `@LLMTool`                           | 生成 `GeneratedPassive_default` + `GeneratedToolSet_xxx` |
| 仅 `@OnLoad` / `@OnUnload` / `@OnEvent` | 生成 `GeneratedPassive_default` 承载生命周期                   |
| `@LLMTool` + 生命周期（无扩展）                 | 生成一个 `GeneratedPassive_default`，含工具注册和生命周期调用           |
| 同时有扩展 + 工具 + 生命周期                      | 工具注册到关联扩展；`@OnEvent` 由单独的事件扩展统一承载                          |

---

## PluginContext 服务

通过 `useXxx()` 在 suspend 函数中获取：

| 函数                                                | 类型               | 说明                                  |
|---------------------------------------------------|------------------|-------------------------------------|
| `useMem()`                                        | `Mem`            | Redis 风格的键值存储（支持过期）                 |
| `useKv()`                                         | `Kv`             | 持久化键值存储                             |
| `useBlob()`                                       | `Blob`           | 文件/二进制存储                            |
| `useVector()`                                     | `Vector`         | 向量嵌入和语义搜索                           |
| `useConfig()`                                     | `PluginConfig`   | 插件配置（`invoke()` 返回 TypeSafe Config） |
| `useDatabase()`                                   | `Database`       | 数据库查询和执行                            |
| `useScheduler()`                                  | `Scheduler`      | 定时任务调度                              |
| `useLLM()`                                        | `PromptExecutor` | LLM 调用                              |
| `useHttp()`                                       | `HttpClient`     | HTTP 客户端                            |
| `useServer()`                                     | `Server`         | Ktor 路由注册                           |
| `useRegisterCommandExample(example, description)` | `Unit`           | 注册可被 CLI/Web 搜索的插件命令示例              |
| `useMeta()`                                       | `Meta`           | 当前消息元数据（botId、groupId、input 等）      |

> 这些函数必须在 `withPluginContext {}` 或扩展处理器等框架管理的协程中调用，否则抛出 `NO_CONTEXT_ERROR`。

自定义 HTTP 前缀时必须先调用 `server.registerPrefix(prefix)`，再读取 `server.contextUrl` 或生成 HTML
资源地址，最后调用 `server.route {}`。`contextUrl` 反映读取当时的有效前缀；若自定义前缀冲突，框架会降级到
插件默认名称，因此不要手工拼接请求前缀。

CLI/Web 插件发送会触发 `CliPluginEvent(input, echo)`；插件如需返回同步结果，可以发布同 `echo` 的
`CliPluginReplyEvent(echo, message)`。Core 会取最后一条匹配回复作为 send 返回值；没有回复时返回 `null`。
整个同步事件分发最多等待 5 秒；超时返回 HTTP 504，任一处理器失败返回 HTTP 500。
事件执行池饱和时返回 HTTP 503，避免插件阻塞持续占用 Ktor 请求线程。

### Meta 字段

```kotlin
interface Meta {
    val botId: String      // 当前 bot ID
    val groupId: String    // 当前群 ID
    val roledBot: IBotManage.RoledBot  // 角色化 Bot 信息
    val input: String?     // 用户输入
    val senderId: String?  // 发送者 ID
    val echo: String?      // 回显标识
}
```

### Meta.sendAgent()

触发 AI Agent 处理并回复消息：

```kotlin
@Route(key = "xxx", desc = "xxx")
suspend fun chat(meta: Meta): String {
    meta.sendAgent("请帮我查一下天气")
    return "ok"
}
```

---

## 插件配置

每个插件的 `src/main/resources/plugin.json` 是 TypeSafe Config 格式（HOCON/JSON）的配置文件。通过 `useConfig()()` 获取。

```json
{
  "bot": {
    "some-key": "${?ENV_VAR}",
    "another-key": "default-value"
  }
}
```

`${?ENV_VAR}` 是 HOCON 可选环境变量替换语法，但 JSON 解析器不识别。框架在 `ConfigHolderImpl` 中已调用 `.resolve()`
处理，插件也可在获取后手动调用 `config.resolve()` 确保替换。

---

## 示例：最小插件

```kotlin
@file:Definition

package com.example.plugin

import uesugi . spi . Meta
        import uesugi . spi . annotation . *

        @OnLoad
        suspend fun init() {
            val config = useConfig()()
            val kv = useKv()
            // 初始化逻辑
        }

@Passive
suspend fun handler(meta: Meta) {
    val input = meta.input ?: return
    if (input.contains("hello")) {
        meta.sendAgent("Hi there!")
    }
}
```
