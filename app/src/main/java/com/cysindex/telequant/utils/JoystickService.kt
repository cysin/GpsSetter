package com.cysindex.telequant.utils

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.cysindex.telequant.R
import com.cysindex.telequant.spoof.JitterEngine
import io.github.controlwear.virtual.joystick.android.JoystickView
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Floating joystick that walks the anchor point around.
 *
 * The previous implementation had three defects that made it behave oddly:
 *
 *  - the axes were swapped. The library reports 0° as east, so cos(angle) is
 *    the east component — but it was added to *latitude*, meaning pushing right
 *    moved you north.
 *  - `strength / 30` was integer division on an Int. Anything under 30% did
 *    nothing at all, and above that the speed jumped in three coarse steps.
 *  - a fixed offset in degrees was applied to longitude with no cos(latitude)
 *    correction, so the same push covered a very different distance depending
 *    on how far from the equator you were.
 *
 * Movement is now expressed in metres per second and converted through the same
 * helper the jitter engine uses, so a push covers the same ground in every
 * direction and at every latitude. The joystick moves the anchor; jitter is
 * layered on top of it by the hook side.
 */
class JoystickService : Service() {

    private var windowManager: WindowManager? = null
    private var containerView: View? = null
    private var readout: TextView? = null

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        containerView = inflater.inflate(R.layout.joystick_layout, null as ViewGroup?)
        readout = containerView?.findViewById(R.id.joystick_readout)

        val joystick: JoystickView? = containerView?.findViewById(R.id.joystickView_right)
        joystick?.setOnMoveListener({ angle, strength ->
            if (strength > 0) step(angle, strength)
        }, TICK_MS)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.START or Gravity.BOTTOM }

        windowManager?.addView(containerView, params)
        updateReadout()
    }

    /**
     * @param angle degrees, 0 = east, counter-clockwise (the library's convention)
     * @param strength 0..100
     */
    private fun step(angle: Int, strength: Int) {
        val metresPerSecond = PrefManager.joystickSpeed?.toDoubleOrNull() ?: DEFAULT_SPEED_MPS
        val distance = metresPerSecond * (strength / 100.0) * (TICK_MS / 1000.0)
        val radians = Math.toRadians(angle.toDouble())

        val lat = PrefManager.getLat
        val lng = PrefManager.getLng
        val (newLat, newLng) = JitterEngine.offset(
            lat, lng,
            dEast = cos(radians) * distance,
            dNorth = sin(radians) * distance
        )

        PrefManager.update(start = PrefManager.isStarted, la = newLat, ln = newLng)
        updateReadout()
    }

    private fun updateReadout() {
        val speed = PrefManager.joystickSpeed?.toDoubleOrNull() ?: DEFAULT_SPEED_MPS
        readout?.text = getString(
            R.string.joystick_readout,
            PrefManager.getLat, PrefManager.getLng, speed.roundToInt()
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        containerView?.let { windowManager?.removeView(it) }
        containerView = null
        readout = null
    }

    private companion object {
        /** How often the listener fires while the stick is held. */
        const val TICK_MS = 100
        const val DEFAULT_SPEED_MPS = 8.0
    }
}
