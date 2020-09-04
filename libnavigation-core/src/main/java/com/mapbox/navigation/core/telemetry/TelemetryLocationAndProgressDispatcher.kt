package com.mapbox.navigation.core.telemetry

import android.location.Location
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.OffRouteObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.ArrayDeque

internal interface TelemetryLocationAndProgressDispatcher :
    RouteProgressObserver, LocationObserver, RoutesObserver, OffRouteObserver {
    val offRouteChannel: ReceiveChannel<Boolean>
    val routeChannel: ReceiveChannel<DirectionsRoute>
    val routeProgressChannel: ReceiveChannel<RouteProgress>

    val lastLocation: Location?
    val routeProgress: RouteProgress?
    val firstLocation: Deferred<Location>
    val originalRoute: Deferred<DirectionsRoute>

    suspend fun clearLocationEventBuffer()
    fun clearOriginalRoute()
    fun resetRouteProgress()

    fun accumulatePostEventLocations(onBufferFull: (ArrayDeque<Location>, ArrayDeque<Location>) -> Unit)
}
