package com.futaiii.sudodroid.qs

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.futaiii.sudodroid.MainActivity
import com.futaiii.sudodroid.SudodroidApp
import com.futaiii.sudodroid.R
import com.futaiii.sudodroid.vpn.SudokuVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        toggle()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.qs_tile_proxy_label)
        tile.state = if (SudokuVpnService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private fun toggle() {
        if (SudokuVpnService.isRunning) {
            val intent = Intent(this, SudokuVpnService::class.java).apply {
                action = SudokuVpnService.ACTION_STOP
            }
            // Service is already running in foreground; startService is sufficient and avoids
            // foreground-service start requirements on some devices.
            startService(intent)
            refreshTile()
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_AUTO_START_VPN, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityAndCollapse(intent)
            return
        }

        scope.launch {
            val nodeId = withContext(Dispatchers.IO) {
                val repo = (application as SudodroidApp).nodeRepository
                val nodes = repo.nodes.first()
                val preferred = repo.lastActiveId.first()
                preferred?.takeIf { id -> nodes.any { it.id == id } } ?: nodes.firstOrNull()?.id
            }
            if (nodeId.isNullOrBlank()) {
                val intent = Intent(this@VpnTileService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivityAndCollapse(intent)
                return@launch
            }
            val intent = Intent(this@VpnTileService, SudokuVpnService::class.java).apply {
                putExtra(SudokuVpnService.EXTRA_NODE_ID, nodeId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            refreshTile()
        }
    }

    companion object {
        fun requestListeningState(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            TileService.requestListeningState(
                context,
                ComponentName(context, VpnTileService::class.java)
            )
        }
    }
}
