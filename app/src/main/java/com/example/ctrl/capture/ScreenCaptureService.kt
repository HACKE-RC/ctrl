package com.example.ctrl.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "ctrl_capture"
        private const val NOTIFICATION_ID = 2001

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA_INTENT = "data_intent"

        fun start(context: Context, resultCode: Int, dataIntent: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA_INTENT, dataIntent)
            }
            // Use startForegroundService on Android O+ to ensure proper foreground service behavior
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        ensureChannel()
        // Enter foreground immediately with a placeholder notification
        // This is required on Android 16+ for mediaProjection services
        enterForeground("Initializing screen capture...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand startId=$startId")
        
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val dataIntent = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA_INTENT)
        }
        
        if (resultCode == 0 || dataIntent == null) {
            Log.w(TAG, "Missing resultCode or dataIntent, stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            // Acquire MediaProjection
            Log.d(TAG, "Acquiring MediaProjection...")
            ScreenCaptureManager.start(applicationContext, resultCode, dataIntent)
            Log.d(TAG, "MediaProjection acquired successfully")
            
            // Update notification to show it's running
            enterForeground("Screen capture running")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException acquiring MediaProjection", e)
            stopSelf(startId)
            return START_NOT_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Exception acquiring MediaProjection", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        ScreenCaptureManager.stop()
        super.onDestroy()
    }

    private fun enterForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "Entered foreground with: $text")
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CTRL Screen Capture",
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel ensured")
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("CTRL")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }
}
