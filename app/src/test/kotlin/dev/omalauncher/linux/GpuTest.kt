package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuTest {
    /** `/sys/class/kgsl/kgsl-3d0/gpu_model` on a OnePlus 13, and the spaced form other phones print. */
    @Test fun readsAnAdrenoGeneration() {
        assertEquals(830, Gpu.adrenoGeneration("Adreno830v2"))
        assertEquals(642, Gpu.adrenoGeneration("Adreno 642L"))
        assertEquals(750, Gpu.adrenoGeneration("adreno 750"))
        assertNull(Gpu.adrenoGeneration(""))
        assertNull(Gpu.adrenoGeneration("Mali-G715"))
    }

    /** Turnip's KGSL support starts at 6xx. A missing or non-Adreno string is not an offer. */
    @Test fun offersTurnipFromAdreno6xx() {
        assertTrue(Gpu.offersAdreno("Adreno830v2"))
        assertTrue(Gpu.offersAdreno("Adreno 610"))
        assertTrue(Gpu.offersAdreno("Adreno 642L"))
        assertFalse(Gpu.offersAdreno("Adreno 540"))
        assertFalse(Gpu.offersAdreno("Adreno530"))
        assertFalse(Gpu.offersAdreno(""))
        assertFalse(Gpu.offersAdreno("kgsl"))
    }

    /**
     * Software mode must not name either GPU library. Both loader variables point at a manifest
     * that the guest never creates, so the loader does not scan its default directories.
     */
    @Test fun softwareModeNamesNoGpuDriver() {
        val env = Gpu.SOFTWARE_ENV.joinToString("\n")
        assertTrue(env.contains("LIBGL_ALWAYS_SOFTWARE=1"))
        assertTrue(env.contains("VK_ICD_FILENAMES=${Gpu.SOFTWARE_ICD}"))
        assertTrue(env.contains("VK_DRIVER_FILES=${Gpu.SOFTWARE_ICD}"))
        assertFalse(env.contains("libvulkan_freedreno"))
        assertFalse(env.contains("libvulkan_panfrost"))
        assertFalse(env.contains("/usr/share/vulkan"))
    }
}
