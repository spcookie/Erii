package uesugi.plugin

import uesugi.common.toolkit.logger

/** Owns the route-prefix namespace shared by all plugin HTTP endpoints. */
internal class PluginRoutePrefixManager {
    private val log = logger()
    private val lock = Any()
    private val claimsByPrefix = mutableMapOf<String, PrefixClaim>()
    private val prefixesByOwner = mutableMapOf<Any, String>()

    fun register(owner: Any, pluginName: String, requestedPrefix: String): String = synchronized(lock) {
        val candidate = requestedPrefix.trim()
        val validCandidate = candidate.isNotEmpty() && '/' !in candidate
        val requested = if (validCandidate) {
            candidate
        } else {
            log.warn(
                "Plugin $pluginName requested invalid route prefix '$requestedPrefix'; " +
                    "falling back to '$pluginName'"
            )
            pluginName
        }

        val existingClaim = claimsByPrefix[requested]
        val selected = if (existingClaim != null && existingClaim.owner !== owner) {
            log.warn(
                "Plugin $pluginName requested route prefix '$requested', but it is already registered by " +
                    "${existingClaim.pluginName}; falling back to '$pluginName'"
            )
            pluginName
        } else {
            requested
        }

        val selectedClaim = claimsByPrefix[selected]
        check(selectedClaim == null || selectedClaim.owner === owner) {
            "Default route prefix '$selected' for plugin $pluginName is already registered by " +
                "${selectedClaim?.pluginName}"
        }

        prefixesByOwner.remove(owner)?.let { previousPrefix ->
            claimsByPrefix.remove(previousPrefix)
        }
        claimsByPrefix[selected] = PrefixClaim(owner, pluginName)
        prefixesByOwner[owner] = selected
        selected
    }

    fun unregister(owner: Any): String? = synchronized(lock) {
        prefixesByOwner.remove(owner)?.also { prefix ->
            claimsByPrefix.remove(prefix)
        }
    }

    private data class PrefixClaim(
        val owner: Any,
        val pluginName: String,
    )
}
