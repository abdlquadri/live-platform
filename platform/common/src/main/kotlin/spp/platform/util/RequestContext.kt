package spp.platform.util

import spp.protocol.service.live.LiveInstrumentServiceVertxProxyHandler
import spp.protocol.service.tracing.LocalTracingServiceVertxProxyHandler
import kotlin.concurrent.getOrSet

object RequestContext {

    private val threadCtx: ThreadLocal<Map<String, String>> = ThreadLocal<Map<String, String>>()

    fun put(value: Map<String, String>) {
        threadCtx.getOrSet { mutableMapOf() }
        (threadCtx.get() as MutableMap).putAll(value)
    }

    fun put(key: String, value: String) {
        threadCtx.getOrSet { mutableMapOf() }
        (threadCtx.get() as MutableMap)[key] = value
    }

    fun get(): Map<String, String> {
        val globalCtx = mutableMapOf<String, String>()
        //local
        threadCtx.get()?.entries?.forEach { globalCtx[it.key] = it.value }
        threadCtx.remove()

        //services
        LiveInstrumentServiceVertxProxyHandler._headers.get()?.entries()?.forEach { globalCtx[it.key] = it.value }
        LiveInstrumentServiceVertxProxyHandler._headers.remove()
        LocalTracingServiceVertxProxyHandler._headers.get()?.entries()?.forEach { globalCtx[it.key] = it.value }
        LocalTracingServiceVertxProxyHandler._headers.remove()
        return globalCtx
    }
}
