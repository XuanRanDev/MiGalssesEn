package dev.xuanran.miglasses.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.xuanran.miglasses.core.HostIpConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

object HostIpHook {
    private const val COROUTINE_CLASS =
        "com.superhexa.supervision.feature.miwearglasses.presentation.media.handler." +
            "DeviceMediaDataHandler\$fetchServerData\$1"
    private const val HANDLER_CLASS =
        "com.superhexa.supervision.feature.miwearglasses.presentation.media.handler.DeviceMediaDataHandler"

    fun install(module: XposedModule, context: Context, classLoader: ClassLoader) {
        val hooks = linkedSetOf<Executable>()

        val coroutineClass = Class.forName(COROUTINE_CLASS, false, classLoader)
        coroutineClass.declaredConstructors
            .filter { constructor ->
                constructor.parameterTypes.firstOrNull() == String::class.java
            }
            .forEach(hooks::add)

        // 3.3.0: l(String) creates fetchServerData; k(String, Long, Function2, Continuation)
        // is the public fetchData coroutine. Match their argument shape so both boundaries are observed.
        val handlerClass = Class.forName(HANDLER_CLASS, false, classLoader)
        handlerClass.declaredMethods
            .filter { method ->
                method.parameterTypes.firstOrNull() == String::class.java &&
                    (method.parameterCount == 1 || method.parameterCount == 4)
            }
            .forEach(hooks::add)

        check(hooks.isNotEmpty()) { "未找到 hostIP 传递方法" }
        hooks.forEach { executable ->
            module.hook(executable).intercept { chain ->
                captureAndProceed(module, context, executable, chain)
            }
        }
        module.log(
            Log.INFO,
            "MiGlassesEn",
            "已安装眼镜 hostIP Hook: ${hooks.joinToString { "${it.declaringClass.simpleName}.${it.name}" }}"
        )
    }

    private fun captureAndProceed(
        module: XposedModule,
        context: Context,
        executable: Executable,
        chain: XposedInterface.Chain
    ): Any? {
        val hostIp = (chain.args.firstOrNull() as? String)?.trim()
        if (!hostIp.isNullOrEmpty()) {
            val source = "${executable.declaringClass.name}.${executable.name}"
            runCatching {
                val intent = Intent(HostIpConfig.ACTION_CAPTURE_HOST_IP)
                    .setComponent(
                        ComponentName(HostIpConfig.MODULE_PACKAGE, HostIpConfig.RECEIVER_CLASS)
                    )
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(HostIpConfig.KEY_HOST_IP, hostIp)
                    .putExtra(HostIpConfig.KEY_SOURCE, source)
                    .putExtra(HostIpConfig.KEY_CAPTURED_AT, System.currentTimeMillis())
                context.sendBroadcast(intent)
            }.onFailure {
                module.log(Log.ERROR, "MiGlassesEn", "发送眼镜 hostIP 到模块失败", it)
            }
            module.log(
                Log.INFO,
                "MiGlassesEn",
                "捕获眼镜 hostIP=$hostIp, fileList=http://$hostIp:8080/v1/filelists, source=$source"
            )
        }
        return chain.proceed()
    }
}
