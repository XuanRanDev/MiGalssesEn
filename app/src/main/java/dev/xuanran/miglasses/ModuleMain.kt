package dev.xuanran.miglasses

import android.app.Application
import android.content.Context
import android.util.Log
import dev.xuanran.miglasses.core.HookHost
import dev.xuanran.miglasses.hook.HostIpHook
import dev.xuanran.miglasses.hook.NetworkHook
import dev.xuanran.miglasses.hook.P2PControlHook
import dev.xuanran.miglasses.hook.SavePathHook
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class ModuleMain : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        HookHost.init(this)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (param.packageName != HOST_PACKAGE || !param.isFirstPackage) return
        HookHost.init(this)
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hook(attach).intercept { chain -> onApplicationAttach(chain, param) }
    }

    private fun onApplicationAttach(
        chain: XposedInterface.Chain,
        param: PackageLoadedParam
    ): Any? {
        val context = chain.args[0] as Context
        val result = chain.proceed()
        if (Application.getProcessName() != HOST_PACKAGE) return result
        runCatching {
            SavePathHook.install(
                module = this,
                context = context,
                classLoader = param.defaultClassLoader,
                apkPath = param.applicationInfo.sourceDir
            )
        }.onFailure { log(Log.ERROR, "MiGlassesEn", "安装保存路径 Hook 失败", it) }
        runCatching {
            HostIpHook.install(this, context, param.defaultClassLoader)
        }.onFailure { log(Log.ERROR, "MiGlassesEn", "安装 hostIP Hook 失败", it) }
        runCatching {
            NetworkHook.install(this, context, param.defaultClassLoader, param.applicationInfo.sourceDir)
        }.onFailure { log(Log.ERROR, "MiGlassesEn", "安装网络 Hook 失败", it) }
        runCatching {
            P2PControlHook.install(this, context, param.defaultClassLoader)
        }.onFailure { log(Log.ERROR, "MiGlassesEn", "安装 P2P 控制 Hook 失败", it) }
        return result
    }

    companion object { private const val HOST_PACKAGE = "com.xiaomi.superhexa" }
}
