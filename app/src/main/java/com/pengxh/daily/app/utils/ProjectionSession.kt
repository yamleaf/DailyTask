package com.pengxh.daily.app.utils

import android.media.projection.MediaProjection
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * 截屏功能状态管理（MediaProjection 方案）
 *
 * 状态流转：
 *   IDLE → [用户授权] → ACTIVE → [系统回收] → NEED_AUTH → [重新授权] → ACTIVE
 *                                    ↓ [用户关闭]
 *                                  IDLE
 */
object ProjectionSession {
    private const val kTag = "ProjectionSession"

    enum class State { IDLE, ACTIVE, NEED_AUTH }

    private val projectionRef = AtomicReference<MediaProjection?>(null)

    @Volatile
    private var state = State.IDLE

    fun isStateActive(): Boolean = state == State.ACTIVE

    fun getState(): State = state

    fun setProjection(projection: MediaProjection) {
        synchronized(this) {
            projectionRef.getAndSet(projection)?.let {
                try {
                    it.stop()
                } catch (e: Throwable) {
                    Log.w(kTag, "stop old projection failed", e)
                }
            }
            state = State.ACTIVE
        }
    }

    fun getProjection(): MediaProjection? = projectionRef.get()

    fun markStoppedNeedAuth() {
        synchronized(this) {
            state = State.NEED_AUTH
            projectionRef.getAndSet(null)
        }
    }

    fun clear() {
        synchronized(this) {
            projectionRef.getAndSet(null)?.let {
                try {
                    it.stop()
                } catch (_: Throwable) {
                    // ignore
                }
            }
            state = State.IDLE
        }
    }
}
