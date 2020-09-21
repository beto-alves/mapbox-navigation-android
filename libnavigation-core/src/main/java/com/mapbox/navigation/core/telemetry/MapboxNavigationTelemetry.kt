package com.mapbox.navigation.core.telemetry

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import android.os.Build
import android.util.Log
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.telemetry.AppUserTurnstile
import com.mapbox.android.telemetry.TelemetryUtils
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.navigation.base.metrics.MetricEvent
import com.mapbox.navigation.base.metrics.MetricsReporter
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.base.trip.model.RouteProgressState.LOCATION_TRACKING
import com.mapbox.navigation.base.trip.model.RouteProgressState.ROUTE_COMPLETE
import com.mapbox.navigation.core.BuildConfig
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.NavigationSession.State
import com.mapbox.navigation.core.NavigationSession.State.ACTIVE_GUIDANCE
import com.mapbox.navigation.core.NavigationSession.State.FREE_DRIVE
import com.mapbox.navigation.core.NavigationSession.State.IDLE
import com.mapbox.navigation.core.NavigationSessionStateObserver
import com.mapbox.navigation.core.internal.accounts.MapboxNavigationAccounts
import com.mapbox.navigation.core.telemetry.events.AppMetadata
import com.mapbox.navigation.core.telemetry.events.FeedbackEvent
import com.mapbox.navigation.core.telemetry.events.MetricsRouteProgress
import com.mapbox.navigation.core.telemetry.events.NavigationArriveEvent
import com.mapbox.navigation.core.telemetry.events.NavigationCancelEvent
import com.mapbox.navigation.core.telemetry.events.NavigationDepartEvent
import com.mapbox.navigation.core.telemetry.events.NavigationEvent
import com.mapbox.navigation.core.telemetry.events.NavigationFeedbackEvent
import com.mapbox.navigation.core.telemetry.events.NavigationRerouteEvent
import com.mapbox.navigation.core.telemetry.events.PhoneState
import com.mapbox.navigation.core.telemetry.events.RerouteEvent
import com.mapbox.navigation.core.telemetry.events.SessionState
import com.mapbox.navigation.core.telemetry.events.TelemetryLocation
import com.mapbox.navigation.metrics.MapboxMetricsReporter
import com.mapbox.navigation.metrics.internal.event.NavigationAppUserTurnstileEvent
import com.mapbox.navigation.utils.internal.JobControl
import com.mapbox.navigation.utils.internal.Time
import com.mapbox.navigation.utils.internal.ifChannelException
import com.mapbox.navigation.utils.internal.ifNonNull
import com.mapbox.navigation.utils.internal.monitorChannelWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

private data class DynamicallyUpdatedRouteValues(
    val distanceRemaining: AtomicLong = AtomicLong(0),
    val timeRemaining: AtomicInteger = AtomicInteger(0),
    val rerouteCount: AtomicInteger = AtomicInteger(0),
    val routeArrived: AtomicBoolean = AtomicBoolean(false),
    val timeOfRerouteEvent: AtomicLong = AtomicLong(0),
    val timeSinceLastReroute: AtomicInteger = AtomicInteger(0),
    var sessionId: String = TelemetryUtils.obtainUniversalUniqueIdentifier(),
    val distanceCompleted: AtomicReference<Float> = AtomicReference(0f),
    val durationRemaining: AtomicInteger = AtomicInteger(0),
    val tripIdentifier: AtomicReference<String> =
        AtomicReference(TelemetryUtils.obtainUniversalUniqueIdentifier()),
    var sessionStartTime: Date = Date(),
    var sessionArrivalTime: AtomicReference<Date?> = AtomicReference(null),
    var sdkId: String = "none",
    val sessionStarted: AtomicBoolean = AtomicBoolean(false),
    val originalRoute: AtomicReference<DirectionsRoute?> = AtomicReference(null)
) {
    fun reset() {
        distanceRemaining.set(0)
        timeRemaining.set(0)
        rerouteCount.set(0)
        routeArrived.set(false)
        timeOfRerouteEvent.set(0)
        sessionId = TelemetryUtils.obtainUniversalUniqueIdentifier()
        distanceCompleted.set(0f)
        durationRemaining.set(0)
        timeSinceLastReroute.set(0)
        tripIdentifier.set(TelemetryUtils.obtainUniversalUniqueIdentifier())
        sessionArrivalTime.set(null)
        sessionStarted.set(false)
    }
}

