package com.pengxh.daily.app.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import com.pengxh.daily.app.R
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MaskOverlayHelper
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.ProjectionEvent
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.kt.lite.extensions.createImageFileDir
import com.pengxh.kt.lite.extensions.saveImage
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date

import com.pengxh.daily.app.extensions.format
import kotlin.coroutines.resume

class CaptureImageService : Service(), CoroutineScope by MainScope() {

    companion object {
        private val _projectionEvents = MutableSharedFlow<ProjectionEvent>(extraBufferCapacity = 2)
        val projectionEvents = _projectionEvents.asSharedFlow()

        fun emitProjectionEvent(event: ProjectionEvent) {
            _projectionEvents.tryEmit(event)
        }

        @Volatile
        private var captureScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        private val _captureResults = MutableSharedFlow<String>(extraBufferCapacity = 1)
        private val captureResults = _captureResults.asSharedFlow()

        private fun emitCaptureResult(imagePath: String) {
            _captureResults.tryEmit(imagePath)
        }

        private val _captureScreenRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /**
         * 触发截屏，返回 CompletableDeferred 供调用方 await 结果
         */
        fun requestCaptureScreen(): CompletableDeferred<String?> {
            _captureScreenRequest.tryEmit(Unit)
            val deferred = CompletableDeferred<String?>()
            captureScope.launch {
                val result = withTimeoutOrNull(5000L) {
                    captureResults.first()
                }
                deferred.complete(result ?: "")
            }
            return deferred
        }

        private fun resetCaptureScope() {
            captureScope.cancel()
            captureScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }
    }

