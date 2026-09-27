/*
 * BioPay - biometric payment assistance for WeChat Tenpay keyboard.
 *
 * Copyright (C) 2026 kiriashi
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.kiriashi.biopay.entry

import io.github.kiriashi.biopay.core.log.LOG_TAG
import io.github.kiriashi.biopay.core.log.LogCapture
import io.github.kiriashi.biopay.hook.HookManager
import io.github.kiriashi.biopay.hook.ConvenienceFaceHook
import io.github.kiriashi.biopay.payment.BiometricPaymentController
import android.app.Application
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class BioPayModule : XposedModule() {

    private val wiring by lazy { AppWiring() }
    private val convenienceFaceHook = ConvenienceFaceHook()
    private val initLock = Any()
    @Volatile private var initializedApplication: Application? = null
    private var systemServer = false
    private var lifecycleCallbacks: AppLifecycleCallbacks? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        systemServer = param.isSystemServer
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        systemServer = true
        log(Log.INFO, LOG_TAG, "Class 1 face: system_server starting")
        convenienceFaceHook.register(param.classLoader, this)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName == "system" && systemServer) {
            // Retry missing hooks when the framework delivers the system package callback.
            convenienceFaceHook.register(param.defaultClassLoader, this)
            return
        }
        if (param.packageName != "com.tencent.mm") {
            return
        }

        if (!param.isFirstPackage) {
            return
        }

        val processName = Application.getProcessName()
        Log.d(LOG_TAG, "main package loaded, isFirstPkg=${param.isFirstPackage}, process=$processName")

        hookApplicationOnCreate()
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        if (systemServer) return false
        initializedApplication?.let { app ->
            lifecycleCallbacks?.let(app::unregisterActivityLifecycleCallbacks)
            LogCapture.stop(app) { }
        }
        lifecycleCallbacks = null
        BiometricPaymentController.reset()
        wiring.destroy()
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        if (systemServer) return
        val app = try {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Application
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "hot reload: failed to get Application via ActivityThread", e)
            null
        }

        if (app != null) {
            wiring.destroy()
            val state = wiring.init(app)
            HookManager.replaceHooksFromOldGeneration(param.oldHookHandles, this, state)
            BiometricPaymentController.reset()
            initializedApplication = app
            lifecycleCallbacks = AppLifecycleCallbacks(state).also(app::registerActivityLifecycleCallbacks)
            if (state.prefs.isLogCaptureEnabled()) LogCapture.start(app)
            Log.d(LOG_TAG, "hot reload: hooks replaced successfully")
        } else {
            Log.w(LOG_TAG, "hot reload: no Application available, hooks not replaced")
        }
    }

    private fun hookApplicationOnCreate() {
        try {
            val module = this@BioPayModule
            val method = Application::class.java.getDeclaredMethod("onCreate")
            hook(method).setId("bp_app_oncreate").intercept { chain ->
                try {
                    val application = chain.thisObject as? Application
                    if (application != null) {
                        val processName = Application.getProcessName()
                        Log.d(LOG_TAG, "Application.onCreate, process=$processName")
                        if (processName == "com.tencent.mm") {
                            val shouldInitialize = synchronized(initLock) {
                                if (initializedApplication === application) {
                                    false
                                } else {
                                    initializedApplication = application
                                    true
                                }
                            }
                            if (shouldInitialize) {
                                val state = wiring.init(application)
                                HookManager.init(application.classLoader, module, state)
                                lifecycleCallbacks = AppLifecycleCallbacks(state)
                                    .also(application::registerActivityLifecycleCallbacks)
                                if (state.prefs.isLogCaptureEnabled()) {
                                    LogCapture.start(application)
                                }
                            }
                        } else {
                            Log.d(LOG_TAG, "skipping non-main process: $processName")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(LOG_TAG, "init failed", e)
                }
                chain.proceed()
            }
            Log.d(LOG_TAG, "hookApplicationOnCreate registered")
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "hook onCreate failed", e)
        }
    }
}
