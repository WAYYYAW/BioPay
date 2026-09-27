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
    class Sensor {
        fun getCurrentStrength(): Int = 4095
    }

    class Utils {
        companion object {
            @JvmStatic fun isAtLeastStrength(sensor: Int, requested: Int): Boolean = sensor <= requested
            @JvmStatic fun authenticatorStrengthToPropertyStrength(strength: Int): Int = strength
        }
    }

    private class Framework {
        val hooks = mutableMapOf<String, XposedInterface.Hooker>()
        val logs = mutableListOf<String>()
        var installationCount = 0
        val xposed = proxy<XposedInterface> { _, method, args ->
            when (method.name) {
                "hook" -> {
                    installationCount++
                    var id = ""
                    proxy<XposedInterface.HookBuilder> { builder, operation, arguments ->
                        when (operation.name) {
                            "setId" -> { id = arguments!![0] as String; builder }
                            "intercept" -> {
                                hooks[id] = arguments!![0] as XposedInterface.Hooker
                                proxy<XposedInterface.HookHandle> { _, action, _ ->
                                    if (action.name == "unhook") { hooks.remove(id); null }
                                    else throw UnsupportedOperationException(action.name)
                                }
                            }
                            else -> throw UnsupportedOperationException(operation.name)
                        }
                    }
                }
                "log" -> { logs.add(args!![2] as String); null }
                "deoptimize" -> true
                else -> throw UnsupportedOperationException(method.name)
            }
        }
    }

    private class ServerLoader(val classes: MutableMap<String, Class<*>>) : ClassLoader() {
        override fun loadClass(name: String): Class<*> =
            classes[name] ?: throw ClassNotFoundException(name)
    }

    private fun serverClasses() = mutableMapOf<String, Class<*>>(
        "com.android.server.biometrics.BiometricSensor" to Sensor::class.java,
        "com.android.server.biometrics.Utils" to Utils::class.java
    )

    @Test
    fun installsAllThreeHooksWithoutPreAuthInfoOrSensorFields() {
        val framework = Framework()
        ConvenienceFaceHook().register(ServerLoader(serverClasses()), framework.xposed)
        assertEquals(3, framework.hooks.size)
        assertTrue(framework.logs.any { it.contains("all 3 compatibility hooks installed") })
        var originalCalls = 0
        val originalResult = proxy<XposedInterface.Chain> { _, method, _ ->
            if (method.name == "proceed") { originalCalls++; 4095 }
            else throw UnsupportedOperationException(method.name)
        }
        assertEquals(15, framework.hooks.getValue("bp_face_current_strength").intercept(originalResult))
        assertEquals(1, originalCalls)
        val noOriginal = proxy<XposedInterface.Chain> { _, method, _ ->
            throw AssertionError("replacement must not invoke ${method.name}")
        }
        assertEquals(true, framework.hooks.getValue("bp_face_strength_comparison").intercept(noOriginal))
        assertEquals(2, framework.hooks.getValue("bp_face_property_strength").intercept(noOriginal))
    }

    @Test
    fun packageCallbackRetriesOnlyMissingHooks() {
        val framework = Framework()
        val classes = serverClasses().also { it.remove("com.android.server.biometrics.Utils") }
        val loader = ServerLoader(classes)
        val compatibility = ConvenienceFaceHook()
        compatibility.register(loader, framework.xposed)
        assertEquals(setOf("bp_face_current_strength"), framework.hooks.keys)
        assertTrue(framework.logs.any { it.contains("only 1/3") })
        classes["com.android.server.biometrics.Utils"] = Utils::class.java
        compatibility.register(loader, framework.xposed)
        compatibility.register(loader, framework.xposed)
        assertEquals(3, framework.hooks.size)
        assertEquals(3, framework.installationCount)
    }

    @Test
    fun currentStrengthHookPreservesOriginalFailures() {
        val framework = Framework()
        ConvenienceFaceHook().register(ServerLoader(serverClasses()), framework.xposed)
        val failure = IllegalStateException("sensor unavailable")
        val chain = proxy<XposedInterface.Chain> { _, _, _ -> throw failure }
        try {
            framework.hooks.getValue("bp_face_current_strength").intercept(chain)
            throw AssertionError("expected original failure")
        } catch (e: IllegalStateException) {
            assertTrue(e === failure)
        }
    }

    companion object {
        private inline fun <reified T> proxy(
            crossinline handler: (Any, Method, Array<out Any?>?) -> Any?
        ): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
                target, method, args -> handler(target, method, args)
        } as T
    }
}
