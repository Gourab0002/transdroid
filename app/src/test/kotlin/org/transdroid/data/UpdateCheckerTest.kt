/*
 * Copyright 2010-2026 Eric Kok et al.
 *
 * Transdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Transdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transdroid. If not, see <https://www.gnu.org/licenses/>.
 */
package org.transdroid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `newer patch and minor versions read as updates`() {
        assertTrue(isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(isNewerVersion("v1.2.0", "1.0.9"))
        assertTrue(isNewerVersion("v2.0", "1.9.9"))
    }

    @Test
    fun `same version with or without v prefix is not an update`() {
        assertFalse(isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(isNewerVersion("v1.0", "1.0.0"))
    }

    @Test
    fun `older tags and prereleases of the current version are not updates`() {
        assertFalse(isNewerVersion("v0.9.9", "1.0.0"))
        assertFalse(isNewerVersion("v1.0.0-alpha", "1.0.0"))
    }

    @Test
    fun `unparseable tags fall back to plain inequality`() {
        assertTrue(isNewerVersion("nightly", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0", "1.0.0"))
    }
}
