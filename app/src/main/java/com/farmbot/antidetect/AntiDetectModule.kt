package com.farmbot.antidetect

import android.util.Log
import kotlin.random.Random

object AntiDetectModule {

    private const val TAG = "AntiDetect"

    private const val MIN_DELAY_MS = 150L
    private const val MAX_DELAY_MS = 500L
    private const val MIN_CLICK_DURATION = 30L
    private const val MAX_CLICK_DURATION = 120L
    private const val MIN_SWIPE_DURATION = 200L
    private const val MAX_SWIPE_DURATION = 500L
    private const val RHYTHM_VARIANCE = 0.3
    private const val MAX_CONSECUTIVE_ACTIONS = 5
    private const val FALSE_TRIGGER_PROBABILITY = 0.05

    data class TimingConfig(
        val baseDelayMs: Long = 300L,
        val varianceMs: Long = 150L,
        val clickDurationMs: Long = 60L,
        val swipeDurationMs: Long = 300L,
        val longPressDurationMs: Long = 500L
    )

    private var actionCount = 0
    private var lastActionTime = 0L
    private var currentRhythmFactor = 1.0

    init {
        reset()
    }

    fun reset() {
        actionCount = 0
        lastActionTime = 0L
        currentRhythmFactor = 1.0
    }

    fun getDelay(config: TimingConfig = TimingConfig()): Long {
        val randomDelay = Random.nextLong(
            config.baseDelayMs - config.varianceMs,
            config.baseDelayMs + config.varianceMs
        )
        val rhythmDelay = (randomDelay * currentRhythmFactor).toLong()
        val timeSinceLastAction = System.currentTimeMillis() - lastActionTime
        val adjustedDelay = (rhythmDelay - timeSinceLastAction).coerceAtLeast(MIN_DELAY_MS)

        return adjustedDelay.coerceAtMost(MAX_DELAY_MS)
    }

    fun getHumanClickDuration(): Long {
        return Random.nextLong(MIN_CLICK_DURATION, MAX_CLICK_DURATION)
    }

    fun getHumanSwipeDuration(): Long {
        return Random.nextLong(MIN_SWIPE_DURATION, MAX_SWIPE_DURATION)
    }

    fun getHumanLongPressDuration(): Long {
        return Random.nextLong(400L, 700L)
    }

    fun getBiasClickDuration(): Long {
        val base = Random.nextLong(MIN_CLICK_DURATION, MAX_CLICK_DURATION)
        return (base * 1.5).toLong().coerceAtMost(180L)
    }

    fun shouldSimulateFalseTrigger(): Boolean {
        return Random.nextFloat() < FALSE_TRIGGER_PROBABILITY
    }

    fun getFalseTriggerLocation(
        screenWidth: Int,
        screenHeight: Int,
        targetX: Float,
        targetY: Float
    ): Pair<Float, Float> {
        val angle = Random.nextFloat() * 2 * Math.PI.toFloat()
        val distance = Random.nextFloat() * 30f + 10f
        val offsetX = kotlin.math.cos(angle) * distance
        val offsetY = kotlin.math.sin(angle) * distance

        var falseX = targetX + offsetX
        var falseY = targetY + offsetY

        falseX = falseX.coerceIn(0f, screenWidth.toFloat())
        falseY = falseY.coerceIn(0f, screenHeight.toFloat())

        return Pair(falseX, falseY)
    }

    fun recordAction() {
        actionCount++
        lastActionTime = System.currentTimeMillis()

        if (actionCount > MAX_CONSECUTIVE_ACTIONS) {
            introduceNaturalPause()
            actionCount = 0
        }

        updateRhythm()
    }

    fun getActionCount(): Int = actionCount

    private fun updateRhythm() {
        val targetFactor = when {
            actionCount <= 2 -> 1.0
            actionCount <= 4 -> 1.0 + Random.nextFloat() * RHYTHM_VARIANCE
            else -> 1.0 + Random.nextFloat() * RHYTHM_VARIANCE * 2
        }

        currentRhythmFactor += (targetFactor - currentRhythmFactor) * 0.3
    }

    private fun introduceNaturalPause() {
        val pauseDuration = Random.nextLong(500L, 2000L)
        Log.d(TAG, "Introducing natural pause: ${pauseDuration}ms")
        Thread.sleep(pauseDuration)
        currentRhythmFactor = 1.0
    }

    fun getJitteredDuration(baseDuration: Long, jitterRatio: Float = 0.2f): Long {
        val jitter = baseDuration * jitterRatio
        return (baseDuration - jitter + Random.nextFloat() * jitter * 2).toLong()
    }

    fun getAccelerationFactor(): Float {
        return when {
            actionCount <= 1 -> 0.6f
            actionCount <= 3 -> 0.8f
            actionCount <= 6 -> 1.0f
            actionCount <= 10 -> 1.2f
            else -> 1.4f
        }
    }

    fun shouldTakeBreak(): Boolean {
        return actionCount >= MAX_CONSECUTIVE_ACTIONS * 2 &&
                Random.nextFloat() < 0.3
    }

    fun getBreakDuration(): Long {
        return Random.nextLong(2000L, 5000L)
    }
}