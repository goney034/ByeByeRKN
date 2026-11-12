package com.dostup.app.warp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpPlusTest {
    @Test
    fun acceptsTheKeyShapeCloudflareHandsOut() {
        assertTrue(WarpApi.isLicense("ab12CD34-ef56GH78-ij90KL12"))
    }

    @Test
    fun rejectsAnythingElse() {
        assertFalse(WarpApi.isLicense(""))
        assertFalse(WarpApi.isLicense("ab12CD34-ef56GH78"))
        assertFalse(WarpApi.isLicense("ab12CD34-ef56GH78-ij90KL1"))
        assertFalse(WarpApi.isLicense("ab12CD34 ef56GH78 ij90KL12"))
        assertFalse(WarpApi.isLicense("ab12CD34-ef56GH78-ij90KL12-"))
    }
}
