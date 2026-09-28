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
package io.github.kiriashi.biopay.hook

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvenienceFaceHookTest {
    class Sensor(val modality: Int)

    class PreAuthInfo {
        fun getStatusForBiometricAuthenticator(sensor: Sensor, packageName: String): Int = 1
    }

    class Utils {
        companion object {
            @JvmStatic fun isAtLeastStrength(sensor: Int, requested: Int): Boolean = sensor <= requested
        }
    }

    private class Framework {
        val hooks = mutableMapOf<String, XposedInterface.Hooker>()
        val xposed = proxy<XposedInterface> { _, method, _ ->
            when (method.name) {
                "hook" -> {
                    var id = ""
                    proxy<XposedInterface.HookBuilder> { builder, operation, args ->
                        when (operation.name) {
                            "setId" -> { id = args!![0] as String; builder }
                            "intercept" -> {
                                hooks[id] = args!![0] as XposedInterface.Hooker
                                proxy<XposedInterface.HookHandle> { _, _, _ -> null }
                            }
                            else -> throw UnsupportedOperationException(operation.name)
                        }
                    }
                }
                "deoptimize" -> true
                "log" -> null
                else -> throw UnsupportedOperationException(method.name)
            }
        }
    }

    private class ServerLoader(val classes: Map<String, Class<*>>) : ClassLoader() {
        override fun loadClass(name: String): Class<*> =
            classes[name] ?: throw ClassNotFoundException(name)
    }

    private fun loader(withPreAuth: Boolean = true) = ServerLoader(buildMap {
        if (withPreAuth) put("com.android.server.biometrics.PreAuthInfo", PreAuthInfo::class.java)
        put("com.android.server.biometrics.BiometricSensor", Sensor::class.java)
        put("com.android.server.biometrics.Utils", Utils::class.java)
    })

    private fun chain(args: Array<Any>, proceed: () -> Any?): XposedInterface.Chain =
        proxy { _, method, _ ->
            when (method.name) {
                "getArgs" -> args
                "proceed" -> proceed()
                else -> throw UnsupportedOperationException(method.name)
            }
        }

    @Test
    fun onlyWeChatFacePreflightCanAcceptClassTwo() {
        val framework = Framework()
        val hook = ConvenienceFaceHook()
        hook.register(loader(), framework.xposed)
        hook.register(loader(), framework.xposed)
        assertEquals(setOf("bp_face_preflight_context", "bp_face_strength_comparison"), framework.hooks.keys)
        val context = framework.hooks.getValue("bp_face_preflight_context")
        val comparison = framework.hooks.getValue("bp_face_strength_comparison")
        fun check(sensor: Sensor, pkg: String, requested: Int): Boolean =
            context.intercept(chain(arrayOf(sensor, pkg)) {
                comparison.intercept(chain(arrayOf(0x0fff, requested)) { false }) as Boolean
            }) as Boolean
        assertEquals(true, check(Sensor(8), "com.tencent.mm", 0x00ff))
        assertEquals(false, check(Sensor(8), "com.tencent.mm", 0x000f))
        assertEquals(false, check(Sensor(2), "com.tencent.mm", 0x00ff))
        assertEquals(false, check(Sensor(8), "com.example.app", 0x00ff))
        assertEquals(false, comparison.intercept(chain(arrayOf(0x0fff, 0x00ff)) { false }))
    }

    @Test
    fun contextIsRestoredAfterFailure() {
        val framework = Framework()
        ConvenienceFaceHook().register(loader(), framework.xposed)
        val context = framework.hooks.getValue("bp_face_preflight_context")
        try {
            context.intercept(chain(arrayOf(Sensor(8), "com.tencent.mm")) {
                throw IllegalStateException("preflight failed")
            })
            throw AssertionError("expected failure")
        } catch (_: IllegalStateException) {}
        val comparison = framework.hooks.getValue("bp_face_strength_comparison")
        assertEquals(false, comparison.intercept(chain(arrayOf(0x0fff, 0x00ff)) { false }))
    }

    @Test
    fun unavailableContextCannotInstallGlobalOverride() {
        val framework = Framework()
        ConvenienceFaceHook().register(loader(withPreAuth = false), framework.xposed)
        assertTrue(framework.hooks.isEmpty())
    }

    companion object {
        private inline fun <reified T> proxy(
            crossinline handler: (Any, Method, Array<out Any?>?) -> Any?
        ): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
            target, method, args -> handler(target, method, args)
        } as T
    }
}
