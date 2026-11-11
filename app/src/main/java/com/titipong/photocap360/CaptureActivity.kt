package com.titipong.photocap360

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.titipong.photocap360.SphereMath.DEG
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class CaptureActivity : ComponentActivity() {

    companion object {
        private const val TAG = "Capture"
        const val ALIGN_DEG = 3.0            // aim tolerance
        const val MAX_ROLL_DEG = 12.0        // sideways tilt tolerance
        const val STILL_RAD_S = 0.10         // ≈ 5.7°/s, max angular speed to count as "still"
        const val HOLD_MS = 450L             // must stay aimed & still this long
        const val MAX_MOVE_DURING_SHOT = 1.5 // deg; retake if moved more while shutter was open
        const val OVERLAP = 0.35
    }

    private lateinit var preview: PreviewView
    private lateinit var overlay: TargetOverlayView
    private lateinit var hint: TextView
    private lateinit var counter: TextView

    private lateinit var tracker: OrientationTracker
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    // Field of view (tan of half-angle) – sensor long/short axis, then portrait preview H/V.
    private var tanLong = SphereMath.tanHalf(65.0)
    private var tanShort = SphereMath.tanHalf(50.0)
    private var tanH = tanShort
    private var tanV = tanLong

    private var session: CaptureSession? = null
    private var targets: List<SphereMath.Target> = emptyList()
    private var targetDirs: List<SphereMath.Vec3> = emptyList()
    private val done = mutableSetOf<Int>()

    private var holdStart = 0L
    private var holdTarget = -1
    private var capturing = false
    private var exposureLocked = false
    private var cameraReadyAt = 0L
    private var lockedAt = 0L
    private var finished = false

    // Camera capabilities + the latest auto-exposure state seen on the preview stream.
    private var manualSensor = false
    private var manualPost = false
    private var manualFocus = false
    @Volatile private var lastIso: Int? = null
    @Volatile private var lastExp: Long? = null
    @Volatile private var lastFrameDur: Long? = null
    @Volatile private var lastWbGains: RggbChannelVector? = null
    @Volatile private var lastWbTransform: ColorSpaceTransform? = null
    @Volatile private var lastFocus: Float? = null

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (exposureLocked) return
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExp = it }
            result.get(CaptureResult.SENSOR_FRAME_DURATION)?.let { lastFrameDur = it }
            result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { lastWbGains = it }
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { lastWbTransform = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocus = it }
        }
    }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else { toast(R.string.need_camera); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        padForSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        preview = findViewById(R.id.preview)
        preview.scaleType = PreviewView.ScaleType.FIT_CENTER
        overlay = findViewById(R.id.overlay)
        hint = findViewById(R.id.hint)
        counter = findViewById(R.id.counter)
        findViewById<ImageButton>(R.id.close).setOnClickListener { finish() }
        findViewById<Button>(R.id.undo).setOnClickListener { undo() }
        findViewById<Button>(R.id.finish).setOnClickListener { finishCapture() }

        tracker = OrientationTracker(getSystemService(SENSOR_SERVICE) as SensorManager)
        if (!tracker.available) { toast(R.string.no_sensor); finish(); return }
        tracker.listener = { onOrientation() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() { super.onResume(); tracker.start() }
    override fun onPause() { super.onPause(); tracker.stop() }

    // ---------------- Camera ----------------

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val rot = preview.display?.rotation ?: 0
            val pvBuilder = Preview.Builder().setResolutionSelector(selector).setTargetRotation(rot)
            // Watch auto-exposure results so we can freeze them as manual settings later.
            Camera2Interop.Extender(pvBuilder).setSessionCaptureCallback(previewCallback)
            val pv = pvBuilder.build()
            pv.setSurfaceProvider(preview.surfaceProvider)
            val ic = ImageCapture.Builder()
                .setResolutionSelector(selector)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .setTargetRotation(rot)
                .build()
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, ic)
            imageCapture = ic
            cameraReadyAt = SystemClock.elapsedRealtime()
            readFov()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * FOV from Camera2 characteristics:
     *   tan(FOV/2) = (sensor size · activeArray/pixelArray) / 2 / focalLength
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun readFov() {
        try {
            val info = Camera2CameraInfo.from(camera!!.cameraInfo)
            val phys = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focals = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val pixels = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            if (phys != null && focals != null && focals.isNotEmpty()) {
                val f = focals[0].toDouble()
                var w = phys.width.toDouble(); var h = phys.height.toDouble()
                if (active != null && pixels != null && pixels.width > 0 && pixels.height > 0) {
                    w *= active.width().toDouble() / pixels.width
                    h *= active.height().toDouble() / pixels.height
                }
                tanLong = max(w, h) / 2 / f
                tanShort = minOf(w, h) / 2 / f
            }
            val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
            manualPost = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps
            val minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            manualFocus = manualSensor && minFocus > 0f
            Log.i(TAG, "manual sensor=$manualSensor post=$manualPost focus=$manualFocus")
        } catch (e: Exception) {
            Log.w(TAG, "FOV fallback", e)
        }
        val (l, s) = SphereMath.cropTan(tanLong, tanShort, 4.0 / 3.0)
        tanH = s; tanV = l
        overlay.tanH = tanH; overlay.tanV = tanV
        Log.i(TAG, "FOV portrait: %.1f° x %.1f°".format(SphereMath.fovDeg(tanH), SphereMath.fovDeg(tanV)))
    }

    /**
     * Freeze exposure, white balance and focus for the whole capture, so every frame matches.
     *
     * Preferred: read the converged auto values from the preview stream and set them as MANUAL
     * values (AE off with fixed ISO + shutter, AWB off with fixed colour gains + matrix, AF off at
     * the current distance). AE_LOCK/AWB_LOCK alone are only hints that some phones ignore or that
     * still capture overrides; manual values are applied to every request, including stills.
     * Fallback when the camera lacks the MANUAL_* capabilities: AE_LOCK + AWB_LOCK.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun lockExposure() {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()
        val iso = lastIso; val exp = lastExp
        var mode: String
        if (manualSensor && iso != null && exp != null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            lastFrameDur?.let { b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(it, exp)) }
            mode = "manual ISO $iso 1/${(1e9 / exp).roundToInt()}s"
        } else {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            mode = "AE lock"
        }
        val gains = lastWbGains; val xf = lastWbTransform
        if (manualPost && gains != null && xf != null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
            b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, xf)
            mode += ", manual WB"
        } else {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            mode += ", AWB lock"
        }
        val fd = lastFocus
        if (manualFocus && fd != null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, fd)
            mode += ", focus locked"
        }
        try {
            Camera2CameraControl.from(cam.cameraControl).captureRequestOptions = b.build()
        } catch (e: Exception) {
            Log.w(TAG, "exposure lock failed", e)
            mode = "not locked (${e.javaClass.simpleName})"
        }
        exposureLocked = true
        lockedAt = SystemClock.elapsedRealtime()
        session?.exposureMode = mode
        Log.i(TAG, "exposure: $mode")
    }

    // ---------------- Targeting state machine ----------------

    private fun ensureSession(r: FloatArray) {
        if (session != null || imageCapture == null) return
        val yaw0 = SphereMath.yawOf(SphereMath.cameraForward(r))
        val dir = File(filesDir, "sessions/${System.currentTimeMillis()}").apply { mkdirs() }
        session = CaptureSession(dir, tanLong, tanShort, yaw0)
        targets = SphereMath.buildTargets(
            SphereMath.fovDeg(tanH), SphereMath.fovDeg(tanV), OVERLAP, includeNadir = true,
        )
        session!!.targetCount = targets.size
        targetDirs = targets.map { SphereMath.dirFromYawPitch(yaw0 + it.yaw, it.pitch) }
        overlay.targets = targets
        overlay.targetDirs = targetDirs
        overlay.alignDeg = ALIGN_DEG
    }

    private fun onOrientation() {
        if (finished) return
        val r = tracker.rotation()
        ensureSession(r)
        overlay.orient = r
        if (session == null) { overlay.invalidate(); return }

        val fwd = SphereMath.cameraForward(r)
        // Closest not-yet-captured target to where the camera points.
        var best = -1; var bestAng = Double.MAX_VALUE
        for (i in targetDirs.indices) {
            if (i in done) continue
            val a = SphereMath.angleBetween(fwd, targetDirs[i])
            if (a < bestAng) { bestAng = a; best = i }
        }
        val firstPending = targets.indices.firstOrNull { it !in done } ?: -1
        val aimed = best >= 0 && bestAng < ALIGN_DEG * DEG
        overlay.next = if (aimed || (best >= 0 && bestAng < 15 * DEG)) best else firstPending

        if (!capturing) {
            val now = SystemClock.elapsedRealtime()
            val rollOk = SphereMath.rollError(r) < MAX_ROLL_DEG * DEG
            val still = tracker.angularSpeed < STILL_RAD_S
            when {
                !aimed -> {
                    holdTarget = -1; overlay.holdProgress = 0f
                    setHint(if (overlay.next >= 0 && abs(targets[overlay.next].pitch) > 80 * DEG && targets[overlay.next].pitch < 0)
                        R.string.hint_nadir else R.string.hint_aim)
                }
                !rollOk -> { holdTarget = -1; overlay.holdProgress = 0f; setHint(R.string.hint_roll) }
                !still -> { holdTarget = -1; overlay.holdProgress = 0f; setHint(R.string.hint_slow) }
                else -> {
                    if (holdTarget != best) { holdTarget = best; holdStart = now }
                    val p = (now - holdStart).toFloat() / HOLD_MS
                    overlay.holdProgress = p.coerceAtMost(1f)
                    setHint(R.string.hint_hold)
                    // Let auto-exposure settle ~1 s after the camera opens, freeze it while the user
                    // holds on the first target, and give the new settings ~0.5 s to take effect.
                    if (!exposureLocked && now - cameraReadyAt > 1000) lockExposure()
                    if (p >= 1f && exposureLocked && now - lockedAt > 500) capture(best, r)
                }
            }
        }
        counter.text = "${done.size} / ${targets.size}"
        overlay.invalidate()
    }

    private fun capture(targetIndex: Int, rAtTrigger: FloatArray) {
        val ic = imageCapture ?: return
        val s = session ?: return
        capturing = true
        setHint(R.string.hint_capturing)
        val file = File(s.dir, "frame_%02d_%d.jpg".format(targetIndex, System.currentTimeMillis()))
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        ic.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                capturing = false
                overlay.holdProgress = 0f; holdTarget = -1
                val rNow = tracker.rotation()
                val moved = SphereMath.angleBetween(
                    SphereMath.cameraForward(rAtTrigger), SphereMath.cameraForward(rNow),
                ) / DEG
                if (moved > MAX_MOVE_DURING_SHOT) {
                    file.delete()
                    setHint(R.string.hint_moved)
                    return
                }
                s.frames += Frame(file, rAtTrigger, targetIndex)
                done += targetIndex
                overlay.done = done.toSet()
                overlay.capturedFrames = s.frames.map { it.rotation }
                overlay.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                s.save()
                if (done.size == targets.size) finishCapture()
            }

            override fun onError(e: ImageCaptureException) {
                capturing = false
                Log.e(TAG, "capture failed", e)
                toast(e.message ?: "capture error")
            }
        })
    }

    private fun undo() {
        val s = session ?: return
        if (capturing || s.frames.isEmpty()) return
        val last = s.frames.removeAt(s.frames.lastIndex)
        last.file.delete()
        done -= last.targetIndex
        overlay.done = done.toSet()
        overlay.capturedFrames = s.frames.map { it.rotation }
        s.save()
        overlay.invalidate()
    }

    private fun finishCapture() {
        val s = session ?: return
        if (s.frames.size < 2 || finished) return
        finished = true
        s.save()
        setHint(R.string.hint_done)
        startActivity(Intent(this, StitchActivity::class.java).putExtra(StitchActivity.EXTRA_DIR, s.dir.absolutePath))
        finish()
    }

    private var lastHint = 0
    private fun setHint(id: Int) {
        if (id != lastHint) { lastHint = id; hint.setText(id) }
    }

    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_LONG).show()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
