package com.mapbox.navigation.core.telemetry

import android.location.Location
import android.util.Log
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.telemetry.MapboxNavigationTelemetry.TAG
import com.mapbox.navigation.utils.internal.ThreadController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.Collections.synchronizedList

internal class TelemetryLocationAndProgressDispatcherImpl :
    TelemetryLocationAndProgressDispatcher {

    companion object {
        private const val LOCATION_BUFFER_MAX_SIZE = 20
    }

    private val lastLocations = synchronizedList(mutableListOf<Location>())
    private val eventsLocationsBuffer = synchronizedList(mutableListOf<PrePostEventLocations>())
    private var _routeProgress: RouteProgress? = null
    private var _originalRoute = CompletableDeferred<DirectionsRoute>()

    override val offRouteChannel = Channel<Boolean>(Channel.CONFLATED)
    override val routeChannel = Channel<DirectionsRoute>(Channel.CONFLATED)
    override val routeProgressChannel = Channel<RouteProgress>(Channel.CONFLATED)

    override val firstLocation = CompletableDeferred<Location>()
    override val lastLocation: Location?
        get() = lastLocations[0]
    override val routeProgress = _routeProgress
    override val originalRoute = _originalRoute

    private fun processLocationEventBuffer(location: Location) {
        synchronized(eventsLocationsBuffer) {
            val iterator = eventsLocationsBuffer.iterator()
            while (iterator.hasNext()) {
                iterator.next().let {
                    it.addPostEventLocation(location)
                    if (it.postEventLocationsSize() >= LOCATION_BUFFER_MAX_SIZE) {
                        it.onBufferFull()
                        iterator.remove()
                    }
                }
            }
        }
    }

    private fun flushLocationEventBuffer() {
        Log.d(TAG, "flushing buffers before ${lastLocations.size}")
        eventsLocationsBuffer.forEach { it.onBufferFull() }
    }

    private fun accumulateLocation(location: Location) {
        lastLocations.run {
            if (size >= LOCATION_BUFFER_MAX_SIZE) {
                removeLast()
            }
            addFirst(location)
        }
    }

    override fun accumulatePostEventLocations(
        onBufferFull: (ArrayDeque<Location>, ArrayDeque<Location>) -> Unit
    ) {
        eventsLocationsBuffer.addFirst(
            PrePostEventLocations(
                ArrayDeque(lastLocations.getCopy()),
                ArrayDeque(),
                onBufferFull
            )
        )
    }

    override suspend fun clearLocationEventBuffer() {
        withContext(ThreadController.IODispatcher) {
            flushLocationEventBuffer()
            eventsLocationsBuffer.clear()
        }
    }

    override fun onRouteProgressChanged(routeProgress: RouteProgress) {
        Log.d(TAG, "route progress state = ${routeProgress.currentState}")
        _routeProgress = routeProgress
        routeProgressChannel.offer(routeProgress)
    }

    override fun clearOriginalRoute() {
        _originalRoute = CompletableDeferred()
    }

    override fun resetRouteProgress() {
        _routeProgress = null
        routeProgressChannel.poll() // remove element from the channel if exists
    }

    override fun onRawLocationChanged(rawLocation: Location) {
        accumulateLocation(rawLocation)
        processLocationEventBuffer(rawLocation)
        firstLocation.complete(rawLocation)
    }

    override fun onEnhancedLocationChanged(enhancedLocation: Location, keyPoints: List<Location>) {
        // Do nothing
    }

    override fun onRoutesChanged(routes: List<DirectionsRoute>) {
        Log.d(TAG, "onRoutesChanged received. Route list size = ${routes.size}")
        if (routes.isNotEmpty()) {
            routeChannel.offer(routes[0])
            _originalRoute.complete(routes[0])
        }
    }

    override fun onOffRouteStateChanged(offRoute: Boolean) {
        Log.d(TAG, "onOffRouteStateChanged $offRoute")
        offRouteChannel.offer(offRoute)
    }

    private fun <T> MutableList<T>.addFirst(item: T) {
        this.add(0, item)
    }

    @Synchronized
    private fun <T> MutableList<T>.getCopy(): List<T> {
        return mutableListOf<T>().also {
            it.addAll(this)
        }
    }
}
