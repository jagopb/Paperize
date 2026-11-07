package com.anthonyla.paperize.service.wallpaper

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.presentation.MainActivity
import com.anthonyla.paperize.service.WallpaperChangeLock
import com.anthonyla.paperize.service.worker.WallpaperScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

@AndroidEntryPoint
class ScreenOffListenerService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var wallpaperController: WallpaperController
    @Inject lateinit var wallpaperChangeLock: WallpaperChangeLock
    @Inject lateinit var wallpaperScheduler: WallpaperScheduler

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiverRegistered = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "ACTION_SCREEN_OFF received")
                handleScreenOff()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerScreenOffReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        registerScreenOffReceiver()
        serviceScope.launch {
            wallpaperChangeLock.mutex.withLock {
                val mode = settingsRepository.getWallpaperMode()
                val settings = settingsRepository.getScheduleSettings()
                resolveTargetScreen(settings, mode)?.let { target ->
                    wallpaperController.preloadNext(target, settings)
                }
            }
        }
        return START_STICKY
    }

    private fun registerScreenOffReceiver() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
            Log.d(TAG, "Registered ACTION_SCREEN_OFF receiver")
        }
    }

    private fun resolveTargetScreen(settings: ScheduleSettings, mode: WallpaperMode): ScreenType? {
        if (mode != WallpaperMode.STATIC || !settings.hasRequiredAlbums(mode)) return null
        val changeHome = settings.homeEnabled && settings.homeAlbumId != null &&
                settings.homeEffects.enableChangeOnScreenOff
        val changeLock = settings.lockEnabled && settings.lockAlbumId != null &&
                settings.lockEffects.enableChangeOnScreenOff
        return when {
            changeHome && changeLock -> ScreenType.BOTH
            changeHome -> ScreenType.HOME
            changeLock -> ScreenType.LOCK
            else -> null
        }
    }

    private fun handleScreenOff() {
        val powerManager = getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Paperize::ScreenOffWallpaperLock"
        ).apply { acquire(30_000L) }

        serviceScope.launch {
            try {
                wallpaperChangeLock.mutex.withLock {
                    val mode = settingsRepository.getWallpaperMode()
                    val settings = settingsRepository.getScheduleSettings()
                    val targetScreen = resolveTargetScreen(settings, mode) ?: return@withLock

                    Log.d(TAG, "Applying preloaded static wallpaper on screen-off for $targetScreen")
                    val outcome = wallpaperController.change(targetScreen, settings)
                    if (outcome.changed) {
                        wallpaperScheduler.resetAfterManualChange(targetScreen, settings, mode)
                        wallpaperController.preloadNext(targetScreen, settings)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed changing wallpaper on screen off", e)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private fun createNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.change_on_screen_off))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenOffReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister screenOffReceiver", e)
            }
            receiverRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenOffListenerSvc"
        private const val NOTIFICATION_ID = Constants.NOTIFICATION_ID + 2

        fun sync(context: Context, shouldRun: Boolean) {
            val intent = Intent(context, ScreenOffListenerService::class.java)
            try {
                if (shouldRun) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.stopService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync ScreenOffListenerService (shouldRun=$shouldRun)", e)
            }
        }
    }
}