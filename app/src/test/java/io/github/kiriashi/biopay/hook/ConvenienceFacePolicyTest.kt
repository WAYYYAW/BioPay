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

import org.junit.Assert.assertEquals
import org.junit.Test

class ConvenienceFacePolicyTest {
    @Test
    fun convenienceSensorIsReportedAsStrong() {
        assertEquals(0x000f, ConvenienceFacePolicy.currentStrength(0x0fff))
    }

    @Test
    fun preservesExistingStrongAndWeakSensorMasks() {
        for (strength in listOf(0x0001, 0x000f, 0x00ff, 0x0ffe)) {
            assertEquals(strength, ConvenienceFacePolicy.currentStrength(strength))
        }
    }

    @Test
    fun includesHigherMasksAsTheWorkingModuleDoes() {
        for (strength in listOf(0x1000, 0x7fff, Int.MAX_VALUE)) {
            assertEquals(0x000f, ConvenienceFacePolicy.currentStrength(strength))
        }
    }

    @Test
    fun leavesNegativeOrEmptyResultsUnchanged() {
        assertEquals(-1, ConvenienceFacePolicy.currentStrength(-1))
        assertEquals(0, ConvenienceFacePolicy.currentStrength(0))
    }
}
