package com.mapbox.navigation.core.routeoptions

import android.location.Location
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.base.common.logger.Logger
import com.mapbox.base.common.logger.model.Message
import com.mapbox.base.common.logger.model.Tag
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.trip.model.RouteProgress

private const val DEFAULT_REROUTE_BEARING_TOLERANCE = 90.0

/**
 * Default implementation of [RouteOptionsUpdater].
 */
class MapboxRouteOptionsUpdater(
    private val logger: Logger? = null
) : RouteOptionsUpdater {

    /**
     * Provides a new [RouteOptions] instance based on the original request options and the current route progress.
     *
     * Returns *null* if a new [RouteOptions] instance cannot be combined based on the input given. When *null*
     * is returned new route is not fetched.
     */
    override fun update(
        routeOptions: RouteOptions?,
        routeProgress: RouteProgress?,
        location: Location?
    ): RouteOptionsUpdater.RouteOptionsResult {
        if (routeOptions == null || routeProgress == null || location == null) {
            val msg = "Cannot combine RouteOptions, invalid inputs. routeOptions, " +
                "routeProgress, and location mustn't be null"
            logger?.w(
                Tag("MapboxRouteOptionsProvider"),
                Message(msg)
            )
            return RouteOptionsUpdater.RouteOptionsResult.Error(Throwable(msg))
        }

        val optionsBuilder = routeOptions.toBuilder()
        val coordinates = routeOptions.coordinates()

        routeProgress.currentLegProgress?.legIndex?.let { index ->
            optionsBuilder
                .coordinates(
                    coordinates.drop(index + 1).toMutableList().apply {
                        add(0, Point.fromLngLat(location.longitude, location.latitude))
                    }
                )
                .bearingsList(let {
                    val bearings = mutableListOf<List<Double>?>()

                    val originTolerance = routeOptions.bearingsList()?.getOrNull(0)?.getOrNull(1)
                        ?: DEFAULT_REROUTE_BEARING_TOLERANCE
                    val currentAngle = location.bearing.toDouble()

                    bearings.add(listOf(currentAngle, originTolerance))
                    val originalBearings = routeOptions.bearingsList()
                    if (originalBearings != null) {
                        bearings.addAll(originalBearings.subList(index + 1, coordinates.size))
                    } else {
                        while (bearings.size < coordinates.size) {
                            bearings.add(null)
                        }
                    }
                    bearings
                })
                .radiusesList(let radiusesList@{
                    if (routeOptions.radiusesList().isNullOrEmpty()) {
                        return@radiusesList emptyList<Double>()
                    }
                    mutableListOf<Double>().also {
                        it.addAll(routeOptions.radiusesList()!!.subList(index, coordinates.size))
                    }
                })
                .approachesList(let approachesList@{
                    if (routeOptions.approachesList().isNullOrEmpty()) {
                        return@approachesList emptyList<String>()
                    }
                    mutableListOf<String>().also {
                        it.addAll(routeOptions.approachesList()!!.subList(index, coordinates.size))
                    }
                })
                .waypointIndicesList(let waypointIndicesList@{
                    val waypointIndicesList = routeOptions.waypointIndicesList()
                    if (waypointIndicesList.isNullOrEmpty()) {
                        return@waypointIndicesList emptyList<Int>()
                    }
                    mutableListOf<Int>().also { updatedWaypointIndicesList ->
                        var updatedStartWaypointIndicesIndex = 0
                        routeOptions.waypointIndicesList()!!.forEachIndexed { indx, waypointIndex ->
                            if (waypointIndex <= index) {
                                updatedStartWaypointIndicesIndex = indx
                            }
                        }
                        updatedWaypointIndicesList.add(waypointIndicesList[updatedStartWaypointIndicesIndex])
                        updatedWaypointIndicesList.addAll(
                                waypointIndicesList.subList(updatedStartWaypointIndicesIndex + 1, waypointIndicesList.size).map { it - index }
                        )
                    }
                })
                .waypointNamesList(let waypointNamesList@{
                    val waypointNamesList = routeOptions.waypointNamesList()
                    if (waypointNamesList.isNullOrEmpty()) {
                        return@waypointNamesList emptyList<String>()
                    }
                    mutableListOf<String>().also { updatedWaypointNamesList ->
                        var updatedStartWaypointNamesIndex = 0
                        routeOptions.waypointIndicesList()!!.forEachIndexed { indx, waypointIndex ->
                            if (waypointIndex <= index) {
                                updatedStartWaypointNamesIndex = indx
                            }
                        }
                        updatedWaypointNamesList.add(waypointNamesList[updatedStartWaypointNamesIndex])
                        updatedWaypointNamesList.addAll(
                                waypointNamesList.subList(updatedStartWaypointNamesIndex + 1, waypointNamesList.size)
                        )
                    }
                })
                .waypointTargetsList(let waypointTargetsList@{
                    if (routeOptions.waypointTargetsList().isNullOrEmpty()) {
                        return@waypointTargetsList emptyList<Point>()
                    }
                    mutableListOf<Point?>().also {
                        it.add(null)
                        it.addAll(
                            routeOptions.waypointTargetsList()!!.subList(
                                index + 1,
                                coordinates.size
                            )
                        )
                    }
                })
        }

        return RouteOptionsUpdater.RouteOptionsResult.Success(optionsBuilder.build())
    }
}
