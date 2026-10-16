package com.emux.x11

import android.app.Activity
import android.os.Bundle

/**
 * Tiny translucent trampoline so Android routes USB_DEVICE_ATTACHED intents to
 * EmuX even when cold-started; we simply finish and let the desktop session
 * discover the device node via the distro (udev-lite scanning of /sys/bus/usb).
 */
class UsbCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
