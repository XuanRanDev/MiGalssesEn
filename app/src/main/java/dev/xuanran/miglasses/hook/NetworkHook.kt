package dev.xuanran.miglasses.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.xuanran.miglasses.core.DexKitCache
import dev.xuanran.miglasses.core.HostIpConfig
import dev.xuanran.miglasses.core.P2PControlConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.lang.reflect.Method

@OptIn(DexKitExperimentalApi::class)
object NetworkHook {
    private const val CACHE_TAG = "mi-glasses-network"
    private const val CACHE_KEY = "okhttp-call-server-interceptor"
    private const val QUERY_VERSION = 1

    fun install(module: XposedModule, context: Context, classLoader: ClassLoader, apkPath: String) {
        DexKitCache.initialize(context, QUERY_VERSION)
        val bridge = DexKitCacheBridge.create(CACHE_TAG, apkPath)
        val descriptors = bridge.getMethods(CACHE_KEY) {
            searchPackages("okhttp3.internal.http")
            matcher {
                returnType = "okhttp3.c0"
                paramTypes = listOf("okhttp3.u\$a")
                usingStrings(" had non-zero Content-Length: ")
            }
        }
        bridge.close()
        check(descriptors.isNotEmpty()) { "DexKit 未找到混淆后的 OkHttp CallServerInterceptor" }
        val methods = descriptors.map { it.getMethodInstance(classLoader) }.distinct()
        methods.forEach { method ->
            module.hook(method).intercept { chain -> inspect(module, context, chain) }
        }
        module.log(Log.INFO, "MiGlassesEn",
            "已通过 DexKit 安装网络 Hook: ${methods.joinToString { "${it.declaringClass.name}.${it.name}" }}")
    }

    private fun inspect(module: XposedModule, context: Context, chain: XposedInterface.Chain): Any? {
        val requestText = findRequest(chain.args.firstOrNull())?.toString().orEmpty()
        val url = HTTP_URL.find(requestText)?.value.orEmpty()
        if (!url.contains(":8080/")) return chain.proceed()
        val startedAt = System.currentTimeMillis()
        return try {
            val response = chain.proceed()
            publish(module, context, "${redact(requestText)}\n${redact(response?.toString().orEmpty())} " +
                "(${System.currentTimeMillis() - startedAt} ms)")
            response
        } catch (error: Throwable) {
            publish(module, context,
                "${redact(requestText)}\nFAILED: ${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
    }

    private fun findRequest(chain: Any?): Any? {
        if (chain == null) return null
        return allNoArgMethods(chain.javaClass).asSequence()
            .filter { it.returnType.name.startsWith("okhttp3.") }
            .mapNotNull { method ->
                runCatching { method.isAccessible = true; method.invoke(chain) }.getOrNull()
            }
            .firstOrNull { HTTP_URL.containsMatchIn(it.toString()) }
    }

    private fun allNoArgMethods(type: Class<*>): List<Method> {
        val methods = LinkedHashMap<String, Method>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.filter { it.parameterCount == 0 }.forEach {
                methods.putIfAbsent("${it.name}:${it.returnType.name}", it)
            }
            current = current.superclass
        }
        return methods.values.toList()
    }

    private fun redact(raw: String): String {
        if (raw.isBlank()) return "(none)"
        return SENSITIVE_HEADER.replace(raw) {
            "${it.groupValues[1]}${it.groupValues[2]}<redacted-present>"
        }
    }

    private fun publish(module: XposedModule, context: Context, trace: String) {
        module.log(Log.INFO, "MiGlassesEn-Network", trace)
        runCatching {
            context.sendBroadcast(
                Intent(P2PControlConfig.ACTION_NETWORK_TRACE)
                    .setComponent(ComponentName(HostIpConfig.MODULE_PACKAGE, HostIpConfig.RECEIVER_CLASS))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(P2PControlConfig.EXTRA_MESSAGE, trace)
            )
        }
    }

    private val HTTP_URL = Regex("https?://[^,}\\]\\s]+")
    private val SENSITIVE_HEADER =
        Regex("(?i)(Authorization|Cookie|Set-Cookie)([=:]\\s*)([^,}\\]\\n]+)")
}
