package com.example.ctrl.capture

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.example.ctrl.mcp.DisplayInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

object ScreenCaptureManager {
    private const val TAG = "ScreenCaptureManager"
    
    private val projectionRef: AtomicReference<MediaProjection?> = AtomicReference(null)
    private val imageReaderRef: AtomicReference<ImageReader?> = AtomicReference(null)
    private val displayRef: AtomicReference<VirtualDisplay?> = AtomicReference(null)
    private val callbackHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun start(context: Context, resultCode: Int, dataIntent: android.content.Intent) {
        stop()

        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, dataIntent)
        
        // Android 16+ requires registering a callback BEFORE creating VirtualDisplay
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
            }
        }, callbackHandler)
        
        projectionRef.set(projection)

        val info = DisplayInfo.fromContext(context)
        val reader = ImageReader.newInstance(info.widthPx, info.heightPx, android.graphics.PixelFormat.RGBA_8888, 2)
        imageReaderRef.set(reader)

        val display = projection.createVirtualDisplay(
            "ctrl-capture",
            info.widthPx,
            info.heightPx,
            info.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )
        displayRef.set(display)
        
        Log.d(TAG, "Virtual display created: ${info.widthPx}x${info.heightPx}")
    }

    @Synchronized
    fun stop() {
        try {
            displayRef.getAndSet(null)?.release()
        } catch (_: Exception) {
        }
        try {
            imageReaderRef.getAndSet(null)?.close()
        } catch (_: Exception) {
        }
        try {
            projectionRef.getAndSet(null)?.stop()
        } catch (_: Exception) {
        }
    }

    suspend fun capturePngBase64(timeoutMs: Long): String? {
        val reader = imageReaderRef.get() ?: return null
        projectionRef.get() ?: return null

        return withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + timeoutMs
            var image: Image? = null
            while (System.currentTimeMillis() < deadline && image == null) {
                image = runCatching { reader.acquireLatestImage() }.getOrNull()
                if (image == null) delay(20)
            }
            if (image == null) return@withContext null
            try {
                val bitmap = imageToBitmap(image)
                val png = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
                val bytes = png.toByteArray()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } finally {
                try {
                    image.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }
}
