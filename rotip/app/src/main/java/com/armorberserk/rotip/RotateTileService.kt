package com.armorberserk.rotip

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * tile: одно-тап → rotateOnce (DATA→AIRPLANE эскалация).
 * Active-таль: STATE_ACTIVE когда root есть, STATE_INACTIVE если рута нет.
 */
class RotateTileService : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        val ctx = this
        updateTile()
        // rotate — suspend: don't block TileService thread.
        CoroutineScope(Dispatchers.IO).launch {
            Engine.rotate(ctx.applicationContext, allowed = Engine.ROTATION_ORDER)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val root = Engine.hasRoot()
        tile.state = if (root) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_rotate)
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_rotate)
        tile.updateTile()
    }
}
