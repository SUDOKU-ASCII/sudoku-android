package com.futaiii.sudodroid

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.proxy.ProxyOnlyService
import com.futaiii.sudodroid.ui.AppRoot
import com.futaiii.sudodroid.ui.AppViewModel
import com.futaiii.sudodroid.ui.AppViewModelFactory
import com.futaiii.sudodroid.vpn.SudokuVpnService
import android.os.Build

class MainActivity : ComponentActivity() {
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpnService()
            }
        }

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
                        },
                        onToggleProxyOnly = { isRunning ->
                            if (isRunning) stopProxyOnly() else startProxyOnly()
                        },
                        onSwitchNodeWhileProxyOnlyRunning = { node ->
                            switchProxyNode(node)
                        },
                        onRestartRunningProxy = {
                            restartRunningProxy()
                        }
                    )
                }
            }
        }

        maybeAutoStartFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeAutoStartFromIntent(intent)
    }

    private fun maybeAutoStartFromIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START_VPN, false) == true) {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
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

    private fun stopVpn() {
        val intent = Intent(this, SudokuVpnService::class.java).apply {
            action = SudokuVpnService.ACTION_STOP
        }
        // Service is already running in foreground; startService is sufficient.
        startService(intent)
    }

    private fun switchNode(node: NodeConfig) {
        val intent = Intent(this, SudokuVpnService::class.java).apply {
            action = SudokuVpnService.ACTION_SWITCH_NODE
            putExtra(SudokuVpnService.EXTRA_NODE_ID, node.id)
        }
        // Service is already running in foreground; startService is sufficient.
        startService(intent)
    }

    private fun startProxyOnly() {
        val activeId = viewModel.state.value.activeId
        val intent = Intent(this, ProxyOnlyService::class.java).apply {
            putExtra(ProxyOnlyService.EXTRA_NODE_ID, activeId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopProxyOnly() {
        val intent = Intent(this, ProxyOnlyService::class.java).apply {
            action = ProxyOnlyService.ACTION_STOP
        }
        startService(intent)
    }

    private fun switchProxyNode(node: NodeConfig) {
        val intent = Intent(this, ProxyOnlyService::class.java).apply {
            action = ProxyOnlyService.ACTION_SWITCH_NODE
            putExtra(ProxyOnlyService.EXTRA_NODE_ID, node.id)
        }
        startService(intent)
    }

    private fun restartRunningProxy() {
        when {
            SudokuVpnService.isRunning -> {
                startService(Intent(this, SudokuVpnService::class.java).apply {
                    action = SudokuVpnService.ACTION_RELOAD_CONFIG
                })
            }

            ProxyOnlyService.isRunning -> {
                startService(Intent(this, ProxyOnlyService::class.java).apply {
                    action = ProxyOnlyService.ACTION_RELOAD_CONFIG
                })
            }
        }
    }

    companion object {
        const val EXTRA_AUTO_START_VPN = "com.futaiii.sudodroid.extra.AUTO_START_VPN"
    }
}
