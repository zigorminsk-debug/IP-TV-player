package com.iptvplayer.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

/**
 * Builds human-readable track lists (video quality / audio / subtitles) from
 * the player state and applies selections via [TrackSelectionOverride].
 */
object TrackSelections {

    /** A selectable option; [apply] performs the selection on the player. */
    class Option(
        val id: Int,
        val label: String,
        val isSelected: Boolean,
        val apply: (Player) -> Unit,
    )

    class Group(
        val trackType: Int,
        val title: String,
        val options: List<Option>,
    )

    fun buildGroups(player: Player): List<Group> {
        val result = mutableListOf<Group>()
        val tracks: Tracks = player.currentTracks

        for (type in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
            val options = mutableListOf<Option>()
            var id = 1

            val groups = tracks.groups.filter { it.type == type }
            if (groups.isEmpty()) continue

            val hasSelection = groups.any { g ->
                (0 until g.length).any { g.isTrackSelected(it) }
            }
            val isDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(type)

            // "Auto" option
            options += Option(
                id = 0,
                label = "AUTO",
                isSelected = !isDisabled && !hasManualOverride(player, type),
                apply = { p ->
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .clearOverridesOfType(type)
                        .build()
                },
            )

            for (group in groups) {
                for (i in 0 until group.length) {
                    val format: Format = group.getTrackFormat(i)
                    val selected = group.isTrackSelected(i) && !isDisabled
                    options += Option(
                        id = id++,
                        label = describe(type, format),
                        isSelected = selected,
                        apply = { p ->
                            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(type, false)
                                .clearOverridesOfType(type)
                                .addOverride(
                                    TrackSelectionOverride(group.mediaTrackGroup, i),
                                )
                                .build()
                        },
                    )
                }
            }

            if (type == C.TRACK_TYPE_TEXT || type == C.TRACK_TYPE_AUDIO) {
                options += Option(
                    id = -1,
                    label = if (type == C.TRACK_TYPE_TEXT) "OFF" else "MUTE",
                    isSelected = isDisabled,
                    apply = { p ->
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(type, true)
                            .build()
                    },
                )
            }
            if (!anySelected) {
                // ensure something is marked selected
            }
            result += Group(type, titleFor(type), options)
        }
        return result
    }

    private fun hasManualOverride(player: Player, type: Int): Boolean =
        player.trackSelectionParameters.overrides.values.any { it.type == type }

    private fun titleFor(type: Int): String = when (type) {
        C.TRACK_TYPE_VIDEO -> "VIDEO"
        C.TRACK_TYPE_AUDIO -> "AUDIO"
        C.TRACK_TYPE_TEXT -> "SUBTITLES"
        else -> "TRACKS"
    }

    private fun describe(type: Int, format: Format): String {
        val parts = mutableListOf<String>()
        when (type) {
            C.TRACK_TYPE_VIDEO -> {
                if (format.height > 0) parts += "${format.height}p"
                if (format.bitrate > 0) parts += "${format.bitrate / 1000} kbps"
            }
            C.TRACK_TYPE_AUDIO -> {
                if (format.channelCount > 0) parts += "${format.channelCount}ch"
                if (format.sampleRate > 0) parts += "${format.sampleRate / 1000}kHz"
            }
            else -> {}
        }
        val label = format.label ?: format.language
        if (!label.isNullOrBlank()) parts += label.uppercase()
        if (parts.isEmpty()) parts += format.id ?: "?"
        return parts.joinToString(" · ")
    }
}