/**
 * The one and only Telemetry class. This class handles all telemetry events.
 * Event List:
- appUserTurnstile
- navigation.depart
- navigation.feedback
- navigation.reroute
- navigation.fasterRoute
- navigation.arrive
- navigation.cancel
The class must be initialized before any telemetry events are reported. Attempting to use telemetry before initialization is called will throw an exception. Initialization may be called multiple times, the call is idempotent.
The class has two public methods, postUserFeedback() and initialize().
 */
@SuppressLint("StaticFieldLeak")
internal object MapboxNavigationTelemetry : MapboxNavigationTelemetryInterface {
    private const val ONE_SECOND = 1000
    private const val MOCK_PROVIDER = "com.mapbox.navigation.core.replay.ReplayLocationEngine"
    private const val EVENT_VERSION = 7
    internal const val TAG = "MAPBOX_TELEMETRY"

    private lateinit var context: Context // Must be context.getApplicationContext
    private lateinit var telemetryThreadControl: JobControl
    private val telemetryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorSession: Job? = null
    private lateinit var metricsReporter: MetricsReporter
    private lateinit var navigationOptions: NavigationOptions
    private lateinit var localUserAgent: String
    private var weakMapboxNavigation = WeakReference<MapboxNavigation>(null)
    private var lifecycleMonitor: ApplicationLifecycleMonitor? = null
    private var appInstance: Application? = null
        set(value) {
            // Don't set it multiple times to the same value, it will cause multiple registration calls.
            if (field == value) {
                return
            }
            field = value
            ifNonNull(value) { app ->
                Log.d(TAG, "Lifecycle monitor created")
                lifecycleMonitor = ApplicationLifecycleMonitor(app)
            }
        }
    private val dynamicValues = DynamicallyUpdatedRouteValues()
    private var locationEngineNameExternal: String = LocationEngine::javaClass.name
    private val callbackDispatcher: TelemetryLocationAndProgressDispatcher =
        TelemetryLocationAndProgressDispatcherImpl()
    private var navigationSessionStarted = false

    private val navigationSessionObserver = object : NavigationSessionStateObserver {
        override fun onNavigationSessionStateChanged(navigationSession: State) {
            Log.d(TAG, "Navigation state is $navigationSession")
            when (navigationSession) {
                FREE_DRIVE -> switchToNotActiveGuidanceBehavior()
                ACTIVE_GUIDANCE -> sessionStart()
                IDLE -> switchToNotActiveGuidanceBehavior()
            }
        }
    }

    private fun switchToNotActiveGuidanceBehavior() {
        telemetryThreadControl.scope.launch {
            if (navigationSessionStarted) {
                sessionStop()
            }
            navigationSessionStarted = false
            monitorSession?.cancel()
            monitorSession = null
        }
    }

    private fun sendMetricEvent(event: MetricEvent) {
        if (isTelemetryAvailable()) {
            Log.d(TAG, "${event::class.java} event sent")
            metricsReporter.addEvent(event)
        } else {
            Log.d(
                TAG, "${event::class.java} not sent. Caused by: " +
                    "Navigation Session started: ${dynamicValues.sessionStarted.get()}. " +
                    "Route exists: ${dynamicValues.originalRoute.get() != null}"
            )
        }
    }

