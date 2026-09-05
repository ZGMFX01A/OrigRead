package me.ash.reader.ui.component.webview

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.webkit.WebView
import android.widget.OverScroller
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/** A bounded browser viewport participating in the reader's Compose pull/toolbar scroll chain. */
internal open class ReaderScrollWebView(context: Context) : WebView(context), NestedScrollingChild3 {
    private val nestedChild = NestedScrollingChildHelper(this).apply { isNestedScrollingEnabled = true }
    private val fling = OverScroller(context)
    private val configuration = ViewConfiguration.get(context)
    private val consumed = IntArray(2)
    private val windowOffset = IntArray(2)
    private val parentConsumed = IntArray(2)
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var horizontalGesture = false
    private var readerGestureCancelled = false
    private var lastFlingY = 0
    protected var readerSelectionActive = false
    var onReaderScrollChanged: (() -> Unit)? = null
    var onReaderUserDrag: (() -> Unit)? = null
    private var reportedHeight = -1
    private var metricsPosted = false

    val readerContentHeight: Int get() = computeVerticalScrollRange().coerceAtLeast(height)

    private val publishMetrics = Runnable {
        metricsPosted = false
        reportedHeight = readerContentHeight
        onReaderScrollChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Images/fonts can change document height after onPageFinished. Publish only real changes,
        // outside drawing; never measure the Android View to this document height.
        if (readerContentHeight != reportedHeight && !metricsPosted) {
            metricsPosted = true
            post(publishMetrics)
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onReaderScrollChanged?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!metricsPosted) {
            metricsPosted = true
            post(publishMetrics)
        }
    }

    fun stopReaderFling() {
        fling.abortAnimation()
        stopNestedScroll(ViewCompat.TYPE_NON_TOUCH)
    }

