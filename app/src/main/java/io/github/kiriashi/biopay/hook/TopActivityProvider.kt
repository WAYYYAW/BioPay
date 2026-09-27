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

import io.github.kiriashi.biopay.core.log.LOG_TAG
import android.app.Activity
import android.util.Log

object TopActivityProvider {
    private val TAG = LOG_TAG

    @Volatile private var getTopActivityMethod: java.lang.reflect.Method? = null

    private fun findMethod(classLoader: ClassLoader): java.lang.reflect.Method =
        classLoader.loadClass(HookTargets.KindaContext)
            .getDeclaredMethod("getTopActivity").apply { isAccessible = true }

    fun resolve(classLoader: ClassLoader) {
        getTopActivityMethod = try {
            findMethod(classLoader)
        } catch (e: Throwable) {
            Log.w(TAG, "resolve getTopActivity failed", e)
            null
        }
    }

    fun resolveFromHandles(oldHandles: List<io.github.libxposed.api.XposedInterface.HookHandle>) {
        getTopActivityMethod = null
        // Several hooks share a loader; avoid repeating failed lookups for each handle.
        val triedLoaders = HashSet<ClassLoader>()
        for (handle in oldHandles) {
            val loader = handle.executable.declaringClass.classLoader ?: continue
            if (!triedLoaders.add(loader)) continue
            try {
                getTopActivityMethod = findMethod(loader)
                return
            } catch (_: ReflectiveOperationException) {}
        }
        Log.w(TAG, "getTopActivity unavailable after hook reload")
    }

    fun reset() {
        getTopActivityMethod = null
    }

    fun getTopActivity(): Activity? {
        // Snapshot the method so a concurrent reset cannot invalidate this invocation.
        val method = getTopActivityMethod ?: return null
        return try {
            method.invoke(null) as? Activity
        } catch (e: Throwable) {
            Log.w(TAG, "getTopActivity failed", e)
            null
        }
    }
}
