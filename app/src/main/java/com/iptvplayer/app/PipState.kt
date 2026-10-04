package com.iptvplayer.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Global picture-in-picture state, published by [MainActivity] from the
 * framework callback and read by the player UI (controls are hidden in PiP).
 */
object PipState {
    var isInPip: Boolean by mutableStateOf(false)
}
