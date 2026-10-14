package dev.omalauncher.linux

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opt-in GPU acceleration for Linux apps. A Mesa Vulkan driver that talks to
 * Android's own GPU device gives the guest Vulkan: PanVK-kbase on Mali GPUs
 * (v10+: Pixel 7 and newer, recent MediaTek), Turnip on Adreno GPUs
 * (Snapdragon). Arch's stock Mesa runs OpenGL on top of it with zink. Frames
 * still reach us as wl_shm buffers (MESA_VK_WSI_DEBUG=sw), so the compositor
 * doesn't change.
 *
 * The drivers talk to a kernel interface that Android updates can change, so
 * one is only used after a check passes, and checked again after every system
 * or app update; until then apps render in software.
 */
class Gpu(
    context: Context,
    private val proot: Proot,
) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val assets = context.assets

    /**
     * The driver for this phone's GPU, or null when we have none for it.
     * Mali wins when both device nodes exist. Adreno is only offered for 6xx and newer:
     * Turnip does not drive older ones, and a missing model is not a guess.
     */
    private val kind = Driver.entries.firstOrNull { available(it) }

    private fun available(driver: Driver): Boolean {
        if (!File(driver.device).exists()) return false
        if (driver != Driver.ADRENO) return true
        val model = runCatching { File(ADRENO_MODEL).readText() }.getOrNull() ?: return false
        return offersAdreno(model)
    }

    /** The phone has a GPU we have a driver for. */
    val supported = kind != null

    /** The user turned it on; it may still be inactive until [recheck] passes. */
    var wanted by mutableStateOf(prefs.getBoolean(WANTED, false))
        private set

    /** Linux apps started from now on render on the GPU. */
    var active by mutableStateOf(false)
        private set

    /** The GPU the last check found, e.g. "Mali-G715 MC7". */
    var device by mutableStateOf(prefs.getString(DEVICE, null))
        private set

    var state by mutableStateOf<JobState>(JobState.Idle)
        private set

    private val driver get() = kind?.let { File(proot.rootfs, it.path.removePrefix("/")) }

    /**
     * What changes under the driver: the kernel and Android's own GPU driver come with system
     * updates. App updates can bring a new bundled driver (Adreno), so they count too.
     */
    private val build get() = Build.FINGERPRINT + " " + System.getProperty("os.version") + " " + BuildConfig.VERSION_CODE

    /**
     * Activates the GPU if it passed its check on this system build. After
     * a system update, rechecks it first (in the background: apps render in
     * software meanwhile) and stays off if the check fails.
     */
    suspend fun recheck() {
        val kind = kind ?: return
        if (!wanted || active || state is JobState.Working) return
        if (driver?.isFile != true) return turnOff()
        if (prefs.getString(CHECKED_BUILD, null) == build) {
            // The check key can stay the same across an update that only moved the manifest.
            ensureManifest(kind)
            if (File(proot.rootfs, kind.icd.removePrefix("/")).isFile) return activate()
            Log.i(TAG, "GPU manifest missing, checking again")
        } else {
            Log.i(TAG, "system or app changed since the last GPU check, checking again")
        }
        // A bundled driver is reinstalled first, in case this app update brought a new one.
        // Rewrite the manifest too: an app update may have moved it, and the probe names the new path.
        val refresh = (if (kind.bundled) bundledDriverScript(kind) + " && " else "") + icdScript(kind) + " && "
        if (run("checking", refresh + probeScript(kind))) activate()
    }

    /** Installs the driver and Mesa, then checks them; turns on only if the check passes. */
    suspend fun turnOn() {
        val kind = kind ?: return
        if (state is JobState.Working) return
        prefs.edit().putBoolean(WANTED, true).apply()
        wanted = true
        if (run("installing", installScript(kind) + " && " + probeScript(kind))) {
            activate()
        } else {
            // Nothing to come back to on the next start; the failure stays on screen.
            prefs.edit().putBoolean(WANTED, false).apply()
            wanted = false
        }
    }

    /**
     * Back to software rendering for apps started from now on. The .so stays, so turning on again
     * is quick, but the ICD manifest is removed: the Vulkan loader would keep using it, because
     * [SOFTWARE_ENV] is what hides it and a process can unset that.
     */
    fun turnOff() {
        prefs.edit().putBoolean(WANTED, false).remove(CHECKED_BUILD).apply()
        wanted = false
        active = false
        state = JobState.Idle
        proot.graphicsEnv = SOFTWARE_ENV
        val script = removeManifests()
        Thread({
            runCatching {
                val process = proot.process(script).start()
                if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) process.destroy()
            }
        }, "gpu-off").apply { isDaemon = true }.start()
    }

    /** Deletes our manifests only. A file pacman owns at the old path is left alone. */
    private fun removeManifests() =
        Driver.entries.joinToString("; ") { driver ->
            "for f in ${driver.icd} ${driver.legacyIcd}; do " +
                "if [ -f \$f ] && grep -q '${driver.path}' \$f 2>/dev/null; then rm -f \$f; fi; done"
        } + "; true"

    private fun activate() {
        active = true
        proot.graphicsEnv = kind?.env ?: SOFTWARE_ENV
    }

    /** Runs [script]; on success remembers the build it passed on and the GPU's name from vulkaninfo. */
    private suspend fun run(
        action: String,
        script: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            state = JobState.Working(action, emptyList())
            kind?.takeIf { it.bundled }?.let(::stageBundledDriver)
            val (ok, tail) = proot.runJob(script) { state = JobState.Working(action, it) }
            val name = tail.toList().firstNotNullOfOrNull { DEVICE_NAME.find(it)?.groupValues?.get(1) }
            Log.i(TAG, "$action: ${if (ok) "ok, $name" else "failed"}")
            if (ok) {
                device = name
                prefs.edit().putString(CHECKED_BUILD, build).putString(DEVICE, name).apply()
                state = JobState.Idle
            } else {
                state = JobState.Failed(tail.toList())
            }
            ok
        }

    /** Mesa (zink), the Vulkan loader and the probe tools from pacman, then the driver and its ICD file. */
    private fun installScript(kind: Driver) =
        pacmanInstall(listOf("mesa", "vulkan-icd-loader", "vulkan-tools", "mesa-utils", "xz")) + " && " +
            (if (kind.bundled) bundledDriverScript(kind) else maliDriverScript(kind)) + " && " +
            icdScript(kind)

    /** The manifest text. [icdScript] and [ensureManifest] both write this. */
    private fun icdJson(kind: Driver) =
        """{"file_format_version":"1.0.1","ICD":{"library_path":"${kind.path}","api_version":"${kind.apiVersion}"}}"""

    /** The manifest under /usr/local, and removal of our own previous file under /usr/share. */
    private fun icdScript(kind: Driver) =
        "if [ -f ${kind.legacyIcd} ] && grep -q '${kind.path}' ${kind.legacyIcd} 2>/dev/null; then rm -f ${kind.legacyIcd}; fi && " +
            "mkdir -p ${kind.icd.substringBeforeLast('/')} && " +
            "printf '%s\\n' '${icdJson(kind)}' > ${kind.icd}"

    /**
     * Writes the manifest from the host when a passed check is about to be reused and the file is
     * gone. The rootfs is this app's directory, so this does not need a guest process.
     */
    private fun ensureManifest(kind: Driver) {
        val legacy = File(proot.rootfs, kind.legacyIcd.removePrefix("/"))
        if (legacy.isFile && runCatching { legacy.readText().contains(kind.path) }.getOrDefault(false)) legacy.delete()
        val icd = File(proot.rootfs, kind.icd.removePrefix("/"))
        if (icd.isFile) return
        runCatching {
            icd.parentFile?.mkdirs()
            icd.writeText(icdJson(kind) + "\n")
        }.onFailure { Log.w(TAG, "can't write the GPU manifest", it) }
    }

    /**
     * The pinned Mali driver from its release, verified, unless it's already there.
     * /tmp/panvk.so is removed even when the download or the hash check fails.
     */
    private fun maliDriverScript(kind: Driver) =
        "{ { echo '$MALI_SHA256  ${kind.path}' | sha256sum -c - 2>/dev/null || " +
            "{ curl -fL --retry 3 -o /tmp/panvk.so $MALI_URL && " +
            "echo '$MALI_SHA256  /tmp/panvk.so' | sha256sum -c - && " +
            "install -Dm755 /tmp/panvk.so ${kind.path}; }; }; " +
            "ok=\$?; rm -f /tmp/panvk.so; [ \$ok = 0 ]; }"

    /** Copies the driver bundled in the APK to the guest's /tmp, for [bundledDriverScript] to install. */
    private fun stageBundledDriver(kind: Driver) {
        // If this fails, the install script's xz step fails and reports it.
        runCatching { assets.open(kind.asset!!).use { input -> File(proot.tmpDir, STAGED).outputStream().use { input.copyTo(it) } } }
            .onFailure { Log.w(TAG, "can't stage the bundled GPU driver", it) }
    }

    /**
     * Installs the driver [stageBundledDriver] put in /tmp, after its sha256 matches [TURNIP_SHA256].
     * The temps are removed when the decompress, the hash, or the install fails.
     */
    private fun bundledDriverScript(kind: Driver) =
        "{ xz -dc /tmp/$STAGED > /tmp/$STAGED.so && " +
            "echo '$TURNIP_SHA256  /tmp/$STAGED.so' | sha256sum -c - && " +
            "install -Dm755 /tmp/$STAGED.so ${kind.path}; " +
            "ok=\$?; rm -f /tmp/$STAGED /tmp/$STAGED.so; [ \$ok = 0 ]; }"

    /** Vulkan finds the GPU through the driver, and OpenGL runs on it through zink: offscreen, no window needed. */
    private fun probeScript(kind: Driver) =
        // Checks run while apps still render in software; that setting would win over zink.
        // One group, so a failed install before it skips it.
        "{ unset LIBGL_ALWAYS_SOFTWARE VK_ICD_FILENAMES VK_DRIVER_FILES; " + kind.env.joinToString(" ") { "export $it;" } +
            " vk=$(timeout 60 vulkaninfo --summary 2>/dev/null); echo \"\$vk\" | grep -E 'deviceName|driverID';" +
            " { echo \"\$vk\" | grep -q ${kind.driverId} || { echo 'Vulkan: the ${kind.family} driver did not load'; false; }; } &&" +
            " { timeout 60 eglinfo -B -p surfaceless 2>/dev/null | grep 'renderer: zink' || { echo 'OpenGL: zink did not start on the GPU'; false; }; }; }"

    /**
     * A GPU family and its guest Vulkan driver. Drivers live outside pacman's files, so system
     * updates never touch them. Vulkan only sees that driver, and presents through wl_shm; OpenGL
     * is zink on it. One app can still opt out with `GALLIUM_DRIVER=llvmpipe app`.
     */
    private enum class Driver(
        val family: String,
        /** Android's device node for the GPU; the driver talks to it. */
        val device: String,
        val path: String,
        val icd: String,
        /** Where an older build wrote the manifest. Removed only when the file names our library. */
        val legacyIcd: String,
        val apiVersion: String,
        /** What vulkaninfo reports when this driver loaded. */
        val driverId: String,
        /** In the APK's assets, xz-compressed, rather than downloaded. */
        val asset: String? = null,
    ) {
        /** PanVK-kbase, downloaded from its release ([MALI_URL]). */
        MALI(
            "Mali",
            "/dev/mali0",
            "/usr/local/lib64/libvulkan_panfrost.so",
            "/usr/local/share/vulkan/icd.d/panfrost_icd.aarch64.json",
            "/usr/share/vulkan/icd.d/panfrost_icd.aarch64.json",
            "1.4.363",
            "DRIVER_ID_MESA_PANVK",
        ),

        /**
         * Mesa's Turnip built for KGSL, Android's Adreno kernel driver, by scripts/build-turnip.sh
         * (Arch's own vulkan-freedreno only supports the desktop msm driver). The probe passed on an
         * Adreno 830 (Snapdragon 8 Elite); a windowed OpenGL client is still black, see ARCHITECTURE.
         * Its manifest is under /usr/local, under its own name, so it never clashes with Arch's.
         */
        ADRENO(
            "Adreno",
            "/dev/kgsl-3d0",
            "/usr/local/lib64/libvulkan_freedreno.so",
            "/usr/local/share/vulkan/icd.d/turnip_kgsl_icd.aarch64.json",
            "/usr/share/vulkan/icd.d/turnip_kgsl_icd.aarch64.json",
            "1.4.354",
            "DRIVER_ID_MESA_TURNIP",
            asset = "gpu/libvulkan_freedreno.so.xz",
        ),
        ;

        val bundled get() = asset != null

        /** Vulkan sees only this manifest. The loader prefers VK_DRIVER_FILES over a directory scan. */
        val env get() = listOf(
            "VK_ICD_FILENAMES=$icd",
            "VK_DRIVER_FILES=$icd",
            "MESA_VK_WSI_DEBUG=sw",
            "GALLIUM_DRIVER=zink",
        )
    }

    companion object {
        private const val TAG = "gpu"
        private const val WANTED = "gpu_wanted"
        private const val CHECKED_BUILD = "gpu_checked_build"
        private const val DEVICE = "gpu_device"

        /** PanVK-kbase 0.1.0 rc2, the glibc build; tested on a Pixel 9a (Mali-G715). Pinned: newer releases get tested first. */
        private const val MALI_URL =
            "https://github.com/zenithblue-oss/panvk-kbase-android/releases/download/panvk-kbase-v0.1.0-rc2/libvulkan_panfrost-glibc-aarch64.so"
        private const val MALI_SHA256 = "0b8eaf709cd3559acec72b6fcf3acc24bcdb016bdc998adfedca6ff8eb60c820"

        /**
         * Uncompressed sha256 of the Turnip blob `scripts/build-turnip.sh` writes, Mesa 26.2.4 for KGSL.
         * The xz asset is not in git; a rebuild has to update this pin.
         */
        private const val TURNIP_SHA256 = "d45fc6d2b2e54b9f0f40ca2c9177b8e2b4a0963fbdb564aec82203a37ec8f416"

        /** The bundled driver's name in the guest's /tmp while it's installed. */
        private const val STAGED = "gpu-driver.xz"

        private val DEVICE_NAME = Regex("""deviceName\s*=\s*(.+)""")

        /** `/sys/class/kgsl/kgsl-3d0/gpu_model`, for example `Adreno830v2`. World-readable. */
        private const val ADRENO_MODEL = "/sys/class/kgsl/kgsl-3d0/gpu_model"

        /**
         * The digits in an Adreno model string. `Adreno830v2` and `Adreno 642L` both match.
         * Null when the string does not name one.
         */
        internal fun adrenoGeneration(model: String): Int? =
            Regex("""Adreno\s*(\d+)""", RegexOption.IGNORE_CASE).find(model)?.groupValues?.get(1)?.toIntOrNull()

        /** Turnip's KGSL kernel support starts at Adreno 6xx. */
        internal fun offersAdreno(model: String) = adrenoGeneration(model)?.let { it >= 600 } == true

        /**
         * A manifest that is never created. Naming it here, for both loader variables, stops the
         * loader from scanning the default directories and using a driver that was turned off.
         * OpenGL stays on llvmpipe. Vulkan has no software driver in the guest, so a Vulkan-only
         * app finds no device until acceleration is on.
         */
        internal const val SOFTWARE_ICD = "/usr/local/share/vulkan/icd.d/omalauncher-off.json"

        /** Today's default: Mesa renders on the CPU (llvmpipe), and Vulkan is not given a GPU driver. */
        val SOFTWARE_ENV = listOf(
            "LIBGL_ALWAYS_SOFTWARE=1",
            "VK_ICD_FILENAMES=$SOFTWARE_ICD",
            "VK_DRIVER_FILES=$SOFTWARE_ICD",
        )
    }
}