    private fun sessionStart() {
        Log.d(TAG, "sessionStart")
        telemetryThreadControl.scope.launch {
            navigationSessionStarted = true
            callbackDispatcher.resetRouteProgress()
            callbackDispatcher.originalRoute.await().let {
                sessionStartHelper(it)
            }
        }
    }

    private suspend fun sessionStop() {
        Log.d(TAG, "sessionStop")
        if (!dynamicValues.routeArrived.get()) {
            handleSessionCanceled()
        }
        handleSessionStop()
    }

    /**
     * The Navigation session is considered to be guided if it has been started and at least one route is active,
     * it is a free drive / idle session otherwise
     */
    private fun isTelemetryAvailable(): Boolean {
        return dynamicValues.originalRoute.get() != null && dynamicValues.sessionStarted.get()
    }

    private fun handleOffRouteEvent() {
        telemetryThreadControl.scope.launch {
            val routeProgress = callbackDispatcher.routeProgress
            val newRoute = callbackDispatcher.routeChannel.receive()

            dynamicValues.run {
                timeOfRerouteEvent.set(Time.SystemImpl.millis())
                rerouteCount.addAndGet(1)
                distanceRemaining.set(newRoute.distance()?.toLong() ?: -1)
                timeSinceLastReroute.set(
                    (Time.SystemImpl.millis() - timeOfRerouteEvent.get()).toInt()
                )
            }

            callbackDispatcher.accumulatePostEventLocations { preEventBuffer, postEventBuffer ->
                telemetryThreadControl.scope.launch {
                    val rerouteEvent = RerouteEvent(populateSessionState()).apply {
                        newDistanceRemaining = newRoute.distance()?.toInt() ?: -1
                        newDurationRemaining = newRoute.duration()?.toInt() ?: -1
                        newRouteGeometry = obtainGeometry(newRoute)
                    }

                    val navigationRerouteEvent = NavigationRerouteEvent(
                        PhoneState(context),
                        rerouteEvent,
                        MetricsRouteProgress(routeProgress)
                    ).apply {
                        locationsBefore = preEventBuffer.toTelemetryLocations().toTypedArray()
                        locationsAfter = postEventBuffer.toTelemetryLocations().toTypedArray()
                        secondsSinceLastReroute =
                            dynamicValues.timeSinceLastReroute.get() / ONE_SECOND
                    }
                    populateNavigationEvent(navigationRerouteEvent)
                    sendMetricEvent(navigationRerouteEvent)
                }
            }
        }
    }

    fun setApplicationInstance(app: Application) {
        appInstance = app
    }

    /**
     * This method must be called before using the Telemetry object
     */
    fun initialize(
        context: Context,
        mapboxNavigation: MapboxNavigation,
        metricsReporter: MetricsReporter,
        locationEngineName: String,
        jobControl: JobControl,
        options: NavigationOptions,
        userAgent: String
    ) {
        weakMapboxNavigation.get()?.let {
            unregisterListeners(it)
            telemetryThreadControl.job.cancelChildren()
            telemetryThreadControl.job.cancel()
        }

        telemetryThreadControl = jobControl
        weakMapboxNavigation = WeakReference(mapboxNavigation)
        weakMapboxNavigation.get()?.registerNavigationSessionObserver(navigationSessionObserver)
        registerForNotification(mapboxNavigation)
        monitorOffRouteEvents()
        this.context = context
        localUserAgent = userAgent
        locationEngineNameExternal = locationEngineName
        navigationOptions = options
        this.metricsReporter = metricsReporter
        postTurnstileEvent()
        monitorJobCancellation()
        Log.i(TAG, "Valid initialization")
    }

    private fun monitorOffRouteEvents() {
        telemetryThreadControl.scope.monitorChannelWithException(
            callbackDispatcher.offRouteChannel,
            { offRoute -> if (offRoute) handleOffRouteEvent() }
        )
    }

