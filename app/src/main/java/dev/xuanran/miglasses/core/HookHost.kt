package dev.xuanran.miglasses.core

import io.github.libxposed.api.XposedModule

object HookHost {
    @Volatile var module: XposedModule? = null
        private set

    fun init(module: XposedModule) { this.module = module }
}
