package com.mapbox.navigation.core.telemetry

import android.location.Location
import java.util.ArrayDeque

internal class PrePostEventLocations(
    private val preEventLocations: ArrayDeque<Location>,
    private val postEventLocations: ArrayDeque<Location>,
    private val onBufferFullAction: (ArrayDeque<Location>, ArrayDeque<Location>) -> Unit
) {
    fun onBufferFull() {
        onBufferFullAction(preEventLocations, postEventLocations)
    }

    fun addPostEventLocation(location: Location) {
        postEventLocations.addFirst(location)
    }

    fun postEventLocationsSize() = postEventLocations.size
}
