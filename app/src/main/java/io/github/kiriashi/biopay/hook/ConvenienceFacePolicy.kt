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

/** Bitmask values and conversion match the verified FaceBiometricFix APK. */
internal object ConvenienceFacePolicy {
    const val CONVENIENCE = 0x0fff
    const val STRONG = 0x000f
    const val PROPERTY_STRONG = 2

    fun currentStrength(original: Int): Int =
        if (original >= CONVENIENCE) STRONG else original
}
