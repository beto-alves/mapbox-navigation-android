package com.mapbox.navigation.ui.route;

import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mapbox.api.directions.v5.models.DirectionsRoute;
import com.mapbox.geojson.Feature;
import com.mapbox.geojson.LineString;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.Style;

import java.util.List;
import java.util.Map;

import static com.mapbox.navigation.ui.internal.route.RouteConstants.ALTERNATIVE_ROUTE_CASING_LAYER_ID;
import static com.mapbox.navigation.ui.internal.route.RouteConstants.ALTERNATIVE_ROUTE_LAYER_ID;
import static com.mapbox.navigation.ui.internal.route.RouteConstants.PRIMARY_ROUTE_CASING_LAYER_ID;
import static com.mapbox.navigation.ui.internal.route.RouteConstants.PRIMARY_ROUTE_LAYER_ID;

class MapRouteClickListener implements MapboxMap.OnMapClickListener {

  private final String TAG = "MapRouteClickListener";
  private final MapRouteLine routeLine;
  private MapboxMap mapboxMap;

  private OnRouteSelectionChangeListener onRouteSelectionChangeListener;
  private boolean alternativesVisible = true;

  MapRouteClickListener(MapRouteLine routeLine, MapboxMap mapboxMap) {
    this.routeLine = routeLine;
    this.mapboxMap = mapboxMap;
  }

  @Override
  public boolean onMapClick(@NonNull LatLng mapClickPoint) {
    if (!isRouteVisible()) {
      return false;
    }
    Map<LineString, DirectionsRoute> routeLineStrings = routeLine.retrieveRouteLineStrings();
    if (invalidMapClick(routeLineStrings)) {
      return false;
    }
    List<DirectionsRoute> directionsRoutes = routeLine.retrieveDirectionsRoutes();
    findClickedRoute(mapClickPoint, routeLineStrings, directionsRoutes);
    return false;
  }

  void setOnRouteSelectionChangeListener(OnRouteSelectionChangeListener listener) {
    onRouteSelectionChangeListener = listener;
  }

  void updateAlternativesVisible(boolean alternativesVisible) {
    this.alternativesVisible = alternativesVisible;
  }

  private boolean invalidMapClick(@Nullable Map<LineString, DirectionsRoute> routeLineStrings) {
    return routeLineStrings == null || routeLineStrings.isEmpty() || !alternativesVisible;
  }

  private boolean isRouteVisible() {
    return routeLine.retrieveVisibility();
  }

  private void findClickedRoute(@NonNull LatLng mapClickPoint, @NonNull Map<LineString, DirectionsRoute> routeLineStrings,
                                @NonNull List<DirectionsRoute> directionsRoutes) {
    mapboxMap.getStyle(new Style.OnStyleLoaded() {
      @Override
      public void onStyleLoaded(@NonNull Style style) {
        PointF mapClickPointF = mapboxMap.getProjection().toScreenLocation(mapClickPoint);
        // TODO: Use rectF and make 10 be adjustable parameter instead?
        int spacingNumber = 300;
        RectF rectF = new RectF(mapClickPointF.x - spacingNumber, mapClickPointF.y - spacingNumber, mapClickPointF.x + spacingNumber, mapClickPointF.y + spacingNumber);

        int newPrimaryRouteIndex = 0;
        List<Feature> selectedPrimaryRouteLayerFeatureList = mapboxMap.queryRenderedFeatures(mapClickPointF, PRIMARY_ROUTE_LAYER_ID, PRIMARY_ROUTE_CASING_LAYER_ID);
        Log.d(TAG, "onStyleLoaded: selectedPrimaryRouteLayerFeatureList size = " + selectedPrimaryRouteLayerFeatureList.size());
        List<Feature> selectedAlternativeRouteLayerFeatureList = mapboxMap.queryRenderedFeatures(mapClickPointF, ALTERNATIVE_ROUTE_LAYER_ID, ALTERNATIVE_ROUTE_CASING_LAYER_ID);        Log.d(TAG, "onStyleLoaded: selectedPrimaryRouteLayerFeatureList size = " + selectedPrimaryRouteLayerFeatureList.size());
        Log.d(TAG, "onStyleLoaded: selectedAlternativeRouteLayerFeatureList size = " + selectedAlternativeRouteLayerFeatureList.size());
        if (!selectedPrimaryRouteLayerFeatureList.isEmpty()) {
          for (Feature singleFeature : selectedPrimaryRouteLayerFeatureList) {
            Log.d(TAG, "onStyleLoaded: singleFeature = " + singleFeature.getNumberProperty("ROUTE_LINE_FEATURE_INDEX_KEY"));
          }
          newPrimaryRouteIndex = 0;
        } else if (!selectedAlternativeRouteLayerFeatureList.isEmpty()) {
          newPrimaryRouteIndex = 1;
        }
        Log.d(TAG, "onStyleLoaded: newPrimaryRouteIndex = " + newPrimaryRouteIndex);
        DirectionsRoute clickedRoute = directionsRoutes.get(newPrimaryRouteIndex);
        routeLine.updatePrimaryRouteIndex(clickedRoute);
        if (clickedRoute != routeLine.getPrimaryRoute() && onRouteSelectionChangeListener != null) {
          onRouteSelectionChangeListener.onNewPrimaryRouteSelected(clickedRoute);
        }
      }
    });
  }
}