    private fun monitorJobCancellation() {
        telemetryScope.launch {
            select {
                telemetryThreadControl.job.onJoin {
                    Log.d(TAG, "master job canceled")
                    callbackDispatcher.clearLocationEventBuffer()
                    MapboxMetricsReporter.disable() // Disable telemetry unconditionally
                }
            }
        }
    }

    override fun postUserFeedback(
        @FeedbackEvent.Type feedbackType: String,
        description: String,
        @FeedbackEvent.Source feedbackSource: String,
        screenshot: String?,
        feedbackSubType: Array<String>?,
        appMetadata: AppMetadata?
    ) {
        if (navigationSessionStarted) {
            telemetryThreadControl.scope.launch {
                Log.d(TAG, "trying to post a user feedback event")
                val lastProgress = callbackDispatcher.routeProgress
                callbackDispatcher.accumulatePostEventLocations { preEventBuffer, postEventBuffer ->
                    val feedbackEvent = NavigationFeedbackEvent(
                        PhoneState(context),
                        MetricsRouteProgress(lastProgress)
                    ).apply {
                        this.feedbackType = feedbackType
                        this.source = feedbackSource
                        this.description = description
                        this.screenshot = screenshot
                        this.locationsBefore = preEventBuffer.toTelemetryLocations().toTypedArray()
                        this.locationsAfter = postEventBuffer.toTelemetryLocations().toTypedArray()
                        this.feedbackSubType = feedbackSubType
                        this.appMetadata = appMetadata
                    }
                    telemetryThreadControl.scope.launch {
                        populateNavigationEvent(feedbackEvent)
                        sendMetricEvent(feedbackEvent)
                    }
                }
            }
        }
    }

    private fun ArrayDeque<Location>.toTelemetryLocations(): List<TelemetryLocation> {
        val feedbackLocations = mutableListOf<TelemetryLocation>()
        this.forEach {
            feedbackLocations.add(
                TelemetryLocation(
                    it.latitude,
                    it.longitude,
                    it.speed,
                    it.bearing,
                    it.altitude,
                    it.time.toString(),
                    it.accuracy,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        it.verticalAccuracyMeters
                    } else {
                        0f
                    }
                )
            )
        }