    fun startReaderFling(velocity: Int): Boolean {
        stopReaderFling()
        if (abs(velocity) < configuration.scaledMinimumFlingVelocity ||
            !canScrollVertically(if (velocity > 0) 1 else -1)) return false
        startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_NON_TOUCH)
        lastFlingY = scrollY
        fling.fling(0, scrollY, 0, velocity, 0, 0,
            0, (readerContentHeight - height).coerceAtLeast(0))
        postInvalidateOnAnimation()
        return true
    }

    fun scrollReaderToTop() {
        stopReaderFling()
        startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_NON_TOUCH)
        lastFlingY = scrollY
        fling.startScroll(0, scrollY, 0, -scrollY, 300)
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // After cancelling a reader drag for a second finger, Chromium has already received
        // CANCEL. Swallow the remainder rather than handing it a different pointer mid-gesture.
        if (readerGestureCancelled && event.actionMasked != MotionEvent.ACTION_DOWN) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopReaderFling()
                readerGestureCancelled = false
                dragging = false
                horizontalGesture = false
                downX = event.x
                downY = event.y
                lastY = event.y
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                horizontalGesture = true
                if (dragging) {
                    dispatchNestedPreFling(0f, 0f)
                    stopNestedScroll(ViewCompat.TYPE_TOUCH)
                    dragging = false
                    readerGestureCancelled = true
                    velocityTracker?.recycle()
                    velocityTracker = null
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                // Selection handles, long presses and horizontal HTML controls stay with Chromium.
                if (!dragging && !readerSelectionActive && event.pointerCount == 1 && !horizontalGesture) {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (dx > configuration.scaledTouchSlop && dx > dy) horizontalGesture = true
                    if (dy > configuration.scaledTouchSlop && dy > dx) {
                        dragging = true
                        MotionEvent.obtain(event).also {
                            it.action = MotionEvent.ACTION_CANCEL
                            super.onTouchEvent(it)
                            it.recycle()
                        }
                        parent?.requestDisallowInterceptTouchEvent(true)
                        onReaderUserDrag?.invoke()
                    }
                }
                if (dragging) {
                    val dy = (lastY - event.y).roundToInt()
                    lastY = event.y - scrollWithParent(dy, ViewCompat.TYPE_TOUCH)
                    return true
                }
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                if (dragging) {
                    val velocity = if (event.actionMasked == MotionEvent.ACTION_UP) velocityTracker?.run {
                        addMovement(event)
                        computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                        -yVelocity
                    } ?: 0f else 0f
                    // PullToLoad releases through nested pre-fling even when no fling will run.
                    val flingVelocity = velocity.takeIf {
                        abs(it) >= configuration.scaledMinimumFlingVelocity
                    } ?: 0f
                    val consumedByParent = dispatchNestedPreFling(0f, flingVelocity)
                    if (!consumedByParent && event.actionMasked == MotionEvent.ACTION_UP) {
                        val started = startReaderFling(flingVelocity.roundToInt())
                        dispatchNestedFling(0f, flingVelocity, started)
                    }
                }
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                stopNestedScroll(ViewCompat.TYPE_TOUCH)
                if (wasDragging) return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun scrollWithParent(dy: Int, type: Int): Int {
        consumed.fill(0)
        windowOffset.fill(0)
        dispatchNestedPreScroll(0, dy, consumed, windowOffset, type)
        var offset = windowOffset[1]
        val remaining = dy - consumed[1]
        val before = scrollY
        scrollTo(scrollX, (before + remaining).coerceIn(0, (readerContentHeight - height).coerceAtLeast(0)))
        val used = scrollY - before
        windowOffset.fill(0)
        parentConsumed.fill(0)
        dispatchNestedScroll(0, used, 0, remaining - used, windowOffset, type, parentConsumed)
        offset += windowOffset[1]
        return offset
    }

    override fun computeScroll() {
        super.computeScroll()
        if (fling.computeScrollOffset()) {
            val y = fling.currY
            scrollWithParent(y - lastFlingY, ViewCompat.TYPE_NON_TOUCH)
            lastFlingY = y
            postInvalidateOnAnimation()
        } else {
            stopNestedScroll(ViewCompat.TYPE_NON_TOUCH)
        }
    }

    override fun onDetachedFromWindow() {
        stopReaderFling()
        stopNestedScroll(ViewCompat.TYPE_TOUCH)
        removeCallbacks(publishMetrics)
        metricsPosted = false
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    override fun setNestedScrollingEnabled(enabled: Boolean) { nestedChild.isNestedScrollingEnabled = enabled }
    override fun isNestedScrollingEnabled(): Boolean = nestedChild.isNestedScrollingEnabled
    override fun startNestedScroll(axes: Int): Boolean = startNestedScroll(axes, ViewCompat.TYPE_TOUCH)
    override fun startNestedScroll(axes: Int, type: Int): Boolean = nestedChild.startNestedScroll(axes, type)
    override fun stopNestedScroll() = stopNestedScroll(ViewCompat.TYPE_TOUCH)
    override fun stopNestedScroll(type: Int) = nestedChild.stopNestedScroll(type)
    override fun hasNestedScrollingParent(): Boolean = hasNestedScrollingParent(ViewCompat.TYPE_TOUCH)
    override fun hasNestedScrollingParent(type: Int): Boolean = nestedChild.hasNestedScrollingParent(type)
    override fun dispatchNestedPreScroll(dx: Int, dy: Int, consumed: IntArray?, offsetInWindow: IntArray?): Boolean =
        dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, ViewCompat.TYPE_TOUCH)
    override fun dispatchNestedPreScroll(dx: Int, dy: Int, consumed: IntArray?, offsetInWindow: IntArray?, type: Int): Boolean =
        nestedChild.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)
    override fun dispatchNestedScroll(dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, offsetInWindow: IntArray?): Boolean =
        dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, ViewCompat.TYPE_TOUCH)
    override fun dispatchNestedScroll(dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, offsetInWindow: IntArray?, type: Int): Boolean =
        nestedChild.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type)
    override fun dispatchNestedScroll(dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, offsetInWindow: IntArray?, type: Int, consumed: IntArray) =
        nestedChild.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type, consumed)
    override fun dispatchNestedFling(velocityX: Float, velocityY: Float, consumed: Boolean): Boolean =
        nestedChild.dispatchNestedFling(velocityX, velocityY, consumed)
    override fun dispatchNestedPreFling(velocityX: Float, velocityY: Float): Boolean =
        nestedChild.dispatchNestedPreFling(velocityX, velocityY)
}
