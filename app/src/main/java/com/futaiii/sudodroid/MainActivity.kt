package com.futaiii.sudodroid

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.ui.AppRoot
import com.futaiii.sudodroid.ui.AppViewModel
import com.futaiii.sudodroid.ui.AppViewModelFactory
import com.futaiii.sudodroid.vpn.SudokuVpnService
import android.os.Build

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        AppViewModelFactory((application as SudodroidApp).nodeRepository, application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        setContent {
            com.futaiii.sudodroid.ui.theme.SudodroidTheme {
                Surface(modifier = Modifier) {
                    AppRoot(
                        viewModel = viewModel,
                        onToggleVpn = { isRunning ->
                            if (isRunning) stopVpn() else startVpn()
                        },
                        onSwitchNodeWhileRunning = { node ->
                            switchNode(node)
                        }
                    )
                }
            }
        }
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, REQUEST_VPN)
        } else {
            onActivityResult(REQUEST_VPN, RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            val activeId = viewModel.state.value.activeId
            val intent = Intent(this, SudokuVpnService::class.java).apply {
                putExtra(SudokuVpnService.EXTRA_NODE_ID, activeId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, SudokuVpnService::class.java).apply {
            action = SudokuVpnService.ACTION_STOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun switchNode(node: NodeConfig) {
        val intent = Intent(this, SudokuVpnService::class.java).apply {
            action = SudokuVpnService.ACTION_SWITCH_NODE
            putExtra(SudokuVpnService.EXTRA_NODE_ID, node.id)
        }
        // Service is already running in foreground; startService is sufficient.
        startService(intent)
    }

    companion object {
        private const val REQUEST_VPN = 100
    }
}