        return feedbackLocations
    }

    private suspend fun handleSessionCanceled() {
        val cancelEvent = NavigationCancelEvent(PhoneState(context))
        ifNonNull(dynamicValues.sessionArrivalTime.get()) {
            cancelEvent.arrivalTimestamp = TelemetryUtils.generateCreateDateFormatted(it)
        }
        populateNavigationEvent(cancelEvent)
        sendMetricEvent(cancelEvent)
        callbackDispatcher.clearLocationEventBuffer()
    }

    private fun handleSessionStop() {
        dynamicValues.reset()
        callbackDispatcher.clearOriginalRoute()
    }

    private fun postTurnstileEvent() {
        val sdkId = generateSdkIdentifier()
        dynamicValues.sdkId = sdkId
        val appUserTurnstileEvent =
            AppUserTurnstile(sdkId, BuildConfig.MAPBOX_NAVIGATION_VERSION_NAME).also {
                it.setSkuId(MapboxNavigationAccounts.getInstance(context).obtainSkuId())
            }
        val event = NavigationAppUserTurnstileEvent(appUserTurnstileEvent)
        metricsReporter.addEvent(event)
    }

    private fun sessionStartHelper(directionsRoute: DirectionsRoute) {
        dynamicValues.run {
            originalRoute.set(directionsRoute)
            sessionId = TelemetryUtils.obtainUniversalUniqueIdentifier()
            sessionStartTime = Date()
            sessionStarted.set(true)
        }

        monitorSession = telemetryThreadControl.scope.launch {
            val departureEvent = telemetryDeparture(
                directionsRoute,
                callbackDispatcher.firstLocation.await()
            )
            sendMetricEvent(departureEvent)
            monitorSession()
        }
    }

    private suspend fun processArrival() {
        Log.d(TAG, "you have arrived")
        dynamicValues.run {
            tripIdentifier.set(TelemetryUtils.obtainUniversalUniqueIdentifier())
            sessionArrivalTime.set(Date())
            routeArrived.set(true)
        }

        val arriveEvent = NavigationArriveEvent(PhoneState(context))
        populateNavigationEvent(arriveEvent)
        sendMetricEvent(arriveEvent)
        callbackDispatcher.clearLocationEventBuffer()
    }

    /**
     * This method waits for an [RouteProgressState.ROUTE_COMPLETE] event. Once received, it terminates the wait-loop and
     * sends the telemetry data to the servers.
     */
    private suspend fun monitorSession() {
        var continueRunning = true
        var trackingEvent = 0
        while (coroutineContext.isActive && continueRunning) {
            try {
                callbackDispatcher.routeProgressChannel.receive().let { progress ->
                    dynamicValues.run {
                        distanceCompleted.set(distanceCompleted.get() + progress.distanceTraveled)
                        distanceRemaining.set(progress.distanceRemaining.toLong())
                        durationRemaining.set(progress.durationRemaining.toInt())
                    }

                    when (progress.currentState) {
                        ROUTE_COMPLETE -> {
                            if (dynamicValues.sessionStarted.get()) {
                                processArrival()
                                continueRunning = false
                            } else {
                                Log.d(TAG, "route arrival received before a session start")
                            }
                        }
                        LOCATION_TRACKING -> {
                            callbackDispatcher.routeProgress?.let {
                                dynamicValues.timeRemaining.set(it.durationRemaining.toInt())
                                dynamicValues.distanceRemaining.set(it.distanceRemaining.toLong())
                            }

                            if (trackingEvent > 20) {
                                Log.d(TAG, "LOCATION_TRACKING received $trackingEvent")
                                trackingEvent = 0
                            } else {
                                trackingEvent++
                            }
                        }
                        else -> {
                            // Do nothing
                        }
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "monitorSession ${e.localizedMessage}")
                e.ifChannelException {
                    continueRunning = false
                }
            }
        }
    }

    private suspend fun telemetryDeparture(
        directionsRoute: DirectionsRoute,
        startingLocation: Location
    ): MetricEvent {
        val departEvent = NavigationDepartEvent(PhoneState(context))
        populateNavigationEvent(departEvent, directionsRoute, startingLocation)
        return departEvent
    }

    private fun registerForNotification(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.run {
            registerRouteProgressObserver(callbackDispatcher)
            registerLocationObserver(callbackDispatcher)
            registerRoutesObserver(callbackDispatcher)
            registerOffRouteObserver(callbackDispatcher)
        }
    }

    override fun unregisterListeners(mapboxNavigation: MapboxNavigation) {
        mapboxNavigation.run {
            unregisterRouteProgressObserver(callbackDispatcher)
            unregisterLocationObserver(callbackDispatcher)
            unregisterRoutesObserver(callbackDispatcher)
            unregisterOffRouteObserver(callbackDispatcher)
            unregisterNavigationSessionObserver(navigationSessionObserver)
        }
        Log.d(TAG, "resetting Telemetry initialization")
        weakMapboxNavigation.clear()
    }

    private suspend fun populateSessionState(newLocation: Location? = null): SessionState {

        return SessionState().apply {
            dynamicValues.let {
                sessionIdentifier = it.sessionId
                tripIdentifier = it.tripIdentifier.get()
                rerouteCount = it.rerouteCount.get()
                startTimestamp = it.sessionStartTime
                arrivalTimestamp = it.sessionArrivalTime.get()
                secondsSinceLastReroute = if (it.timeSinceLastReroute.get() == 0) {
                    -1
                } else
                    it.timeSinceLastReroute.get()
            }

            callbackDispatcher.let {
                eventLocation = newLocation ?: it.lastLocation ?: Location("unknown")
                val originalRoute = it.originalRoute.await()
                originalDirectionRoute = originalRoute
                originalRequestIdentifier = originalRoute.routeOptions()?.requestUuid()

                val routeProgress = it.routeProgress
                eventRouteDistanceCompleted = routeProgress?.distanceTraveled?.toDouble() ?: 0.0
                currentDirectionRoute = routeProgress?.route
                requestIdentifier = routeProgress?.route?.routeOptions()?.requestUuid()
            }

            mockLocation = locationEngineName == MOCK_PROVIDER
            eventDate = Date()
            locationEngineName = locationEngineNameExternal
            percentInForeground = lifecycleMonitor?.obtainForegroundPercentage() ?: 100
            percentInPortrait = lifecycleMonitor?.obtainPortraitPercentage() ?: 100
        }
    }

    private suspend fun populateNavigationEvent(
        navigationEvent: NavigationEvent,
        route: DirectionsRoute? = null,
        newLocation: Location? = null
    ) {
        val directionsRoute = route ?: callbackDispatcher.routeProgress?.route
        val location = newLocation ?: callbackDispatcher.lastLocation

        navigationEvent.apply {
            sdkIdentifier = generateSdkIdentifier()

            callbackDispatcher.routeProgress?.let { routeProgress ->
                stepIndex = routeProgress.currentLegProgress?.currentStepProgress?.stepIndex ?: 0

                routeProgress.route.let {
                    geometry = it.geometry()
                    profile = it.routeOptions()?.profile()
                    requestIdentifier = it.routeOptions()?.requestUuid()
                    stepCount = obtainStepCount(it)
                    legIndex = it.routeIndex()?.toInt() ?: 0
                    legCount = it.legs()?.size ?: 0
                }
            }

            callbackDispatcher.originalRoute.await().let {
                originalStepCount = obtainStepCount(it)
                originalEstimatedDistance = it.distance()?.toInt() ?: 0
                originalEstimatedDuration = it.duration()?.toInt() ?: 0
                originalRequestIdentifier = it.routeOptions()?.requestUuid()
                originalGeometry = it.geometry()
            }

            locationEngine = locationEngineNameExternal
            tripIdentifier = TelemetryUtils.obtainUniversalUniqueIdentifier()
            lat = location?.latitude ?: 0.0
            lng = location?.longitude ?: 0.0
            simulation = locationEngineNameExternal == MOCK_PROVIDER
            percentTimeInPortrait = lifecycleMonitor?.obtainPortraitPercentage() ?: 100
            percentTimeInForeground = lifecycleMonitor?.obtainForegroundPercentage() ?: 100

            dynamicValues.let {
                startTimestamp = TelemetryUtils.generateCreateDateFormatted(it.sessionStartTime)
                distanceCompleted = it.distanceCompleted.get().toInt()
                distanceRemaining = it.distanceRemaining.get().toInt()
                durationRemaining = it.durationRemaining.get()
                rerouteCount = it.rerouteCount.get()
                sessionIdentifier = it.sessionId
            }

            eventVersion = EVENT_VERSION

            directionsRoute?.let {
                absoluteDistanceToDestination = obtainAbsoluteDistance(
                    callbackDispatcher.lastLocation,
                    obtainRouteDestination(it)
                )
                estimatedDistance = it.distance()?.toInt() ?: 0
                estimatedDuration = it.duration()?.toInt() ?: 0

                // TODO:OZ voiceIndex is not available in SDK 1.0 and was not set in the legacy telemetry        navigationEvent.voiceIndex
                // TODO:OZ bannerIndex is not available in SDK 1.0 and was not set in the legacy telemetry        navigationEvent.bannerIndex
                totalStepCount = obtainStepCount(it)
            }
        }
    }

    private fun generateSdkIdentifier() =
        if (navigationOptions.isFromNavigationUi)
            "mapbox-navigation-ui-android"
        else
            "mapbox-navigation-android"
}
