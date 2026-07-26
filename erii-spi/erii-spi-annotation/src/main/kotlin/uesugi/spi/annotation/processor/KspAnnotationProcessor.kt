@file:OptIn(KspExperimental::class)

package uesugi.spi.annotation.processor

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*

class KspAnnotationProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {

    private companion object {
        const val DEFAULT_TOOLSET = "default"
        const val TOOLSET_CLASS_PREFIX = "GeneratedToolSet_"
        const val PF4J_EXTENSION_PATH = "META-INF/services/org.pf4j.Extension"

        const val ANN_ROUTE = "uesugi.spi.annotation.Route"
        const val ANN_CMD = "uesugi.spi.annotation.Cmd"
        const val ANN_PASSIVE = "uesugi.spi.annotation.Passive"
        const val ANN_TOOL = "uesugi.spi.annotation.LLMTool"
        const val ANN_DEFINITION = "uesugi.spi.annotation.Definition"
        const val ANN_LLM_DESC = "uesugi.spi.annotation.LLMDesc"
        const val ANN_CHAT_MESSAGE = "uesugi.common.ChatMessage"
        const val ANN_ON_LOAD = "uesugi.spi.annotation.OnLoad"
        const val ANN_ON_UNLOAD = "uesugi.spi.annotation.OnUnload"
        const val ANN_ON_START = "uesugi.spi.annotation.OnStart"
        const val ANN_ON_STOP = "uesugi.spi.annotation.OnStop"
        const val ANN_ON_EVENT = "uesugi.spi.annotation.OnEvent"

        private val CMD_SLOTS = listOf(
            ParamSlot("meta", "uesugi.spi.Meta", "meta"),
            ParamSlot("args", "kotlin.collections.List", "holder.args"),
        )

        private val ROUTE_PASSIVE_SLOTS = listOf(
            ParamSlot("meta", "uesugi.spi.Meta", "meta"),
        )
    }

    // ========== Parameter Slots ==========

    /**
     * Defines an available parameter slot for annotation-generated functions.
     * Functions may accept a PREFIX of these slots in declared order.
     */
    private data class ParamSlot(
        val name: String,
        val qualifiedType: String,
        val valueExpression: String,
    )

    private var processed = false

    // 多个 extension 共用 default toolset 时，仅首次注册，避免 ToolRegistry 重复定义
    private var defaultToolSetEmitted = false
    private val availableToolSets = mutableSetOf<String>()

    // ========== Entry ==========

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (processed) return emptyList()
        defaultToolSetEmitted = false
        availableToolSets.clear()

        val (pkgName, defAnno) = findFileDefinition(resolver) ?: run {
            logger.warn("No @file:Definition found, skipping KSP processing")
            return emptyList()
        }

