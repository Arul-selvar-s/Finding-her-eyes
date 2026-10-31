package app.facefiles.platform

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.facefiles.FaceScanHolder
import app.facefiles.FaceScanService
import app.facefiles.domain.FaceScanControl

/** Starting a scan also starts the foreground service so Android does not kill us in the background. */
class AndroidFaceScanControl(
    private val activity: Activity,
    private val delegate: FaceScanControl,
) : FaceScanControl by delegate {

    override fun start() {
        FaceScanHolder.control = delegate
        delegate.start()
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            activity.startForegroundService(Intent(activity, FaceScanService::class.java))
        } catch (e: Exception) {
            // The scan still runs while the app is in the foreground.
        }
    }
}
