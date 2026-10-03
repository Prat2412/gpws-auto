package dev.gpws.auto

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: the GPWS killswitch, one swipe away without opening the app. */
class KillTile : TileService() {

    override fun onStartListening() {
        Gpws.init(this)
        render()
    }

    override fun onClick() {
        Gpws.init(this)
        Gpws.armed = !Gpws.armed
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        tile.state = if (Gpws.armed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "GPWS"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (Gpws.armed) "Armed" else "Inhibited"
        tile.updateTile()
    }
}
