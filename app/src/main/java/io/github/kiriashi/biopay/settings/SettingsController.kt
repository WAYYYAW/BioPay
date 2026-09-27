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
package io.github.kiriashi.biopay.settings

import io.github.kiriashi.biopay.core.log.LogCapture
import io.github.kiriashi.biopay.core.util.isValidActivity
import io.github.kiriashi.biopay.data.crypto.KeystoreHelper
import io.github.kiriashi.biopay.data.crypto.PasswordVersionPolicy
import io.github.kiriashi.biopay.hook.FieldStore
import io.github.kiriashi.biopay.lifecycle.AppState
import io.github.kiriashi.biopay.payment.BiometricType
import io.github.kiriashi.biopay.payment.BiometricPromptPolicy
import io.github.kiriashi.biopay.settings.ui.M3Field
import android.content.Context
import android.os.CancellationSignal
import android.hardware.biometrics.BiometricPrompt
import android.widget.Toast

object SettingsController {

    fun loadSavedPassword(pwdInput: M3Field, state: AppState) {
        val raw = state.prefs.getEncodedPassword()
        if (!raw.isNullOrEmpty()) {
            pwdInput.hint = "密码已设置"
        }
    }

    fun handleSave(context: Context, dialogHost: DialogHost, pwdInput: M3Field, state: AppState, selectedType: Int) {
        if (selectedType == BiometricType.DISABLED) {
            state.prefs.setBiometricMode(BiometricType.DISABLED)
            dismissDialog(context, dialogHost, state)
            return
        }
        val pwd = pwdInput.text.toString().trim()
        val expectedPasswordVersion = PasswordVersionPolicy.current
        if (pwd.isEmpty()) {
            if (!state.prefs.getEncodedPassword().isNullOrEmpty()) {
                if (PasswordVersionPolicy.requiresReentry(state.prefs.getEncodedPassword(), state.prefs.getPasswordVersion())) {
                    state.prefs.clearPassword()
                    showToast(context, "安全存储已升级，请重新输入支付密码")
                    return
                }
                authenticateWithBiometric(context, dialogHost, state, biometricType = selectedType) {
                    state.prefs.setBiometricMode(selectedType)
                    true
                }
                return
            }
            showToast(context, "请输入支付密码")
            return
        }
        if (pwd.length != KeystoreHelper.PASSWORD_LENGTH || pwd.any { it !in '0'..'9' }) {
            showToast(context, "密码必须是6位数字")
            return
        }
        val encryptionCipher = try {
            KeystoreHelper.createEncryptionCipher()
        } catch (e: Throwable) {
            showToast(context, "无法初始化安全存储，请重试")
            return
        }
        authenticateWithBiometric(
            context,
            dialogHost,
            state,
            biometricType = selectedType
        ) {
            if (state.prefs.savePassword(pwd, encryptionCipher, expectedPasswordVersion, selectedType).isFailure) {
                showToast(context, "密码加密失败，请重试")
                return@authenticateWithBiometric false
            }
            true
        }
    }

    fun handleClearPassword(state: AppState) {
        state.prefs.clearPassword()
    }

    fun dismissDialog(context: Context, dialogHost: DialogHost, state: AppState) {
        state.fields.removeField(context, FieldStore.SETTINGS_DIALOG)
        dialogHost.dismiss()
    }

    fun authenticateWithBiometric(
        context: Context,
        dialogHost: DialogHost,
        state: AppState,
        successMsg: String = "生物支付已启用",
        biometricType: Int = state.prefs.getBiometricType(),
        onSuccess: () -> Boolean
    ) {
        if (!context.isValidActivity()) return
        val signal = CancellationSignal()
        val attemptId = dialogHost.beginAuthentication(signal)
        try {
            val builder = BiometricPrompt.Builder(context)
                .setTitle("身份验证")
                .setNegativeButton("取消", context.mainExecutor) { _, _ ->
                    dialogHost.finishAuthentication(attemptId)
                }
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult?) {
                    if (!dialogHost.finishAuthentication(attemptId) || !context.isValidActivity()) return
                    try {
                        if (onSuccess()) {
                            showToast(context, successMsg)
                            dismissDialog(context, dialogHost, state)
                        }
                    } catch (e: Throwable) {
                        showToast(context, "保存失败，请重试")
                    }
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence?) {
                    if (!dialogHost.finishAuthentication(attemptId)) return
                    if (msg != null && context.isValidActivity()) showToast(context, msg.toString())
                }
            }
            BiometricPromptPolicy.configure(builder, biometricType).build()
                .authenticate(signal, context.mainExecutor, callback)
        } catch (e: Throwable) {
            if (dialogHost.finishAuthentication(attemptId)) showToast(context, "无法启动身份验证，请重试")
        }
    }

    fun showToast(context: Context, msg: String) {
        if (msg.isNotEmpty()) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun setLogCaptureEnabled(context: Context, state: AppState, enabled: Boolean): String {
        if (enabled) {
            state.prefs.setLogCaptureEnabled(true)
            LogCapture.start(context)
            return "日志捕获已开启"
        }
        state.prefs.setLogCaptureEnabled(false)
        LogCapture.stop(context) { path ->
            if (context.isValidActivity()) {
                showToast(context, if (path != null) "日志已保存到: $path" else "日志保存未完成")
            }
        }
        return "日志捕获已关闭"
    }
}