    private val kTag = "CaptureImageService"
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, "capture_image_service_channel").apply {
            setSmallIcon(R.mipmap.ic_launcher)
            setContentText("截屏服务已就绪")
            setPriority(NotificationCompat.PRIORITY_LOW)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setSilent(true)
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            setShowWhen(true)
            setSound(null)
            setVibrate(null)
        }
    }
    private val mpr by lazy { getSystemService(MediaProjectionManager::class.java) }
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCapturingInitialized = false
    private var captureRetryCount = 0

    override fun onCreate() {
        super.onCreate()
        resetCaptureScope()
        val name = "${resources.getString(R.string.app_name)}截屏服务"
        val channel = NotificationChannel(
            "capture_image_service_channel", name, NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Channel for Capture Image Service"
        }
        notificationManager.createNotificationChannel(channel)
        val notification = notificationBuilder.build()

        createImageFileDir()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Constant.CAPTURE_IMAGE_SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(Constant.CAPTURE_IMAGE_SERVICE_NOTIFICATION_ID, notification)
        }

        launch {
            _captureScreenRequest.collect { captureScreen() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: return START_STICKY

        if (resultCode == Activity.RESULT_CANCELED) return START_STICKY

        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }

        if (data == null) {
            Log.w(kTag, "onStartCommand: intent data is null")
            emitProjectionEvent(ProjectionEvent.Failed)
            return START_STICKY
        }

        try {
            val projection = mpr.getMediaProjection(resultCode, data)
            if (projection == null) {
                Log.w(kTag, "getMediaProjection returned null")
                emitProjectionEvent(ProjectionEvent.Failed)
                return START_STICKY
            }

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.w(kTag, "MediaProjection stopped by system")
                    ProjectionSession.markStoppedNeedAuth()
                    SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
                    releaseCaptureResources()
                    isCapturingInitialized = false
                }
            }, null)

            ProjectionSession.setProjection(projection)
            Log.d(kTag, "MediaProjection created successfully")

            initializeCaptureResources(projection)

            emitProjectionEvent(ProjectionEvent.Ready)

            SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 1)
        } catch (e: Exception) {
            Log.w(kTag, "createMediaProjection failed: ${e.message}", e)
            emitProjectionEvent(ProjectionEvent.Failed)
        }

        return START_STICKY
    }

    private fun initializeCaptureResources(projection: MediaProjection) {
        if (isCapturingInitialized) {
            Log.d(kTag, "Capture resources already initialized")
            return
        }

        captureRetryCount = 0
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        try {
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            virtualDisplay = projection.createVirtualDisplay(
                "CaptureImageDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                reader.surface,
                null,
                null
            )

            isCapturingInitialized = true
            Log.d(kTag, "Capture resources initialized successfully")
        } catch (e: Exception) {
            Log.e(kTag, "Failed to initialize capture resources", e)
            releaseCaptureResources()
            isCapturingInitialized = false
        }
    }

    private fun captureScreen() {
        if (!ProjectionSession.isStateActive()) {
            MessageDispatcher.sendMessage(
                "截屏失败", "MediaProjection not active. state=${ProjectionSession.getState()}"
            )
            return
        }

        val projection = ProjectionSession.getProjection()
        if (projection == null) {
            MessageDispatcher.sendMessage("截屏失败", "MediaProjection not available")
            return
        }

        if (!isCapturingInitialized || imageReader == null || virtualDisplay == null) {
            Log.w(kTag, "Capture resources not initialized, reinitializing...")
            releaseCaptureResources()
            initializeCaptureResources(projection)
            if (!isCapturingInitialized) {
                MessageDispatcher.sendMessage("截屏失败", "截屏资源初始化失败")
                return
            }
        }

        launch {
            try {
                val reader = imageReader ?: run {
                    MessageDispatcher.sendMessage("截屏失败", "ImageReader 为空")
                    return@launch
                }

                val startTime = System.currentTimeMillis()
                Log.d(kTag, "================== 开始截屏 ==================")

                // 伪息屏蒙层在显示时，临时移除
                val maskShowing = MaskOverlayHelper.isShowing()
                if (maskShowing) {
                    MaskOverlayHelper.hideForScreenshot(this@CaptureImageService)
                    delay(400)
                }

                val image = withTimeoutOrNull(1000L) {
                    Log.d(kTag, "进入等待......")
                    waitForImageAvailable(reader)
                }

                val elapsed = System.currentTimeMillis() - startTime
                if (image == null) {
                    Log.e(kTag, "获取图像失败: acquireNextImage返回null, 总耗时: ${elapsed}ms")
                    if (maskShowing) {
                        MaskOverlayHelper.restoreAfterScreenshot(this@CaptureImageService)
                    }
                    return@launch
                }
                Log.d(kTag, "图像获取成功, 耗时: ${elapsed}ms")

                val width = image.width
                val height = image.height
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = createBitmap(width + rowPadding / pixelStride, height)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val cropped = if (rowPadding != 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, width, height)
                } else bitmap

                // 保存全屏截图（不再裁剪中间区域）
                val finalBitmap = cropped

                if (isBitmapMostlyBlack(finalBitmap)) {
                    if (captureRetryCount < 2) {
                        captureRetryCount++
                        Log.w(kTag, "检测到黑色画面，第${captureRetryCount}次重试")
                        if (maskShowing) {
                            MaskOverlayHelper.restoreAfterScreenshot(this@CaptureImageService)
                        }
                        delay(1000)
                        captureScreen()
                        return@launch
                    } else {
                        Log.w(kTag, "黑色画面重试已耗尽，使用当前图像")
                        captureRetryCount = 0
                    }
                } else {
                    captureRetryCount = 0
                }

                val imagePath = "${createImageFileDir()}/${Date().format("yyyyMMdd_HHmmss")}.png"
                finalBitmap.saveImage(imagePath)
                LogFileManager.writeLog("截屏成功: $imagePath")
                emitCaptureResult(imagePath)

                if (maskShowing) {
                    MaskOverlayHelper.restoreAfterScreenshot(this@CaptureImageService)
                }
            } catch (_: RemoteException) {
                Log.w(kTag, "RemoteException during capture")
                ProjectionSession.markStoppedNeedAuth()
                emitProjectionEvent(ProjectionEvent.Failed)
                releaseCaptureResources()
                isCapturingInitialized = false
            } catch (_: SecurityException) {
                Log.w(kTag, "SecurityException during capture")
                ProjectionSession.markStoppedNeedAuth()
                emitProjectionEvent(ProjectionEvent.Failed)
                releaseCaptureResources()
                isCapturingInitialized = false
            } catch (e: Exception) {
                Log.e(kTag, "截屏失败: ${e.message}", e)
                MessageDispatcher.sendMessage("截屏失败", "${e.message}")
            }
        }
    }

    private suspend fun waitForImageAvailable(imageReader: ImageReader): Image? {
        return suspendCancellableCoroutine { continuation ->
            var resumed = false

            val listener = ImageReader.OnImageAvailableListener { reader ->
                if (resumed) return@OnImageAvailableListener
                val image = reader.acquireLatestImage() ?: reader.acquireNextImage()
                if (image != null) {
                    reader.setOnImageAvailableListener(null, null)
                    resumed = true
                    if (continuation.isActive) {
                        continuation.resume(image)
                    } else {
                        image.close()
                    }
                }
            }

            continuation.invokeOnCancellation {
                if (!resumed) {
                    imageReader.setOnImageAvailableListener(null, null)
                }
            }

            imageReader.setOnImageAvailableListener(listener, null)

            if (!resumed) {
                val existing = imageReader.acquireLatestImage()
                if (existing != null) {
                    imageReader.setOnImageAvailableListener(null, null)
                    resumed = true
                    continuation.resume(existing)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
        captureScope.cancel()
        releaseCaptureResources()
        ProjectionSession.clear()
        SaveKeyValues.saveInt(Constant.RESULT_SOURCE_KEY, 0)
        isCapturingInitialized = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun isBitmapMostlyBlack(bitmap: Bitmap): Boolean {
        val sampleStep = 10
        val darkThreshold = 20
        var darkPixels = 0
        var totalPixels = 0
        for (y in 0 until bitmap.height step sampleStep) {
            for (x in 0 until bitmap.width step sampleStep) {
                val pixel = bitmap[x, y]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r < darkThreshold && g < darkThreshold && b < darkThreshold) {
                    darkPixels++
                }
                totalPixels++
            }
        }
        return totalPixels > 0 && darkPixels.toFloat() / totalPixels >= 0.90f
    }

    private fun releaseCaptureResources() {
        Log.d(kTag, "Releasing capture resources")
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }
}
