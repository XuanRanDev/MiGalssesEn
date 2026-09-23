package dev.xuanran.miglasses.hook

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.xuanran.miglasses.core.HostIpConfig
import dev.xuanran.miglasses.core.P2PControlConfig
import dev.xuanran.miglasses.core.SavePathConfig
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

object P2PControlHook {
    private const val HANDLER = "com.superhexa.supervision.feature.channel.presentation.newversion.business.miwear.proto.wifi.MiWearWiFiP2PConfigHandler"
    private const val OPERATOR = "com.superhexa.supervision.feature.channel.presentation.newversion.decorator.IDeviceOperator"
    @Volatile private var operator: Any? = null
    @Volatile private var installed = false

    fun install(module: XposedModule, context: Context, classLoader: ClassLoader) {
        if (installed) return
        hookOperatorSources(module, classLoader)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != P2PControlConfig.ACTION_COMMAND) return
                val token = module.getRemotePreferences(SavePathConfig.GROUP)
                    .getString(P2PControlConfig.KEY_CONTROL_TOKEN, null)
                if (token.isNullOrBlank() ||
                    token != intent.getStringExtra(P2PControlConfig.KEY_CONTROL_TOKEN)
                ) {
                    module.log(Log.WARN, "MiGlassesEn", "拒绝无效 P2P 控制命令")
                    return
                }
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        when (intent.getStringExtra(P2PControlConfig.EXTRA_COMMAND)) {
                            P2PControlConfig.COMMAND_START -> start(module, context, classLoader)
                            P2PControlConfig.COMMAND_STOP -> stop(module, context, classLoader)
                        }
                    }.onFailure { error ->
                        report(module, context, false,
                            "P2P 控制异常：${error.cause?.message ?: error.message}")
                    }
                }
            }
        }
        val registrationContext = context.applicationContext ?: context
        registrationContext.registerReceiver(
            receiver, IntentFilter(P2PControlConfig.ACTION_COMMAND), Context.RECEIVER_EXPORTED
        )
        installed = true
        module.log(Log.INFO, "MiGlassesEn", "已安装手动 P2P 控制入口")
    }

    private fun hookOperatorSources(module: XposedModule, classLoader: ClassLoader) {
        val operatorClass = Class.forName(OPERATOR, false, classLoader)
        Class.forName(HANDLER, false, classLoader).declaredMethods
            .filter { it.parameterTypes.firstOrNull() == operatorClass }
            .forEach { method ->
                module.hook(method).intercept { chain ->
                    operator = chain.args.firstOrNull()
                    chain.proceed()
                }
            }
        runCatching {
            Class.forName("u5.a", false, classLoader).declaredMethods
                .filter { operatorClass.isAssignableFrom(it.returnType) }
                .forEach { method ->
                    module.hook(method).intercept { chain ->
                        chain.proceed().also { if (it != null) operator = it }
                    }
                }
        }
    }

    private fun start(module: XposedModule, context: Context, classLoader: ClassLoader) {
        val currentOperator = operator ?: resolveOperator(classLoader)
        if (currentOperator == null) {
            report(module, context, false, "无法取得设备控制通道：请确保眼镜已连接并保持官方 App 运行")
            return
        }
        operator = currentOperator
        val clazz = Class.forName(HANDLER, false, classLoader)
        val instance = clazz.getDeclaredField("a").apply { isAccessible = true }.get(null)
            ?: error("MiWearWiFiP2PConfigHandler 单例为空")
        val startGo = clazz.getDeclaredMethod("x", Continuation::class.java)
        invokeSuspend(startGo, instance, emptyArray()) { goResult ->
            if (goResult.getOrNull() as? Boolean != true) {
                report(module, context, false, "手机创建 Wi-Fi Direct Group Owner 失败")
                return@invokeSuspend
            }
            val enable = clazz.declaredMethods.first { it.name == "o" && it.parameterCount == 3 }
            invokeSuspend(enable, instance, arrayOf(currentOperator, null)) { enableResult ->
                val result = enableResult.getOrNull()
                val code = runCatching { result?.javaClass?.getField("code")?.getInt(result) }.getOrNull()
                val ip = invokeNoArg(result, "getIpAddress")?.toString().orEmpty()
                if (code == 0 && ip.isNotBlank()) {
                    runCatching { clazz.getDeclaredMethod("n").invoke(instance) }
                    report(module, context, true, "P2P 已开启，眼镜 IP：$ip")
                } else {
                    removeGroup(clazz, instance) { }
                    report(module, context, false, "眼镜拒绝或未完成 P2P：code=$code, ip=$ip")
                }
            }
        }
    }

    private fun stop(module: XposedModule, context: Context, classLoader: ClassLoader) {
        val clazz = Class.forName(HANDLER, false, classLoader)
        val instance = clazz.getDeclaredField("a").apply { isAccessible = true }.get(null)
            ?: error("MiWearWiFiP2PConfigHandler 单例为空")
        runCatching { clazz.getDeclaredMethod("z").invoke(instance) }
        removeGroup(clazz, instance) { result ->
            val removed = result.getOrNull() as? Boolean == true
            report(module, context, removed,
                if (removed) "P2P Group 已关闭" else "未发现 Group 或关闭失败")
        }
    }

    private fun removeGroup(clazz: Class<*>, instance: Any, done: (Result<Any?>) -> Unit) {
        invokeSuspend(clazz.getDeclaredMethod("w", Continuation::class.java), instance, emptyArray(), done)
    }

    private fun resolveOperator(classLoader: ClassLoader): Any? = runCatching {
        val helperClass = Class.forName(
            "com.superhexa.lib.channel.tools.BlueDeviceDbHelper", false, classLoader
        )
        val helper = helperClass.getDeclaredField("a").apply { isAccessible = true }.get(null)
        val bondMethod = helperClass.declaredMethods.first {
            it.name == "k" && Modifier.isStatic(it.modifiers) && it.parameterCount == 4
        }.apply { isAccessible = true }
        val bond = bondMethod.invoke(null, helper, null, 1, null) ?: return@runCatching null
        val providerClass = Class.forName("u5.a", false, classLoader)
        val provider = providerClass.getDeclaredField("a").apply { isAccessible = true }.get(null)
        val operatorClass = Class.forName(OPERATOR, false, classLoader)
        providerClass.declaredMethods.first {
            operatorClass.isAssignableFrom(it.returnType) && it.parameterCount == 1
        }.apply { isAccessible = true }.invoke(provider, bond)
    }.getOrNull()

    private fun invokeSuspend(
        method: Method,
        target: Any,
        args: Array<Any?>,
        done: (Result<Any?>) -> Unit
    ) {
        var resumed = false
        val continuation = object : Continuation<Any?> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) {
                if (!resumed) { resumed = true; done(result) }
            }
        }
        runCatching {
            method.isAccessible = true
            method.invoke(target, *args, continuation)
        }.onSuccess {
            if (it !== COROUTINE_SUSPENDED && !resumed) {
                resumed = true
                done(Result.success(it))
            }
        }.onFailure {
            if (!resumed) { resumed = true; done(Result.failure(it.cause ?: it)) }
        }
    }

    private fun invokeNoArg(instance: Any?, name: String): Any? = runCatching {
        instance?.javaClass?.methods?.first {
            it.name == name && it.parameterCount == 0
        }?.invoke(instance)
    }.getOrNull()

    private fun report(module: XposedModule, context: Context, success: Boolean, message: String) {
        module.log(if (success) Log.INFO else Log.ERROR, "MiGlassesEn-P2P", message)
        context.sendBroadcast(
            Intent(P2PControlConfig.ACTION_RESULT)
                .setComponent(ComponentName(HostIpConfig.MODULE_PACKAGE, HostIpConfig.RECEIVER_CLASS))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(P2PControlConfig.EXTRA_SUCCESS, success)
                .putExtra(P2PControlConfig.EXTRA_MESSAGE, message)
        )
    }
}
