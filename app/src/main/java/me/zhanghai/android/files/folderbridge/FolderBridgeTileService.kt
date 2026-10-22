package me.zhanghai.android.files.folderbridge

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import me.zhanghai.android.files.compat.doWithStartForegroundServiceAllowed

@RequiresApi(Build.VERSION_CODES.N)
class FolderBridgeTileService : TileService() {
    private val observer = Observer<FolderBridgeService.Status> { onStatusChanged(it) }

    override fun onStartListening() {
        super.onStartListening()

        FolderBridgeService.statusLiveData.observeForever(observer)
    }

    override fun onStopListening() {
        super.onStopListening()

        FolderBridgeService.statusLiveData.removeObserver(observer)
    }

    private fun onStatusChanged(status: FolderBridgeService.Status) {
        val tile = qsTile
        when (status.state) {
            FolderBridgeService.State.STARTING,
            FolderBridgeService.State.RUNNING -> tile.state = Tile.STATE_ACTIVE
            FolderBridgeService.State.STOPPING -> tile.state = Tile.STATE_UNAVAILABLE
            FolderBridgeService.State.STOPPED -> tile.state = Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()

        if (isLocked) {
            unlockAndRun { toggle() }
        } else {
            toggle()
        }
    }

    private fun toggle() {
        doWithStartForegroundServiceAllowed { FolderBridgeService.toggle(this) }
    }
}
