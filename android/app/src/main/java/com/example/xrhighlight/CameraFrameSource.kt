package com.example.xrhighlight

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.xr.runtime.math.Pose
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.tan

/**
 * Pulls frames from the world-facing camera with CameraX, tags each with the head pose at capture
 * time, JPEG-encodes it and hands it to [DetectionClient].
 *
 * Android XR exposes the world-facing camera through the regular Camera2/CameraX APIs.
 */
class CameraFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val client: DetectionClient,
    /** Current head pose in perception space, or null if tracking is not available right now. */
    private val headPoseProvider: () -> Pose?,
    private val onFrameSent: (FrameMeta) -> Unit,
    private val maxWidth: Int = 640,
    private val jpegQuality: Int = 75,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var frameCounter = 0L

    /** Field of view of the sensor before any crop, from Camera2 characteristics. */
    @Volatile private var sensorTanHalf: Pair<Float, Float>? = null

    var description: String = "camera: not started"
        private set

    fun start(onStarted: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p
            val selector = chooseCamera(p)
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor, ::analyze)
            try {
                p.unbindAll()
                val camera = p.bindToLifecycle(lifecycleOwner, selector, analysis)
                sensorTanHalf = readSensorFov(camera.cameraInfo)
                description = "camera ok, sensor tan(fov/2)=${sensorTanHalf ?: "unknown (fallback)"}"
            } catch (e: Exception) {
                Log.e(TAG, "bind failed", e)
                description = "camera bind failed: ${e.message}"
            }
            onStarted(description)
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        provider?.unbindAll()
    }

    fun shutdown() {
        stop()
        executor.shutdown()
    }

    private fun chooseCamera(p: ProcessCameraProvider): CameraSelector = when {
        p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
        else -> CameraSelector.Builder()
            .addCameraFilter { infos ->
                // Prefer anything that is not user-facing, otherwise take the first camera.
                val world = infos.filter { it.lensFacing != CameraSelector.LENS_FACING_FRONT }
                (world.ifEmpty { infos }).take(1)
            }
            .build()
    }

    @SuppressLint("UnsafeOptInUsageError")
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun readSensorFov(info: CameraInfo): Pair<Float, Float>? = try {
        val c2 = Camera2CameraInfo.from(info)
        val intr = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val preArray =
            c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        if (intr != null && intr[0] > 0f && intr[1] > 0f && preArray != null) {
            // fx, fy are in pixels of the pre-correction active array.
            Pair(preArray.width() / (2f * intr[0]), preArray.height() / (2f * intr[1]))
        } else {
            val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focal != null && focal.isNotEmpty() && size != null) {
                Pair(size.width / (2f * focal[0]), size.height / (2f * focal[0]))
            } else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read camera characteristics", e)
        null
    }

    /** Camera model for an output image of [w] x [h] that was center-cropped from the sensor. */
    private fun cameraModelFor(w: Int, h: Int, rotationDeg: Int): CameraModel {
        val (sh, sv) = sensorTanHalf ?: return CameraModel.FALLBACK
        // Account for rotation (sensor axes swap) first.
        val (th, tv) = if (rotationDeg % 180 != 0) Pair(sv, sh) else Pair(sh, sv)
        val outAspect = w.toFloat() / h
        val sensorAspect = th / tv
        return if (outAspect > sensorAspect) {
            CameraModel(th, th / outAspect) // top/bottom cropped
        } else {
            CameraModel(tv * outAspect, tv) // left/right cropped
        }
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (!client.tryAcquireSlot()) return
            val pose = headPoseProvider()
            if (pose == null) {
                client.releaseSlot()
                return
            }
            val rotation = image.imageInfo.rotationDegrees
            var bmp = image.toBitmap()
            val longSide = max(bmp.width, bmp.height)
            if (rotation != 0 || longSide > maxWidth) {
                val scale = (maxWidth.toFloat() / longSide).coerceAtMost(1f)
                val m = Matrix().apply {
                    postRotate(rotation.toFloat())
                    postScale(scale, scale)
                }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val jpeg = ByteArrayOutputStream(64 * 1024).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                out.toByteArray()
            }
            val id = frameCounter++
            onFrameSent(
                FrameMeta(
                    frameId = id,
                    headPose = pose,
                    camera = cameraModelFor(bmp.width, bmp.height, rotation),
                    sentAtMs = SystemClock.elapsedRealtime(),
                )
            )
            client.sendFrame(id, jpeg)
        } catch (e: Exception) {
            Log.w(TAG, "analyze failed", e)
            client.releaseSlot()
        } finally {
            image.close()
        }
    }

    companion object {
        private const val TAG = "CameraFrameSource"

        fun fovDegrees(tanHalf: Float): Float = Math.toDegrees(2.0 * atan(tanHalf.toDouble())).toFloat()

        fun tanHalfFromDegrees(deg: Float): Float = tan(Math.toRadians(deg / 2.0)).toFloat()
    }
}
