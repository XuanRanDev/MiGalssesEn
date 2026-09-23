package dev.xuanran.miglasses.hook

import android.content.ContentValues
import android.content.Context
import android.util.Log
import dev.xuanran.miglasses.core.DexKitCache
import dev.xuanran.miglasses.core.SavePathConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi

@OptIn(DexKitExperimentalApi::class)
object SavePathHook {
    private const val CACHE_TAG = "mi-glasses-save-path"
    private const val CACHE_KEY = "content-values-builders"
    private const val QUERY_VERSION = 1

    fun install(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        apkPath: String
    ) {
        DexKitCache.initialize(context, QUERY_VERSION)
        val bridge = DexKitCacheBridge.create(CACHE_TAG, apkPath)
        val dexMethods = bridge.getMethods(CACHE_KEY) {
            searchPackages("com.superhexa.supervision")
            matcher {
                returnType = "android.content.ContentValues"
                paramTypes = listOf("java.io.File", "long")
                usingStrings("_display_name", "relative_path", "is_pending", "mime_type")
            }
        }
        bridge.close()

        check(dexMethods.isNotEmpty()) { "DexKit 未找到 MediaStore ContentValues 构造方法" }
        val methods = dexMethods.map { it.getMethodInstance(classLoader) }.distinct()
        methods.forEach { method ->
            module.hook(method).intercept { chain -> replaceRelativePath(chain) }
        }
        module.log(
            Log.INFO,
            "MiGlassesEn",
            "已通过 DexKit 安装保存路径 Hook: ${methods.joinToString { "${it.declaringClass.name}.${it.name}" }}"
        )
    }


    private fun replaceRelativePath(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()
        val values = result as? ContentValues ?: return result
        values.put("relative_path", SavePathConfig.current())
        return values
    }
}
