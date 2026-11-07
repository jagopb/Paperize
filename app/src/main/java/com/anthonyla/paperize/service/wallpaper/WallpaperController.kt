package com.anthonyla.paperize.service.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import com.anthonyla.paperize.core.EmptyAlbumException
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.runCatchingCancellable
import com.anthonyla.paperize.core.util.setBitmapChecked
import com.anthonyla.paperize.domain.model.PreparedWallpaper
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.usecase.ChangeWallpaperUseCase
import com.anthonyla.paperize.domain.usecase.ReapplyEffectsUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class WallpaperChangeOutcome(val changed: Boolean = false, val emptyAlbum: Boolean = false)

/** Call while holding WallpaperChangeLock so applying and resetting schedules stay ordered. */
@Singleton
class WallpaperController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wallpaperManager: WallpaperManager,
    private val prepare: ChangeWallpaperUseCase,
    private val render: ReapplyEffectsUseCase,
    private val settingsRepository: SettingsRepository
) {
    private val preloadedWallpapers = ConcurrentHashMap<ScreenType, PreparedWallpaper>()

    /** Pre-decodes and applies effects to the next wallpaper in the background for instant screen-off switching. */
    suspend fun preloadNext(screen: ScreenType, settings: ScheduleSettings) {
        if (screen == ScreenType.LIVE) return
        when (screen) {
            ScreenType.HOME -> settings.homeAlbumId?.let { preloadForScreen(it, ScreenType.HOME) }
            ScreenType.LOCK -> settings.lockAlbumId?.let { preloadForScreen(it, ScreenType.LOCK) }
            ScreenType.BOTH -> {
                val home = settings.homeAlbumId
                if (home != null && home == settings.lockAlbumId && !settings.separateSchedules) {
                    // changeSynchronized() looks up ScreenType.BOTH!
                    preloadForScreen(home, ScreenType.BOTH)
                } else {
                    // change() looks up ScreenType.HOME and ScreenType.LOCK separately!
                    if (settings.homeEnabled && home != null) preloadForScreen(home, ScreenType.HOME)
                    if (settings.lockEnabled && settings.lockAlbumId != null) preloadForScreen(settings.lockAlbumId, ScreenType.LOCK)
                }
            }
            ScreenType.LIVE -> Unit
        }
    }

    private suspend fun preloadForScreen(albumId: String, screen: ScreenType) {
        val existing = preloadedWallpapers[screen]
        if (existing != null && existing.albumId == albumId && !existing.bitmap.isRecycled) return
        clearPreloaded(screen)
        runCatchingCancellable {
            val prepared = prepareOrDisable(albumId, screen)
            if (prepared != null) {
                preloadedWallpapers[screen] = prepared
                Log.d("WallpaperController", "Preloaded next wallpaper for $screen")
            }
        }
    }

    suspend fun clearPreloaded(screen: ScreenType? = null) {
        val keys = if (screen != null) listOf(screen) else preloadedWallpapers.keys.toList()
        for (key in keys) {
            preloadedWallpapers.remove(key)?.let { prepared ->
                withContext(NonCancellable) { prepare.restore(prepared) }
                if (!prepared.bitmap.isRecycled) prepared.bitmap.recycle()
            }
        }
    }

    private suspend fun takePreloadedOrPrepare(albumId: String, screen: ScreenType): PreparedWallpaper? {
        val cached = preloadedWallpapers.remove(screen)
        if (cached != null) {
            if (cached.albumId == albumId && !cached.bitmap.isRecycled) {
                return cached
            } else {
                withContext(NonCancellable) { prepare.restore(cached) }
                if (!cached.bitmap.isRecycled) cached.bitmap.recycle()
            }
        }
        return prepareOrDisable(albumId, screen)
    }

    suspend fun change(screen: ScreenType, settings: ScheduleSettings): WallpaperChangeOutcome = when (screen) {
        ScreenType.LIVE -> {
            context.sendBroadcast(Intent(Constants.ACTION_RELOAD_WALLPAPER).setPackage(context.packageName))
            WallpaperChangeOutcome(changed = true)
        }
        ScreenType.HOME -> changeSelected(settings.homeAlbumId, screen)
        ScreenType.LOCK -> changeSelected(settings.lockAlbumId, screen)
        ScreenType.BOTH -> {
            val home = settings.homeAlbumId
            if (home != null && home == settings.lockAlbumId && !settings.separateSchedules) {
                changeSynchronized(home, settings)
            } else {
                combine(changeSelected(home, ScreenType.HOME), changeSelected(settings.lockAlbumId, ScreenType.LOCK))
            }
        }
    }

    private suspend fun changeSelected(albumId: String?, screen: ScreenType): WallpaperChangeOutcome {
        if (albumId == null) return WallpaperChangeOutcome()
        val prepared = takePreloadedOrPrepare(albumId, screen) ?: return WallpaperChangeOutcome(emptyAlbum = true)
        applyPrepared(prepared, screen)
        return WallpaperChangeOutcome(changed = true)
    }

    private suspend fun changeSynchronized(albumId: String, settings: ScheduleSettings): WallpaperChangeOutcome {
        val prepared = takePreloadedOrPrepare(albumId, ScreenType.BOTH) ?: return WallpaperChangeOutcome(emptyAlbum = true)
        if (settings.sameStaticPresentation()) {
            applyPrepared(prepared, ScreenType.BOTH)
        } else {
            applyPrepared(prepared, ScreenType.HOME)
            val lockBitmap = render(albumId, ScreenType.LOCK, prepared.wallpaperId)
            applyBitmap(lockBitmap, ScreenType.LOCK) { prepare.complete(prepared, ScreenType.LOCK) }
        }
        return WallpaperChangeOutcome(changed = true)
    }

    private suspend fun prepareOrDisable(albumId: String, screen: ScreenType): PreparedWallpaper? {
        try {
            return prepare(albumId, if (screen == ScreenType.BOTH) ScreenType.HOME else screen)
        } catch (_: EmptyAlbumException) {
            settingsRepository.clearEmptyAlbumSelection(albumId, screen)
            return null
        }
    }

    private suspend fun applyPrepared(prepared: PreparedWallpaper, screen: ScreenType) {
        var accepted = false
        try {
            currentCoroutineContext().ensureActive()
            wallpaperManager.setBitmapChecked(prepared.bitmap, screen.flags())
            accepted = true
            withContext(NonCancellable) {
                screen.staticScreens().forEach { prepare.complete(prepared, it) }
            }
        } catch (e: Exception) {
            if (!accepted) withContext(NonCancellable) { prepare.restore(prepared) }
            throw e
        } finally {
            prepared.bitmap.recycle()
        }
    }

    suspend fun applySpecific(albumId: String, wallpaperId: String, screen: ScreenType, settings: ScheduleSettings) {
        clearPreloaded()
        val targets = if (screen == ScreenType.BOTH && !settings.sameStaticPresentation()) {
            listOf(ScreenType.HOME, ScreenType.LOCK)
        } else listOf(screen)
        for (target in targets) {
            val renderScreen = if (target == ScreenType.BOTH) ScreenType.HOME else target
            val bitmap = render(albumId, renderScreen, wallpaperId)
            applyBitmap(bitmap, target) {
                target.staticScreens().forEach {
                    prepare.completeSpecific(albumId, it, wallpaperId, settings.shuffleEnabled)
                }
            }
        }
    }

    suspend fun reapply(screen: ScreenType, settings: ScheduleSettings): WallpaperChangeOutcome {
        if (screen == ScreenType.LIVE) return WallpaperChangeOutcome()
        clearPreloaded()
        var outcome = WallpaperChangeOutcome()
        for (target in screen.staticScreens()) {
            val albumId = if (target == ScreenType.HOME) settings.homeAlbumId else settings.lockAlbumId
            if (albumId == null) continue
            val bitmap = runCatchingCancellable { render(albumId, target) }.getOrNull()
            val result = if (bitmap == null) changeSelected(albumId, target) else {
                applyBitmap(bitmap, target)
                WallpaperChangeOutcome(changed = true)
            }
            outcome = combine(outcome, result)
        }
        return outcome
    }

    private suspend fun applyBitmap(bitmap: Bitmap, screen: ScreenType, onApplied: suspend () -> Unit = {}) {
        try {
            currentCoroutineContext().ensureActive()
            wallpaperManager.setBitmapChecked(bitmap, screen.flags())
            withContext(NonCancellable) { onApplied() }
        } finally {
            bitmap.recycle()
        }
    }

    private fun combine(first: WallpaperChangeOutcome, second: WallpaperChangeOutcome) = WallpaperChangeOutcome(
        changed = first.changed || second.changed, emptyAlbum = first.emptyAlbum || second.emptyAlbum
    )

    private fun ScheduleSettings.sameStaticPresentation() =
        homeEffects == lockEffects && homeScalingType == lockScalingType && !homeScrollingEnabled

    private fun ScreenType.staticScreens() = if (this == ScreenType.BOTH) listOf(ScreenType.HOME, ScreenType.LOCK) else listOf(this)

    private fun ScreenType.flags(): Int = when (this) {
        ScreenType.HOME -> WallpaperManager.FLAG_SYSTEM
        ScreenType.LOCK -> WallpaperManager.FLAG_LOCK
        ScreenType.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        ScreenType.LIVE -> error("Live wallpaper is applied by its engine")
    }
}