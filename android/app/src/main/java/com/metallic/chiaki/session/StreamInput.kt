package com.metallic.chiaki.session

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.metallic.chiaki.common.Gamepad
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ControllerState

class StreamInput(val context: Context, val preferences: Preferences)
{
	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	/** Called on the main thread when the user asks for the in-stream menu (button combo). */
	var menuRequestedCallback: (() -> Unit)? = null

	/** Id of the last game controller that sent input, used for rumble. */
	var lastGamepadDeviceId: Int = -1
		private set

	val controllerState: ControllerState get()
	{
		val controllerState = sensorControllerState or keyControllerState or motionControllerState or pulseControllerState or touchpadControllerState

		// Touchpad alternatives (Fire OS without DualSense driver may not deliver the touchpad at all)
		controllerState.buttons = controllerState.buttons.let { b ->
			val l3r3 = ControllerState.BUTTON_L3 or ControllerState.BUTTON_R3
			when
			{
				// only the physical Create button; "Press Create" from the menu still sends Create
				Gamepad.touchpadAlt == Gamepad.TouchpadAlt.CREATE &&
						keyControllerState.buttons and ControllerState.BUTTON_SHARE != 0U &&
						pulseControllerState.buttons and ControllerState.BUTTON_SHARE == 0U ->
					(b and ControllerState.BUTTON_SHARE.inv()) or ControllerState.BUTTON_TOUCHPAD
				Gamepad.touchpadAlt == Gamepad.TouchpadAlt.L3_R3 && b and l3r3 == l3r3 ->
					(b and l3r3.inv()) or ControllerState.BUTTON_TOUCHPAD
				else -> b
			}
		}

		val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		@Suppress("DEPRECATION")
		when(windowManager.defaultDisplay.rotation)
		{
			Surface.ROTATION_90 -> {
				controllerState.accelX *= -1.0f
				controllerState.accelZ *= -1.0f
				controllerState.gyroX *= -1.0f
				controllerState.gyroZ *= -1.0f
				controllerState.orientX *= -1.0f
				controllerState.orientZ *= -1.0f
			}
			else -> {}
		}

		// prioritize motion controller's l2 and r2 over key
		// (some controllers send only key, others both but key earlier than full press)
		if(motionControllerState.l2State > 0U)
			controllerState.l2State = motionControllerState.l2State
		if(motionControllerState.r2State > 0U)
			controllerState.r2State = motionControllerState.r2State

		return controllerState or touchControllerState
	}

	private val sensorControllerState = ControllerState() // from Motion Sensors
	private val keyControllerState = ControllerState() // from KeyEvents
	private val motionControllerState = ControllerState() // from MotionEvents
	private val pulseControllerState = ControllerState() // short virtual presses from the in-stream menu
	private val touchpadControllerState = ControllerState() // DualSense touchpad through pointer capture
	private val touchpadPointerIds = HashMap<Int, UByte>() // Android pointer id -> chiaki touch id
	var touchControllerState = ControllerState()
		set(value)
		{
			field = value
			controllerStateUpdated()
		}

	private val swapCrossMoon = preferences.swapCrossMoon
	private val handler = Handler(Looper.getMainLooper())

