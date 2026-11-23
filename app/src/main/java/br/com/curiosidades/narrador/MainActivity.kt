package br.com.curiosidades.narrador

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import br.com.curiosidades.narrador.core.Models
import br.com.curiosidades.narrador.ui.AppTheme
import br.com.curiosidades.narrador.ui.Root

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Models.refresh(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { Root(vm) } }
    }

    override fun onStop() { vm.flush(); super.onStop() }
}
