package com.mapbox.services.android.navigation.v5.milestone;

import com.mapbox.services.android.navigation.v5.RouteProgress;

public interface MilestoneEventListener {

  void onMilestoneEvent(RouteProgress routeProgress, NavigationMilestone milestone);

}