        val routeFunctions = resolver.getSymbolsWithAnnotation(ANN_ROUTE)
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.checkVisibility("Route") }
            .toList()

        val cmdFunctions = resolver.getSymbolsWithAnnotation(ANN_CMD)
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.checkVisibility("Cmd") }
            .toList()

        val passiveFunctions = resolver.getSymbolsWithAnnotation(ANN_PASSIVE)
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.checkVisibility("Passive") }
            .toList()

        val toolFunctions = resolver.getSymbolsWithAnnotation(ANN_TOOL)
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.checkVisibility("LLMTool") }
            .filter { it.parent !is KSClassDeclaration } // skip @Tool on class methods (handled by MetaToolSet subclass)
            .toList()

        // Lifecycle functions
        val onLoadFunctions = resolver.getSymbolsWithAnnotation(ANN_ON_LOAD)
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.checkVisibility("OnLoad") }
            .toList()
        onLoadFunctions.forEach { it.validateLifecycleParams("OnLoad") }
        val namedOnLoad = onLoadFunctions
            .filter { it.findAnnotation(ANN_ON_LOAD)?.stringArg("value")?.isNotEmpty() == true }
            .associateBy { it.findAnnotation(ANN_ON_LOAD)!!.stringArg("value") }
        val globalOnLoad = onLoadFunctions
            .filter { it.findAnnotation(ANN_ON_LOAD)?.stringArg("value")?.isEmpty() != false }
            .map { "${it.simpleName.asString()}()" }

        val onUnloadFunctions = resolver.getSymbolsWithAnnotation(ANN_ON_UNLOAD)
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.checkVisibility("OnUnload") }
            .toList()
        onUnloadFunctions.forEach { it.validateLifecycleParams("OnUnload") }
        val namedOnUnload = onUnloadFunctions
            .filter { it.findAnnotation(ANN_ON_UNLOAD)?.stringArg("value")?.isNotEmpty() == true }
            .associateBy { it.findAnnotation(ANN_ON_UNLOAD)!!.stringArg("value") }
        val globalOnUnload = onUnloadFunctions
            .filter { it.findAnnotation(ANN_ON_UNLOAD)?.stringArg("value")?.isEmpty() != false }
            .map { "${it.simpleName.asString()}()" }

        // Event functions
        val onEventFunctions = resolver.getSymbolsWithAnnotation(ANN_ON_EVENT)
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.checkVisibility("OnEvent") }
            .toList()
        onEventFunctions.forEach { it.validateOnEventParams() }
        val globalOnEvent = onEventFunctions.map { func -> buildOnEventSubscription(func) }

        // Plugin lifecycle
        val onStartFuncs = resolver.getSymbolsWithAnnotation(ANN_ON_START)
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.checkVisibility("OnStart") }
            .toList()
        if (onStartFuncs.size > 1) {
            logger.error("Only one @OnStart function allowed per plugin")
            return emptyList()
        }
        onStartFuncs.firstOrNull()?.validateLifecycleParams("OnStart")

        val onStopFuncs = resolver.getSymbolsWithAnnotation(ANN_ON_STOP)
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.checkVisibility("OnStop") }
            .toList()
        if (onStopFuncs.size > 1) {
            logger.error("Only one @OnStop function allowed per plugin")
            return emptyList()
        }
        onStopFuncs.firstOrNull()?.validateLifecycleParams("OnStop")

        // Generate
        generatePluginDelegate(pkgName, defAnno, onStartFuncs.firstOrNull(), onStopFuncs.firstOrNull())
        // KAPT PluginDefinitionProcessor generates plugin.properties from @PluginDefinition annotation
        generateToolSets(toolFunctions, pkgName)
        generateRouteExtensions(routeFunctions, pkgName, namedOnLoad, namedOnUnload, globalOnLoad, globalOnUnload, emptyList())
        generateCmdExtensions(cmdFunctions, pkgName, namedOnLoad, namedOnUnload, globalOnLoad, globalOnUnload, emptyList())
        generatePassiveExtensions(passiveFunctions, pkgName, namedOnLoad, namedOnUnload, globalOnLoad, globalOnUnload, emptyList())

        val hasLifecycle = globalOnLoad.isNotEmpty() || globalOnUnload.isNotEmpty() || globalOnEvent.isNotEmpty()
        val hasExtension = routeFunctions.isNotEmpty() || cmdFunctions.isNotEmpty() || passiveFunctions.isNotEmpty()
        val needsDefaultPassive = !hasExtension && (toolFunctions.isNotEmpty() || hasLifecycle)
        if (needsDefaultPassive) {
            generateDefaultPassive(toolFunctions, pkgName, globalOnLoad, globalOnUnload, globalOnEvent)
        }
        val needsEventPassive = hasExtension && globalOnEvent.isNotEmpty()
        if (needsEventPassive) {
            generateEventPassive(pkgName, globalOnEvent)
        }

        generatePf4jExtensionsFile(
            routeFunctions,
            cmdFunctions,
            passiveFunctions,
            pkgName,
            needsDefaultPassive,
            needsEventPassive,
        )

        processed = true
        return emptyList()
    }

    // ========== @file:Definition ==========

    private fun findFileDefinition(resolver: Resolver): Pair<String, KSAnnotation?>? {
        for (file in resolver.getNewFiles()) {
            val defAnno =
                file.annotations.find { it.annotationType.resolve().declaration.qualifiedName?.asString() == ANN_DEFINITION }
            if (defAnno != null) return file.packageName.asString() to defAnno
        }
        for (file in resolver.getAllFiles()) {
            val defAnno =
                file.annotations.find { it.annotationType.resolve().declaration.qualifiedName?.asString() == ANN_DEFINITION }
            if (defAnno != null) return file.packageName.asString() to defAnno
        }
        return null
    }

    // ========== Plugin delegate ==========

    private fun generatePluginDelegate(
        pkgName: String,
        defAnno: KSAnnotation?,
        onStartFunc: KSFunctionDeclaration?,
        onStopFunc: KSFunctionDeclaration?,
    ) {
        val className = "GeneratedPlugin"
        val needsRunBlocking = onStartFunc.isSuspendFunc() || onStopFunc.isSuspendFunc()

        val content = buildString {
            appendLine("package $pkgName")
            appendLine()
            appendLine("import uesugi.spi.AgentPlugin")
            appendLine("import uesugi.spi.PluginDefinition")
            if (needsRunBlocking) {
                appendLine("import kotlinx.coroutines.runBlocking")
            }
            appendLine()
            appendLine(buildPluginDefinitionAnnotation(defAnno))
            appendLine("class $className : AgentPlugin() {")
            if (onStartFunc != null) {
                val call = onStartFunc.simpleName.asString() + "()"
                appendLine("    override fun start() {")
                appendLine("        super.start()")
                if (onStartFunc.isSuspendFunc()) {
                    appendLine("        runBlocking { $call }")
                } else {
                    appendLine("        $call")
                }
                appendLine("    }")
            }
            if (onStopFunc != null) {
                val call = onStopFunc.simpleName.asString() + "()"
                appendLine("    override fun stop() {")
                if (onStopFunc.isSuspendFunc()) {
                    appendLine("        runBlocking { $call }")
                } else {
                    appendLine("        $call")
                }
                appendLine("        super.stop()")
                appendLine("    }")
            }
            appendLine("}")
        }

        writeFile(pkgName, className, content)
    }

    private fun buildPluginDefinitionAnnotation(defAnno: KSAnnotation?): String {
        if (defAnno == null) return "@PluginDefinition"
        val params = mutableListOf<String>()
        listOf("pluginId", "version", "requires", "dependencies", "description", "provider", "license").forEach { key ->
            val value = defAnno.stringArg(key).takeUnless { it.isEmpty() } ?: options[key]?.takeUnless { it.isEmpty() }
            if (value != null) params.add("$key = \"${value.escapeForLiteral()}\"")
        }
        return if (params.isEmpty()) "@PluginDefinition" else "@PluginDefinition(${params.joinToString(", ")})"
    }

    private fun writeFile(
        packageName: String,
        fileName: String,
        content: String,
        extensionName: String = "kt",
    ) {
        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = false),
            packageName = packageName,
            fileName = fileName,
            extensionName = extensionName,
        ).writer().use { it.write(content) }
    }

    // ========== ToolSets ==========

    private fun generateToolSets(toolFunctions: List<KSFunctionDeclaration>, pkgName: String) {
        val grouped = toolFunctions.groupBy {
            it.findAnnotation(ANN_TOOL)?.stringArg("set")?.takeUnless { s -> s.isEmpty() } ?: DEFAULT_TOOLSET
        }

        for ((setName, functions) in grouped) {
            val className = toolSetClassName(setName)
            availableToolSets.add(setName)
            val hasNonSuspend = functions.any { !it.isSuspendFunc() }

            // Pre-compute per-function data to avoid double annotation lookup
            data class FuncData(
                val func: KSFunctionDeclaration,
                val funcName: String,
                val funcIsSuspend: Boolean,
                val descAnno: KSAnnotation?,
                val chatMsgAnno: KSAnnotation?,
                val paramDecls: List<String>,
                val argNames: List<String>,
            )

            val funcsData = functions.map { func ->
                FuncData(
                    func = func,
                    funcName = func.simpleName.asString(),
                    funcIsSuspend = func.isSuspendFunc(),
                    descAnno = func.findAnnotation(ANN_LLM_DESC),
                    chatMsgAnno = func.findAnnotation(ANN_CHAT_MESSAGE),
                    paramDecls = buildToolParams(func).first,
                    argNames = buildToolParams(func).second,
                )
            }
            val hasChatMessage = funcsData.any { it.chatMsgAnno != null }

            val content = buildString {
                appendLine("package $pkgName")
                appendLine()
                appendLine("import uesugi.spi.MetaToolSet")
                appendLine("import uesugi.spi.PluginContext")
                appendLine("import uesugi.spi.annotation.withPluginContext")
                appendLine("import ai.koog.agents.core.tools.annotations.Tool as KoogTool")
                appendLine("import ai.koog.agents.core.tools.annotations.LLMDescription as KoogLLMDescription")
                if (hasChatMessage) {
                    appendLine("import uesugi.common.ChatMessage")
                }
                if (hasNonSuspend) {
                    appendLine("import kotlinx.coroutines.Dispatchers")
                    appendLine("import kotlinx.coroutines.withContext")
                }
                for (fd in funcsData) {
                    appendLine("import $pkgName.${fd.funcName} as _erii_${fd.funcName}")
                }
                appendLine()
                appendLine("class $className(private val context: PluginContext) : MetaToolSet {")

                for (fd in funcsData) {
                    if (fd.chatMsgAnno != null) {
                        appendLine("    @ChatMessage")
                    }
                    fd.descAnno?.stringArg("description")?.takeUnless { it.isEmpty() }?.let {
                        appendLine("    @KoogLLMDescription(\"${it.escapeForLiteral()}\")")
                    }
                    appendLine("    @KoogTool")

                    val argsStr = fd.argNames.joinToString(", ")
                    val callExpr = if (fd.funcIsSuspend) {
                        "withPluginContext(context) { _erii_${fd.funcName}($argsStr) }"
                    } else {
                        "withPluginContext(context) { withContext(Dispatchers.IO) { _erii_${fd.funcName}($argsStr) } }"
                    }

                    if (fd.paramDecls.isEmpty()) {
                        appendLine("    suspend fun ${fd.funcName}(): String? = $callExpr")
                    } else {
                        appendLine("    suspend fun ${fd.funcName}(")
                        appendLine(fd.paramDecls.joinToString(",\n"))
                        appendLine("    ): String? = $callExpr")
                    }
                    appendLine()
                }

                appendLine("}")
            }

            writeFile(pkgName, className, content)
        }
    }

    private fun buildToolParams(func: KSFunctionDeclaration): Pair<List<String>, List<String>> {
        val paramDecls = mutableListOf<String>()
        val argNames = mutableListOf<String>()

        for (param in func.parameters) {
            val paramType = param.type.resolve().declaration.qualifiedName?.asString()
                ?.toShortKotlinType() ?: param.type.resolve().toString()
            val paramName = param.name?.asString() ?: continue
            val descAnno = param.findAnnotation(ANN_LLM_DESC)

            val line = buildString {
                descAnno?.stringArg("description")?.takeUnless { it.isEmpty() }?.let {
                    appendLine("        @KoogLLMDescription(\"${it.escapeForLiteral()}\")")
                }
                append("        $paramName: $paramType")
            }
            paramDecls.add(line)
            argNames.add(paramName)
        }

        return paramDecls to argNames
    }

    // ========== Extension Generation ==========

    private fun generateExtensionFile(
        func: KSFunctionDeclaration,
        pkgName: String,
        className: String,
        interfaceName: String,
        slots: List<ParamSlot>,
        annotationLabel: String,
        onLoadCalls: List<String>,
        onUnloadCalls: List<String>,
        globalOnLoad: List<String>,
        globalOnUnload: List<String>,
        globalOnEvent: List<String>,
        toolSets: List<String>,
        extraProperties: StringBuilder.() -> Unit = {},
        extraOnLoad: StringBuilder.() -> Unit = {},
    ) {
        val handlerIsSuspend = func.isSuspendFunc()
        val matchedSlots = func.matchSlots(slots, annotationLabel)
        val handlerHasMeta = funcDeclaresMeta(matchedSlots, slots)
        val callExpr = buildSlotCallExpr(func.simpleName.asString(), matchedSlots, slots)

        val content = buildString {
            appendLine("package $pkgName")
            appendLine()
            emitExtensionImports(handlerHasMeta, handlerIsSuspend)
            appendLine()
            appendLine("@Extension")
            appendLine("class $className : $interfaceName<GeneratedPlugin> {")
            extraProperties()
            appendLine("    override fun onLoad(context: PluginContext) {")
            emitLifecycleCalls(onLoadCalls + globalOnLoad, "        ")
            emitLifecycleCalls(globalOnEvent, "        ")
            appendLine("        context.chain { meta ->")
            extraOnLoad()
            appendLine("            withPluginContext(context) {")
            emitHandlerInvocation(callExpr, handlerIsSuspend, handlerHasMeta, "                ")
            appendLine("            }")
            appendLine("        }")
            emitToolSetRegistrations(toolSets, pkgName, "        ")
            appendLine("    }")
            appendLine()
            appendLine("    override fun onUnload() {")
            emitLifecycleCalls(onUnloadCalls + globalOnUnload, "        ")
            appendLine("    }")
            appendLine("}")
        }

        writeFile(pkgName, className, content)
    }

    // ========== Route Extensions ==========

    private fun generateRouteExtensions(
        routeFunctions: List<KSFunctionDeclaration>,
        pkgName: String,
        namedOnLoad: Map<String, KSFunctionDeclaration>,
        namedOnUnload: Map<String, KSFunctionDeclaration>,
        globalOnLoad: List<String>,
        globalOnUnload: List<String>,
        globalOnEvent: List<String>,
    ) {
        for (func in routeFunctions) {
            val routeAnno = func.findAnnotation(ANN_ROUTE) ?: continue
            val funcName = func.simpleName.asString()
            val onLoadCalls = resolveLifecycleCalls(routeAnno.stringArrayArg("onLoad"), namedOnLoad, funcName)
            val onUnloadCalls = resolveLifecycleCalls(routeAnno.stringArrayArg("onUnload"), namedOnUnload, funcName)

            generateExtensionFile(
                func = func,
                pkgName = pkgName,
                className = "GeneratedRoute_${funcName}",
                interfaceName = "RouteExtension",
                slots = ROUTE_PASSIVE_SLOTS,
                annotationLabel = "Route",
                onLoadCalls = onLoadCalls,
                onUnloadCalls = onUnloadCalls,
                globalOnLoad = globalOnLoad,
                globalOnUnload = globalOnUnload,
                globalOnEvent = globalOnEvent,
                toolSets = routeAnno.stringArrayArg("toolSets"),
                extraProperties = {
                    appendLine("    override val matcher: Pair<String, String>")
                    appendLine(
                        "        get() = \"${
                            routeAnno.stringArg("key").escapeForLiteral()
                        }\" to \"${routeAnno.stringArg("desc").escapeForLiteral()}\""
                    )
                    appendLine()
                }
            )
        }
    }

    // ========== Cmd Extensions ==========

    private fun generateCmdExtensions(
        cmdFunctions: List<KSFunctionDeclaration>,
        pkgName: String,
        namedOnLoad: Map<String, KSFunctionDeclaration>,
        namedOnUnload: Map<String, KSFunctionDeclaration>,
        globalOnLoad: List<String>,
        globalOnUnload: List<String>,
        globalOnEvent: List<String>,
    ) {
        for (func in cmdFunctions) {
            val cmdAnno = func.findAnnotation(ANN_CMD) ?: continue
            val funcName = func.simpleName.asString()
            val cmdName = cmdAnno.stringArg("name")
            val aliases = cmdAnno.stringArrayArg("alias")
            val aliasList = aliases.joinToString(", ") { "\"$it\"" }
            val onLoadCalls = resolveLifecycleCalls(cmdAnno.stringArrayArg("onLoad"), namedOnLoad, funcName)
            val onUnloadCalls = resolveLifecycleCalls(cmdAnno.stringArrayArg("onUnload"), namedOnUnload, funcName)

            generateExtensionFile(
                func = func,
                pkgName = pkgName,
                className = "GeneratedCmd_${cmdName.replace("-", "_")}",
                interfaceName = "SlashCmdExtension",
                slots = CMD_SLOTS,
                annotationLabel = "Cmd",
                onLoadCalls = onLoadCalls,
                onUnloadCalls = onUnloadCalls,
                globalOnLoad = globalOnLoad,
                globalOnUnload = globalOnUnload,
                globalOnEvent = globalOnEvent,
                toolSets = cmdAnno.stringArrayArg("toolSets"),
                extraProperties = {
                    appendLine("    override val cmd: String")
                    appendLine("        get() = \"$cmdName\"")
                    if (aliases.isNotEmpty()) {
                        appendLine("    override val alias: List<String>")
                        appendLine("        get() = listOf($aliasList)")
                    }
                    appendLine()
                },
                extraOnLoad = {
                    appendLine("            val holder = meta.parser(Unit)")
                }
            )
        }
    }

    // ========== Passive Extensions ==========

    private fun generatePassiveExtensions(
        passiveFunctions: List<KSFunctionDeclaration>,
        pkgName: String,
        namedOnLoad: Map<String, KSFunctionDeclaration>,
        namedOnUnload: Map<String, KSFunctionDeclaration>,
        globalOnLoad: List<String>,
        globalOnUnload: List<String>,
        globalOnEvent: List<String>,
    ) {
        for (func in passiveFunctions) {
            val passiveAnno = func.findAnnotation(ANN_PASSIVE) ?: continue
            val funcName = func.simpleName.asString()
            val onLoadCalls = resolveLifecycleCalls(passiveAnno.stringArrayArg("onLoad"), namedOnLoad, funcName)
            val onUnloadCalls = resolveLifecycleCalls(passiveAnno.stringArrayArg("onUnload"), namedOnUnload, funcName)

            generateExtensionFile(
                func = func,
                pkgName = pkgName,
                className = "GeneratedPassive_${funcName}",
                interfaceName = "PassiveExtension",
                slots = ROUTE_PASSIVE_SLOTS,
                annotationLabel = "Passive",
                onLoadCalls = onLoadCalls,
                onUnloadCalls = onUnloadCalls,
                globalOnLoad = globalOnLoad,
                globalOnUnload = globalOnUnload,
                globalOnEvent = globalOnEvent,
                toolSets = passiveAnno.stringArrayArg("toolSets"),
            )
        }
    }

    // ========== Default Passive (auto-generated for tool-only/lifecycle-only plugins) ==========

    private fun generateDefaultPassive(
        toolFunctions: List<KSFunctionDeclaration>,
        pkgName: String,
        globalOnLoad: List<String>,
        globalOnUnload: List<String>,
        globalOnEvent: List<String>,
    ) {
        val toolSetClasses = toolFunctions
            .map { it.findAnnotation(ANN_TOOL)?.stringArg("set")?.takeUnless { s -> s.isEmpty() } ?: DEFAULT_TOOLSET }
            .distinct()
            .filter { it in availableToolSets }
            .map { toolSetClassName(it) }

        val hasLifecycle = globalOnLoad.isNotEmpty() || globalOnUnload.isNotEmpty() || globalOnEvent.isNotEmpty()
        val className = "GeneratedPassive_default"

        val content = buildString {
            appendLine("package $pkgName")
            appendLine()
            if (hasLifecycle) {
                appendLine("import kotlinx.coroutines.runBlocking")
                appendLine("import uesugi.spi.annotation.withPluginContext")
            }
            appendLine("import org.pf4j.Extension")
            appendLine("import uesugi.spi.PassiveExtension")
            appendLine("import uesugi.spi.PluginContext")
            appendLine()
            appendLine("@Extension")
            appendLine("class $className : PassiveExtension<GeneratedPlugin> {")
            appendLine("    override fun onLoad(context: PluginContext) {")
            for (call in globalOnLoad + globalOnEvent) {
                if (hasLifecycle) {
                    appendLine("        runBlocking { withPluginContext(context) { $call } }")
                } else {
                    appendLine("        $call")
                }
            }
            for (tsClass in toolSetClasses) {
                appendLine("        context.tool { { $pkgName.$tsClass(context) } }")
            }
            appendLine("    }")
            appendLine()
            appendLine("    override fun onUnload() {")
            for (call in globalOnUnload) {
                appendLine("        $call")
            }
            appendLine("    }")
            appendLine("}")
        }

        writeFile(pkgName, className, content)
    }

    private fun generateEventPassive(
        pkgName: String,
        globalOnEvent: List<String>,
    ) {
        val className = "GeneratedPassive_events"
        val content = buildString {
            appendLine("package $pkgName")
            appendLine()
            appendLine("import org.pf4j.Extension")
            appendLine("import uesugi.spi.PassiveExtension")
            appendLine("import uesugi.spi.PluginContext")
            appendLine("import uesugi.spi.annotation.withPluginContext")
            appendLine()
            appendLine("@Extension")
            appendLine("class $className : PassiveExtension<GeneratedPlugin> {")
            appendLine("    override fun onLoad(context: PluginContext) {")
            emitLifecycleCalls(globalOnEvent, "        ")
            appendLine("    }")
            appendLine()
            appendLine("    override fun onUnload() = Unit")
            appendLine("}")
        }
        writeFile(pkgName, className, content)
    }

    // ========== PF4J Extensions file ==========

    private fun generatePf4jExtensionsFile(
        routeFunctions: List<KSFunctionDeclaration>,
        cmdFunctions: List<KSFunctionDeclaration>,
        passiveFunctions: List<KSFunctionDeclaration>,
        pkgName: String,
        needsDefaultPassive: Boolean,
        needsEventPassive: Boolean,
    ) {
        val extensionClasses = buildList {
            for (func in routeFunctions) {
                add("$pkgName.GeneratedRoute_${func.simpleName.asString()}")
            }
            for (func in cmdFunctions) {
                val cmdName = func.findAnnotation(ANN_CMD)?.stringArg("name")?.replace("-", "_") ?: continue
                add("$pkgName.GeneratedCmd_$cmdName")
            }
            for (func in passiveFunctions) {
                add("$pkgName.GeneratedPassive_${func.simpleName.asString()}")
            }
            if (needsDefaultPassive) {
                add("$pkgName.GeneratedPassive_default")
            }
            if (needsEventPassive) {
                add("$pkgName.GeneratedPassive_events")
            }
        }

        if (extensionClasses.isEmpty()) return

        val content = extensionClasses.joinToString("\n")
        writeFile("", PF4J_EXTENSION_PATH, content, "")
    }

    // ========== Import / Handler / Lifecycle helpers ==========

    private fun StringBuilder.emitExtensionImports(handlerHasMeta: Boolean, handlerIsSuspend: Boolean) {
        appendLine("import org.pf4j.Extension")
        appendLine("import uesugi.spi.*")
        appendLine("import uesugi.spi.annotation.withPluginContext")
        when {
            !handlerHasMeta && handlerIsSuspend -> appendLine("import uesugi.spi.annotation.withMeta")
            !handlerHasMeta && !handlerIsSuspend -> appendLine("import uesugi.spi.annotation.withMetaIO")
            handlerHasMeta && !handlerIsSuspend -> {
                appendLine("import kotlinx.coroutines.Dispatchers")
                appendLine("import kotlinx.coroutines.withContext")
            }
        }
    }

    private fun StringBuilder.emitHandlerInvocation(
        callExpr: String, handlerIsSuspend: Boolean, handlerHasMeta: Boolean, indent: String
    ) {
        when {
            !handlerHasMeta && handlerIsSuspend -> {
                appendLine(indent + "withMeta(meta) {")
                appendLine("$indent    $callExpr")
                appendLine("$indent}")
            }

            !handlerHasMeta && !handlerIsSuspend -> {
                appendLine(indent + "withMetaIO(meta) {")
                appendLine("$indent    $callExpr")
                appendLine("$indent}")
            }

            handlerHasMeta && !handlerIsSuspend -> {
                appendLine(indent + "withContext(Dispatchers.IO) { $callExpr }")
            }

            else -> appendLine("$indent$callExpr")
        }
    }

    private fun StringBuilder.emitToolSetRegistrations(
        toolSets: List<String>, packageName: String, indent: String
    ) {
        for (setName in toolSets) {
            emitSingleToolSetRegistration(packageName, setName, indent)
        }
    }

    private fun StringBuilder.emitSingleToolSetRegistration(packageName: String, setName: String, indent: String) {
        if (setName !in availableToolSets) return
        if (setName == DEFAULT_TOOLSET) {
            if (defaultToolSetEmitted) return
            defaultToolSetEmitted = true
        }
        val tsClassName = toolSetClassName(setName)
        appendLine("${indent}context.tool { { $packageName.$tsClassName(context) } }")
    }

    private fun StringBuilder.emitLifecycleCalls(calls: List<String>, indent: String) {
        for (call in calls) {
            appendLine("$indent$call")
        }
    }

    // ========== Utility methods ==========

    private fun resolveLifecycleCalls(
        names: List<String>,
        functions: Map<String, KSFunctionDeclaration>,
        callerName: String,
    ): List<String> = names.mapNotNull { name ->
        val func = functions[name]
        if (func == null) {
            logger.error("@OnLoad/OnUnload(\"$name\") not found, referenced by '$callerName'")
            null
        } else {
            "${func.simpleName.asString()}()"
        }
    }

    // ========== Parameter Slot Validation ==========

    /**
     * Validates function parameters against [slots] and returns the matched prefix indices.
     *
     * Rules:
     * - Function may declare FEWER parameters than slots (takes a prefix of slots).
     * - Function CANNOT declare MORE parameters than available slots.
     * - Parameters must match slot types IN ORDER (skipping/reordering = error).
     *
     * On violation, emits a compile error via [logger] and returns an empty list.
     */
    private fun KSFunctionDeclaration.matchSlots(
        slots: List<ParamSlot>,
        annotationName: String,
    ): List<Int> {
        val params = parameters.toList()

        if (params.size > slots.size) {
            val expected = slots.joinToString(", ") { "${it.name}: ${it.qualifiedType}" }
            logger.error(
                "@$annotationName function '${simpleName.asString()}' declares ${params.size} parameter(s), " +
                        "but only ${slots.size} slot(s) are available. " +
                        "Available slots (in order): $expected"
            )
            return emptyList()
        }

        for ((i, param) in params.withIndex()) {
            val slot = slots[i]
            val paramTypeFqn = param.type.resolve().declaration.qualifiedName?.asString()
            if (paramTypeFqn != slot.qualifiedType) {
                val paramName = param.name?.asString() ?: "<unnamed>"
                logger.error(
                    "@$annotationName function '${simpleName.asString()}' parameter #${i + 1} " +
                            "('$paramName: $paramTypeFqn') does not match expected type " +
                            "'${slot.qualifiedType}' for slot '${slot.name}'. " +
                            "Expected parameter order: ${slots.joinToString(", ") { "${it.name}: ${it.qualifiedType}" }}"
                )
                return emptyList()
            }
        }

        return params.indices.toList()
    }

    /** Builds the call expression from matched slot indices. */
    private fun buildSlotCallExpr(
        funcName: String,
        matchedSlots: List<Int>,
        slots: List<ParamSlot>,
    ): String {
        val args = matchedSlots.joinToString(", ") { slots[it].valueExpression }
        return "$funcName($args)"
    }

    /** Checks whether the matched slots include the Meta slot. */
    private fun funcDeclaresMeta(matchedSlots: List<Int>, slots: List<ParamSlot>): Boolean =
        matchedSlots.any { slots[it].qualifiedType == "uesugi.spi.Meta" }

    /** Validates that a lifecycle function has zero parameters. */
    private fun KSFunctionDeclaration.validateLifecycleParams(annotationName: String) {
        val count = parameters.count()
        if (count > 0) {
            logger.error(
                "@$annotationName function '${simpleName.asString()}' must have zero parameters, " +
                        "but found $count parameter(s)."
            )
        }
    }

    /** Validates that an @OnEvent function has exactly one parameter. */
    private fun KSFunctionDeclaration.validateOnEventParams() {
        val count = parameters.count()
        if (count != 1) {
            logger.error(
                "@OnEvent function '${simpleName.asString()}' must have exactly one parameter, " +
                        "but found $count parameter(s)."
            )
        }
    }

    /** Builds a context.onEvent { ... } subscription line for an @OnEvent function. */
    private fun buildOnEventSubscription(func: KSFunctionDeclaration): String {
        val funcName = func.simpleName.asString()
        val annotation = func.findAnnotation(ANN_ON_EVENT)
        val eventType = annotation?.kClassArg("value")
        val eventTypeName = eventType?.declaration?.qualifiedName?.asString() ?: run {
            logger.error("@OnEvent function '$funcName': cannot resolve event type from annotation value")
            "Any"
        }
        return "context.onEvent { event -> if (event is $eventTypeName) { " +
            "withPluginContext(context) { $funcName(event) } } }"
    }

    /** Extracts a KClass annotation argument as KSType. */
    private fun KSAnnotation.kClassArg(name: String): KSType? =
        arguments.firstOrNull { it.name?.asString() == name }?.value as? KSType

    private fun KSFunctionDeclaration?.isSuspendFunc(): Boolean =
        this != null && Modifier.SUSPEND in modifiers

    private fun KSFunctionDeclaration.checkVisibility(annotationName: String): Boolean {
        if (Modifier.PRIVATE in modifiers) {
            logger.error(
                "@$annotationName function '${simpleName.asString()}' must not be private. " +
                        "Top-level functions annotated with @$annotationName must be public or internal."
            )
            return false
        }
        return true
    }

    private fun String.escapeForLiteral(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun String.toShortKotlinType(): String = when (this) {
        "kotlin.String" -> "String"
        "kotlin.Int" -> "Int"
        "kotlin.Long" -> "Long"
        "kotlin.Boolean" -> "Boolean"
        "kotlin.Float" -> "Float"
        "kotlin.Double" -> "Double"
        "kotlin.Byte" -> "Byte"
        "kotlin.Short" -> "Short"
        "kotlin.Char" -> "Char"
        "kotlin.Unit" -> "Unit"
        "kotlin.collections.List" -> "List"
        "kotlin.collections.Map" -> "Map"
        "kotlin.collections.Set" -> "Set"
        else -> this
    }

    private fun toolSetClassName(name: String): String =
        "${TOOLSET_CLASS_PREFIX}${name.replace("-", "_")}"

    // ========== KSP Annotation helpers ==========

    private fun KSAnnotated.findAnnotation(fqn: String): KSAnnotation? =
        annotations.find { it.annotationType.resolve().declaration.qualifiedName?.asString() == fqn }

    private fun KSAnnotation.stringArg(name: String): String =
        arguments.firstOrNull { it.name?.asString() == name }?.value as? String ?: ""

    private fun KSAnnotation.stringArrayArg(name: String): List<String> {
        val arg = arguments.firstOrNull { it.name?.asString() == name } ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return (arg.value as? List<String>) ?: emptyList()
    }
}