	private val sensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					sensorControllerState.accelX = event.values[1] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelY = event.values[2] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelZ = event.values[0] / SensorManager.GRAVITY_EARTH
				}
				Sensor.TYPE_GYROSCOPE -> {
					sensorControllerState.gyroX = event.values[1]
					sensorControllerState.gyroY = event.values[2]
					sensorControllerState.gyroZ = event.values[0]
				}
				Sensor.TYPE_ROTATION_VECTOR -> {
					val q = floatArrayOf(0f, 0f, 0f, 0f)
					SensorManager.getQuaternionFromVector(q, event.values)
					sensorControllerState.orientX = q[2]
					sensorControllerState.orientY = q[3]
					sensorControllerState.orientZ = q[1]
					sensorControllerState.orientW = q[0]
				}
				else -> return
			}
			controllerStateUpdated()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	private val motionLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume()
		{
			val samplingPeriodUs = 4000
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			listOfNotNull(
				sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
				sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
			).forEach {
				sensorManager.registerListener(sensorEventListener, it, samplingPeriodUs)
			}
		}

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause()
		{
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			sensorManager.unregisterListener(sensorEventListener)
		}
	}

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		if(preferences.motionEnabled)
			lifecycleOwner.lifecycle.addObserver(motionLifecycleObserver)
	}

	private fun controllerStateUpdated()
	{
		controllerStateChangedCallback?.let { it(controllerState) }
	}

	/** Release every button and stick, e.g. before opening the in-stream menu. */
	fun releaseAll()
	{
		keyControllerState.buttons = 0U
		keyControllerState.l2State = 0U
		keyControllerState.r2State = 0U
		motionControllerState.buttons = 0U
		motionControllerState.l2State = 0U
		motionControllerState.r2State = 0U
		motionControllerState.leftX = 0
		motionControllerState.leftY = 0
		motionControllerState.rightX = 0
		motionControllerState.rightY = 0
		controllerStateUpdated()
	}

	/** Press a button for a short moment (used by the in-stream menu for PS / touchpad). */
	fun pulseButton(buttonMask: UInt, durationMs: Long = 150L)
	{
		pulseControllerState.buttons = pulseControllerState.buttons or buttonMask
		controllerStateUpdated()
		handler.postDelayed({
			pulseControllerState.buttons = pulseControllerState.buttons and buttonMask.inv()
			controllerStateUpdated()
		}, durationMs)
	}

	// --- in-stream menu combo: L1 + R1 + Share + Options -----------------------------------

	private val comboMask = ControllerState.BUTTON_L1 or ControllerState.BUTTON_R1 or
			ControllerState.BUTTON_SHARE or ControllerState.BUTTON_OPTIONS
	private var comboFiredAt = 0L

	private fun checkMenuCombo()
	{
		if(keyControllerState.buttons and comboMask != comboMask)
			return
		val now = SystemClock.uptimeMillis()
		if(now - comboFiredAt < 1000)
			return
		comboFiredAt = now
		releaseAll()
		menuRequestedCallback?.invoke()
	}

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		//Log.i("StreamSession", "key event $event")
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
			return false

		val isGamepad = Gamepad.isGamepadSource(event.source)
		if(isGamepad)
			lastGamepadDeviceId = event.deviceId

		// Fire TV remote / keyboard: Back or Menu opens the in-stream menu.
		if(!isGamepad && (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU))
		{
			if(event.action == KeyEvent.ACTION_UP)
				menuRequestedCallback?.invoke()
			return true
		}

		val keyCode = Gamepad.logicalKeyCode(event)

		when(keyCode)
		{
			KeyEvent.KEYCODE_BUTTON_L2 -> {
				keyControllerState.l2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				controllerStateUpdated()
				return true
			}
			KeyEvent.KEYCODE_BUTTON_R2 -> {
				keyControllerState.r2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				controllerStateUpdated()
				return true
			}
		}

		val buttonMask: UInt = when(keyCode)
		{
			// dpad handled by MotionEvents for gamepads, but the Fire TV remote sends keys
			KeyEvent.KEYCODE_DPAD_LEFT -> if(isGamepad) return true else ControllerState.BUTTON_DPAD_LEFT
			KeyEvent.KEYCODE_DPAD_RIGHT -> if(isGamepad) return true else ControllerState.BUTTON_DPAD_RIGHT
			KeyEvent.KEYCODE_DPAD_UP -> if(isGamepad) return true else ControllerState.BUTTON_DPAD_UP
			KeyEvent.KEYCODE_DPAD_DOWN -> if(isGamepad) return true else ControllerState.BUTTON_DPAD_DOWN
			KeyEvent.KEYCODE_DPAD_CENTER -> if(isGamepad) return true else ControllerState.BUTTON_CROSS
			KeyEvent.KEYCODE_BUTTON_A -> if(swapCrossMoon) ControllerState.BUTTON_MOON else ControllerState.BUTTON_CROSS
			KeyEvent.KEYCODE_BUTTON_B -> if(swapCrossMoon) ControllerState.BUTTON_CROSS else ControllerState.BUTTON_MOON
			KeyEvent.KEYCODE_BUTTON_X -> if(swapCrossMoon) ControllerState.BUTTON_PYRAMID else ControllerState.BUTTON_BOX
			KeyEvent.KEYCODE_BUTTON_Y -> if(swapCrossMoon) ControllerState.BUTTON_BOX else ControllerState.BUTTON_PYRAMID
			KeyEvent.KEYCODE_BUTTON_L1 -> ControllerState.BUTTON_L1
			KeyEvent.KEYCODE_BUTTON_R1 -> ControllerState.BUTTON_R1
			KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerState.BUTTON_L3
			KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerState.BUTTON_R3
			KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerState.BUTTON_SHARE
			KeyEvent.KEYCODE_BUTTON_START -> ControllerState.BUTTON_OPTIONS
			KeyEvent.KEYCODE_BUTTON_C -> ControllerState.BUTTON_PS
			KeyEvent.KEYCODE_BUTTON_MODE -> ControllerState.BUTTON_PS
			Gamepad.KEYCODE_TOUCHPAD -> ControllerState.BUTTON_TOUCHPAD
			else -> return isGamepad // swallow unknown gamepad keys so Android does not act on them
		}

		keyControllerState.buttons = keyControllerState.buttons.run {
			when(event.action)
			{
				KeyEvent.ACTION_DOWN -> this or buttonMask
				KeyEvent.ACTION_UP -> this and buttonMask.inv()
				else -> this
			}
		}

		controllerStateUpdated()
		if(event.action == KeyEvent.ACTION_DOWN)
			checkMenuCombo()
		return true
	}

	/**
	 * Events of the DualSense touchpad while the stream view holds pointer capture.
	 * Absolute touchpad events give real finger positions (swipes); any pointer device gives the click.
	 */
	fun onCapturedPointerEvent(event: MotionEvent): Boolean
	{
		var changed = false

		val clickPressed = event.buttonState and (MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0
		val wasPressed = touchpadControllerState.buttons and ControllerState.BUTTON_TOUCHPAD != 0U
		if(clickPressed != wasPressed)
		{
			touchpadControllerState.buttons = if(clickPressed) ControllerState.BUTTON_TOUCHPAD else 0U
			changed = true
		}

		if(event.isFromSource(InputDevice.SOURCE_TOUCHPAD))
		{
			val device = event.device
			val rangeX = device?.getMotionRange(MotionEvent.AXIS_X, event.source)
			val rangeY = device?.getMotionRange(MotionEvent.AXIS_Y, event.source)
			fun mapAxis(v: Float, range: InputDevice.MotionRange?, size: UShort): UShort
			{
				val span = (range?.range ?: 0f).takeIf { it > 0f } ?: return 0U
				val n = ((v - range!!.min) / span).coerceIn(0f, 1f)
				return (n * (size.toInt() - 1)).toInt().toUShort()
			}
			fun x(i: Int) = mapAxis(event.getX(i), rangeX, ControllerState.TOUCHPAD_WIDTH)
			fun y(i: Int) = mapAxis(event.getY(i), rangeY, ControllerState.TOUCHPAD_HEIGHT)

			when(event.actionMasked)
			{
				MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
					val i = event.actionIndex
					touchpadControllerState.startTouch(x(i), y(i))?.let { touchpadPointerIds[event.getPointerId(i)] = it }
					changed = true
				}
				MotionEvent.ACTION_MOVE -> {
					for(i in 0 until event.pointerCount)
					{
						val id = touchpadPointerIds[event.getPointerId(i)] ?: continue
						changed = touchpadControllerState.setTouchPos(id, x(i), y(i)) || changed
					}
				}
				MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
					touchpadPointerIds.remove(event.getPointerId(event.actionIndex))?.let { touchpadControllerState.stopTouch(it) }
					changed = true
				}
				MotionEvent.ACTION_CANCEL -> {
					touchpadPointerIds.values.forEach { touchpadControllerState.stopTouch(it) }
					touchpadPointerIds.clear()
					changed = true
				}
			}
		}

		if(changed)
			controllerStateUpdated()
		return true
	}

	/** Pointer capture lost (dialog, pause): lift fingers and the click. */
	fun releaseTouchpad()
	{
		touchpadPointerIds.values.forEach { touchpadControllerState.stopTouch(it) }
		touchpadPointerIds.clear()
		touchpadControllerState.buttons = 0U
		controllerStateUpdated()
	}

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		// DualSense touchpad click when the kernel exposes the touchpad as a separate pointer device
		if(event.device?.vendorId == Gamepad.SONY_VENDOR_ID &&
			(event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE))
		{
			keyControllerState.buttons = if(event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS)
				keyControllerState.buttons or ControllerState.BUTTON_TOUCHPAD
			else
				keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
			controllerStateUpdated()
			return true
		}

		if(event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK)
			return false
		lastGamepadDeviceId = event.deviceId
		fun Float.signedAxis() = (this.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
		fun Float.unsignedAxis() = (this.coerceIn(0f, 1f) * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()
		val sticks = Gamepad.readSticks(event)
		motionControllerState.leftX = sticks.leftX.signedAxis()
		motionControllerState.leftY = sticks.leftY.signedAxis()
		motionControllerState.rightX = sticks.rightX.signedAxis()
		motionControllerState.rightY = sticks.rightY.signedAxis()
		motionControllerState.l2State = sticks.l2.unsignedAxis()
		motionControllerState.r2State = sticks.r2.unsignedAxis()
		motionControllerState.buttons = motionControllerState.buttons.let {
			val dpadX = sticks.hatX
			val dpadY = sticks.hatY
			val dpadButtons =
				(if(dpadX > 0.5f) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
						(if(dpadX < -0.5f) ControllerState.BUTTON_DPAD_LEFT else 0U) or
						(if(dpadY > 0.5f) ControllerState.BUTTON_DPAD_DOWN else 0U) or
						(if(dpadY < -0.5f) ControllerState.BUTTON_DPAD_UP else 0U)
			it and (ControllerState.BUTTON_DPAD_RIGHT or
					ControllerState.BUTTON_DPAD_LEFT or
					ControllerState.BUTTON_DPAD_DOWN or
					ControllerState.BUTTON_DPAD_UP).inv() or
					dpadButtons
		}
		//Log.i("StreamSession", "motionEvent => $motionControllerState")
		controllerStateUpdated()
		return true
	}
}
