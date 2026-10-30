package app.facefiles

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.facefiles.data.AppDatabase
import app.facefiles.data.buildDatabase
import app.facefiles.platform.androidDatabaseBuilder
import app.facefiles.platform.androidScanRoots
import app.facefiles.scan.FileScanner
import app.facefiles.scan.ScanOptions
import app.facefiles.ui.App

class MainActivity : ComponentActivity() {

    private var hasAccess by mutableStateOf(false)
    private lateinit var database: AppDatabase
    private lateinit var scanner: FileScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = buildDatabase(androidDatabaseBuilder(this))
        scanner = FileScanner(
            fileDao = database.fileDao(),
            rootsProvider = { androidScanRoots(this) },
            options = ScanOptions(workers = 4),
        )
        setContent {
            if (hasAccess) {
                App(indexer = scanner, fileDao = database.fileDao())
            } else {
                AccessScreen(onGrant = ::requestAccess)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from the system settings / permission dialog.
        hasAccess = hasAllFilesAccess()
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                1,
            )
        }
    }
}

@Composable
private fun AccessScreen(onGrant: () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("FaceFiles needs access to your files", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Everything stays on this device. No cloud, no uploads, no accounts.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onGrant) { Text("Grant file access") }
            }
        }
    }
}
