package app.viewfinder

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import app.viewfinder.camera.ViewfinderEngine
import app.viewfinder.domain.FileProjectRepository
import java.io.File
import app.viewfinder.ui.ViewfinderScreen
import app.viewfinder.ui.ViewfinderViewModel

class MainActivity : ComponentActivity() {
    private lateinit var engine: ViewfinderEngine
    private val vm: ViewfinderViewModel by viewModels {
        ViewfinderViewModel.Factory(FileProjectRepository(File(filesDir, "projects/default.json")))
    }
    private val permission = mutableStateOf(false)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) = pushRotation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = ViewfinderEngine(applicationContext)
        engine.init()

        val granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        permission.value = granted
        engine.setPermission(granted)

        setContent { ViewfinderScreen(vm, engine, permission.value) }
        hideSystemBars() // needs the decor view, so only after setContent
        pushRotation()

        if (!granted) requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)

        // Debug builds only: `--es vf_script "frame=2.39;zoom=2"` drives the UI without touch injection.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) intent?.getStringExtra("vf_script")?.let(vm::applyDebugScript)
    }

    override fun onResume() {
        super.onResume()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        pushRotation()
        engine.setResumed(true)
    }

    override fun onPause() {
        vm.flush() // save the project right away, not after the autosave delay
        engine.setResumed(false)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        super.onPause()
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            permission.value = ok
            engine.setPermission(ok)
        }
    }

    private fun pushRotation() {
        @Suppress("DEPRECATION")
        engine.setDisplayRotation(windowManager.defaultDisplay.rotation * 90)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private companion object {
        const val REQ_CAMERA = 1
    }
}
