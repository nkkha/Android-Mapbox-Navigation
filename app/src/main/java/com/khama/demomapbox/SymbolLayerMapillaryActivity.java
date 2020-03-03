package com.khama.demomapbox;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PointF;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.recyclerview.widget.RecyclerView;

import com.mapbox.geojson.Feature;
import com.mapbox.geojson.FeatureCollection;
import com.mapbox.geojson.Point;
import com.mapbox.mapboxsdk.Mapbox;
import com.mapbox.mapboxsdk.camera.CameraPosition;
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback;
import com.mapbox.mapboxsdk.maps.Style;
import com.mapbox.mapboxsdk.style.expressions.Expression;
import com.mapbox.mapboxsdk.style.layers.CircleLayer;
import com.mapbox.mapboxsdk.style.layers.SymbolLayer;
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource;

import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.mapbox.mapboxsdk.style.expressions.Expression.eq;
import static com.mapbox.mapboxsdk.style.expressions.Expression.exponential;
import static com.mapbox.mapboxsdk.style.expressions.Expression.get;
import static com.mapbox.mapboxsdk.style.expressions.Expression.interpolate;
import static com.mapbox.mapboxsdk.style.expressions.Expression.literal;
import static com.mapbox.mapboxsdk.style.expressions.Expression.match;
import static com.mapbox.mapboxsdk.style.expressions.Expression.stop;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.circleColor;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.circleOpacity;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.circleRadius;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconAllowOverlap;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconImage;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconSize;

public class SymbolLayerMapillaryActivity extends AppCompatActivity implements OnMapReadyCallback,
        MapboxMap.OnMapClickListener {
    private static final String SOURCE_ID = "mapbox.poi";
    private static final String MAKI_LAYER_ID = "mapbox.poi.maki";
    private static final String LOADING_LAYER_ID = "mapbox.poi.loading";
    private static final String LOADING_LAYER_ID1 = "mapbox.poi.loading1";

    private static final String PROPERTY_SELECTED = "selected";
    private static final String PROPERTY_LOADING = "loading";
    private static final String PROPERTY_LOADING1 = "loading1";
    private static final String PROPERTY_LOADING_PROGRESS = "loading_progress";
    private static final String PROPERTY_LOADING_PROGRESS1 = "loading_progress1";
    private static final String PROPERTY_LOADING_OPACITY = "loading_opacity";
    private static final String PROPERTY_LOADING_OPACITY1 = "loading_opacity1";
    private static final String PROPERTY_TITLE = "title";

    private static final long CAMERA_ANIMATION_TIME = 1950;
    private static final float LOADING_CIRCLE_RADIUS = 40;
    private static final float LOADING_CIRCLE_RADIUS1 = 40;
    private static final int LOADING_PROGRESS_STEPS = 25;
    private static final int LOADING_PROGRESS_STEPS1 = 25;//number of steps in a progress animation
    private static final int LOADING_STEP_DURATION = 50; //duration between each step

    private MapView mapView;
    private MapboxMap mapboxMap;
    private RecyclerView recyclerView;

    private GeoJsonSource source;
    private FeatureCollection featureCollection;
    private AnimatorSet animatorSet;

    private LoadMapillaryDataTask loadMapillaryDataTask;

    @ActivityStep
    private int currentStep;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef( {STEP_INITIAL, STEP_LOADING})
    @interface ActivityStep {
    }

    private static final int STEP_INITIAL = 0;
    private static final int STEP_LOADING = 1;

    private static final Map<Integer, Double> stepZoomMap = new HashMap<>();

    static {
        stepZoomMap.put(STEP_INITIAL, 11.0);
        stepZoomMap.put(STEP_LOADING, 13.5);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Mapbox.getInstance(this, getString(R.string.access_token));
        setContentView(R.layout.activity_symbol_layer_mapillary);

        recyclerView = findViewById(R.id.rv_on_top_of_map);

        // Initialize the map view
        mapView = findViewById(R.id.mapView);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);
    }

    @Override
    public void onMapReady(@NonNull final MapboxMap mapboxMap) {
        this.mapboxMap = mapboxMap;
        mapboxMap.setStyle(Style.MAPBOX_STREETS, style -> {
            mapboxMap.getUiSettings().setCompassEnabled(false);
            mapboxMap.getUiSettings().setLogoEnabled(false);
            mapboxMap.getUiSettings().setAttributionEnabled(false);
            new LoadPoiDataTask(SymbolLayerMapillaryActivity.this).execute();
            mapboxMap.addOnMapClickListener(SymbolLayerMapillaryActivity.this);
        });
    }

    @Override
    public boolean onMapClick(@NonNull LatLng point) {
        PointF screenPoint = mapboxMap.getProjection().toScreenLocation(point);
        return handleClickIcon(screenPoint);
    }

    public void setupData(final FeatureCollection collection) {
        if (mapboxMap == null) {
            return;
        }
        featureCollection = collection;
        mapboxMap.getStyle(style -> {
            setupSource(style);
            setupMakiLayer(style);
            setupLoadingLayer(style);
            setupLoadingLayer1(style);
        });
    }

    private void setupSource(@NonNull Style loadedMapStyle) {
        source = new GeoJsonSource(SOURCE_ID, featureCollection);
        loadedMapStyle.addSource(source);
    }

    private void refreshSource() {
        if (source != null && featureCollection != null) {
            source.setGeoJson(featureCollection);
        }
    }

    /**
     * Setup a layer with maki icons, eg. restaurant.
     */
    private void setupMakiLayer(@NonNull Style loadedMapStyle) {
        loadedMapStyle.addLayer(new SymbolLayer(MAKI_LAYER_ID, SOURCE_ID)
                .withProperties(
                        /* show maki icon based on the value of poi feature property
                         * https://www.mapbox.com/maki-icons/
                         */
                        iconImage("{poi}-15"),

                        /* allows show all icons */
                        iconAllowOverlap(true),

                        /* when feature is in selected state, grow icon */
                        iconSize(match(Expression.toString(get(PROPERTY_SELECTED)), literal(1.0f),
                                stop("true", 1.5f))))
        );
    }

    /**
     * Setup layer indicating that there is an ongoing progress.
     */
    private void setupLoadingLayer(@NonNull Style loadedMapStyle) {
        loadedMapStyle.addLayerBelow(new CircleLayer(LOADING_LAYER_ID, SOURCE_ID)
                .withProperties(
                        circleRadius(interpolate(exponential(1), get(PROPERTY_LOADING_PROGRESS), getLoadingAnimationStops())),
                        circleColor(Color.RED),
                        circleOpacity(interpolate(exponential(1), get(PROPERTY_LOADING_OPACITY), getLoadingAnimationStops()))
                )
                .withFilter(eq(get(PROPERTY_LOADING), literal(true))), MAKI_LAYER_ID);
    }

    private Expression.Stop[] getLoadingAnimationStops() {
        List<Expression.Stop> stops = new ArrayList<>();
        for (int i = 0; i < LOADING_PROGRESS_STEPS; i++) {
            stops.add(stop(i, LOADING_CIRCLE_RADIUS * i / LOADING_PROGRESS_STEPS));
        }

        return stops.toArray(new Expression.Stop[LOADING_PROGRESS_STEPS]);
    }

    private void setupLoadingLayer1(@NonNull Style loadedMapStyle) {
        loadedMapStyle.addLayerBelow(new CircleLayer(LOADING_LAYER_ID1, SOURCE_ID)
                .withProperties(
                        circleRadius(interpolate(exponential(1), get(PROPERTY_LOADING_PROGRESS1), getLoadingAnimationStops1())),
                        circleColor(Color.RED),
                        circleOpacity(interpolate(exponential(1), get(PROPERTY_LOADING_OPACITY1), getLoadingAnimationStops1()))
                )
                .withFilter(eq(get(PROPERTY_LOADING1), literal(true))), MAKI_LAYER_ID);
    }

    private Expression.Stop[] getLoadingAnimationStops1() {
        List<Expression.Stop> stops = new ArrayList<>();
        for (int i = 0; i < LOADING_PROGRESS_STEPS1; i++) {
            stops.add(stop(i, LOADING_CIRCLE_RADIUS1 * i / LOADING_PROGRESS_STEPS1));
        }

        return stops.toArray(new Expression.Stop[LOADING_PROGRESS_STEPS1]);
    }

    /**
     * This method handles click events for maki symbols.
     * <p>
     * When a maki symbol is clicked, we moved that feature to the selected state.
     * </p>
     *
     * @param screenPoint the point on screen clicked
     */
    private boolean handleClickIcon(PointF screenPoint) {
        List<Feature> features = mapboxMap.queryRenderedFeatures(screenPoint, MAKI_LAYER_ID);
        if (!features.isEmpty()) {
            String title = features.get(0).getStringProperty(PROPERTY_TITLE);
            List<Feature> featureList = featureCollection.features();
            for (int i = 0; i < Objects.requireNonNull(featureList).size(); i++)
                if (featureList.get(i).getStringProperty(PROPERTY_TITLE).equals(title))
                    setSelected(i);
            return true;
        }
        return false;
    }

    /**
     * Set a feature selected state with the ability to scroll the RecycleViewer to the provided index.
     *  @param index      the index of selected feature
     *
     */
    private void setSelected(int index) {
        if (recyclerView.getVisibility() == View.GONE) {
            recyclerView.setVisibility(View.VISIBLE);
        }

        deselectAll(false);

        Feature feature = Objects.requireNonNull(featureCollection.features()).get(index);
        selectFeature(feature);
        animateCameraToSelection(feature);
        refreshSource();
        loadMapillaryData(feature);

        recyclerView.scrollToPosition(index);
    }

    /**
     * Deselects the state of all the features
     */
    private void deselectAll(boolean hideRecycler) {
        for (Feature feature : Objects.requireNonNull(featureCollection.features())) {
            Objects.requireNonNull(feature.properties()).addProperty(PROPERTY_SELECTED, false);
        }

        if (hideRecycler) {
            recyclerView.setVisibility(View.GONE);
        }
    }

    /**
     * Selects the state of a feature
     *
     * @param feature the feature to be selected.
     */
    private void selectFeature(Feature feature) {
        Objects.requireNonNull(feature.properties()).addProperty(PROPERTY_SELECTED, true);
    }

    private Feature getSelectedFeature() {
        if (featureCollection != null) {
            for (Feature feature : Objects.requireNonNull(featureCollection.features())) {
                if (feature.getBooleanProperty(PROPERTY_SELECTED)) {
                    return feature;
                }
            }
        }

        return null;
    }

    /**
     * Animate camera to a feature.
     *
     * @param feature the feature to animate to
     */
    private void animateCameraToSelection(Feature feature, double newZoom) {
        CameraPosition cameraPosition = mapboxMap.getCameraPosition();

        if (animatorSet != null) {
            animatorSet.cancel();
        }

        animatorSet = new AnimatorSet();
        animatorSet.playTogether(
                createLatLngAnimator(cameraPosition.target, convertToLatLng(feature)),
                createZoomAnimator(cameraPosition.zoom, newZoom)
        );
        animatorSet.start();
    }

    private void animateCameraToSelection(Feature feature) {
        double zoom = feature.getNumberProperty("zoom").doubleValue();
        animateCameraToSelection(feature, zoom);
    }

    private void loadMapillaryData(Feature feature) {
        if (loadMapillaryDataTask != null) {
            loadMapillaryDataTask.cancel(true);
        }

        loadMapillaryDataTask = new LoadMapillaryDataTask(this, new Handler(), new Handler(), feature);
        loadMapillaryDataTask.execute(50);
    }

    private void setActivityStep(@ActivityStep int activityStep) {
        Feature selectedFeature = getSelectedFeature();
        double zoom = stepZoomMap.get(activityStep);
        animateCameraToSelection(selectedFeature, zoom);

        currentStep = activityStep;
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (loadMapillaryDataTask != null) {
            loadMapillaryDataTask.cancel(true);
        }
        mapView.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NotNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mapboxMap != null) {
            mapboxMap.removeOnMapClickListener(this);
        }
        mapView.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (currentStep == STEP_LOADING) {
            if (loadMapillaryDataTask != null) {
                loadMapillaryDataTask.cancel(true);
            }
            setActivityStep(STEP_INITIAL);
            deselectAll(true);
            refreshSource();
        } else {
            super.onBackPressed();
        }
    }

    private LatLng convertToLatLng(Feature feature) {
        Point symbolPoint = (Point) feature.geometry();
        return new LatLng(Objects.requireNonNull(symbolPoint).latitude(), symbolPoint.longitude());
    }

    private Animator createLatLngAnimator(LatLng currentPosition, LatLng targetPosition) {
        ValueAnimator latLngAnimator = ValueAnimator.ofObject(new LatLngEvaluator(), currentPosition, targetPosition);
        latLngAnimator.setDuration(CAMERA_ANIMATION_TIME);
        latLngAnimator.setInterpolator(new FastOutSlowInInterpolator());
        latLngAnimator.addUpdateListener(animation -> mapboxMap.moveCamera(CameraUpdateFactory.newLatLng((LatLng) animation.getAnimatedValue())));
        return latLngAnimator;
    }

    private Animator createZoomAnimator(double currentZoom, double targetZoom) {
        ValueAnimator zoomAnimator = ValueAnimator.ofFloat((float) currentZoom, (float) targetZoom);
        zoomAnimator.setDuration(CAMERA_ANIMATION_TIME);
        zoomAnimator.setInterpolator(new FastOutSlowInInterpolator());
        zoomAnimator.addUpdateListener(animation -> mapboxMap.moveCamera(CameraUpdateFactory.zoomTo((Float) animation.getAnimatedValue())));
        return zoomAnimator;
    }

    /**
     * Helper class to evaluate LatLng objects with a ValueAnimator
     */
    private static class LatLngEvaluator implements TypeEvaluator<LatLng> {
        private final LatLng latLng = new LatLng();

        @Override
        public LatLng evaluate(float fraction, LatLng startValue, LatLng endValue) {
            latLng.setLatitude(startValue.getLatitude()
                    + ((endValue.getLatitude() - startValue.getLatitude()) * fraction));
            latLng.setLongitude(startValue.getLongitude()
                    + ((endValue.getLongitude() - startValue.getLongitude()) * fraction));
            return latLng;
        }
    }

    /**
     * AsyncTask to load data from the assets folder.
     */
    private static class LoadPoiDataTask extends AsyncTask<Void, Void, FeatureCollection> {

        private final WeakReference<SymbolLayerMapillaryActivity> activityRef;

        LoadPoiDataTask(SymbolLayerMapillaryActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        @Override
        protected FeatureCollection doInBackground(Void... params) {
            SymbolLayerMapillaryActivity activity = activityRef.get();

            if (activity == null) {
                return null;
            }

            String geoJson = loadGeoJsonFromAsset(activity);
            return FeatureCollection.fromJson(geoJson);
        }

        @Override
        protected void onPostExecute(FeatureCollection featureCollection) {
            super.onPostExecute(featureCollection);
            SymbolLayerMapillaryActivity activity = activityRef.get();
            if (featureCollection == null || activity == null) {
                return;
            }
            activity.setupData(featureCollection);
        }

        static String loadGeoJsonFromAsset(Context context) {
            try {
                // Load GeoJSON file from local asset folder
                InputStream is = context.getAssets().open("sf_poi.geojson");
                int size = is.available();
                byte[] buffer = new byte[size];
                is.read(buffer);
                is.close();
                return new String(buffer, StandardCharsets.UTF_8);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }
    }

    /**
     * Async task which fetches pictures from around the POI using Mapillary services.
     * https://www.mapillary.com/developer/api-documentation/
     */
    private static class LoadMapillaryDataTask extends AsyncTask<Integer, Void, Void> {

        private WeakReference<SymbolLayerMapillaryActivity> activityRef;
        private final Handler progressHandler;
        private final Handler progressHandler1;
        private int loadingProgress;
        private int loadingProgress1;
        private float loadingOpacity;
        private float loadingOpacity1;
        private boolean loadingIncrease = true;
        private boolean loadingIncrease1 = true;
        private Feature feature;

        LoadMapillaryDataTask(SymbolLayerMapillaryActivity activity,
                              Handler progressHandler, Handler progressHandler1, Feature feature) {
            this.activityRef = new WeakReference<>(activity);
            this.progressHandler = progressHandler;
            this.progressHandler1 = progressHandler1;
            this.feature = feature;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingProgress = 0;
            loadingOpacity = 0.6f;
            loadingProgress1 = 0;
            loadingOpacity1 = 0.6f;

            setLoadingState(true);
        }

        @Override
        protected Void doInBackground(Integer... radius) {
            progressHandler.post(progressRunnable);
            progressHandler1.post(progressRunnable1);
            try {
                Thread.sleep(2500); //ensure loading visualisation
            } catch (InterruptedException exception) {
                exception.printStackTrace();
            }
            return null;
        }


        private Runnable progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (isCancelled()) {
                    setLoadingState(false);
                    return;
                }

                if (loadingIncrease) {
                    if (loadingProgress >= LOADING_PROGRESS_STEPS) {
                        loadingIncrease = false;
                    }
                } else {
                    if (loadingProgress <= 0) {
                        loadingIncrease = true;
                    }
                }

                loadingProgress = loadingIncrease ? loadingProgress + 1 : 0;
                loadingOpacity = loadingIncrease ? loadingOpacity - 0.02f : 0.6f;

                feature.addNumberProperty(PROPERTY_LOADING_PROGRESS, loadingProgress);
                feature.addNumberProperty(PROPERTY_LOADING_OPACITY, loadingOpacity);
                SymbolLayerMapillaryActivity activity = activityRef.get();
                if (activity != null) {
                    activity.refreshSource();
                }
                if (loadingProgress == 0) {
                    progressHandler.postDelayed(this, 500);
                } else {
                    progressHandler.postDelayed(this, LOADING_STEP_DURATION);
                }
            }
        };

        private Runnable progressRunnable1 = new Runnable() {
            @Override
            public void run() {
                if (isCancelled()) {
                    setLoadingState(false);
                    return;
                }

                if (loadingIncrease1) {
                    if (loadingProgress1 >= LOADING_PROGRESS_STEPS1) {
                        loadingIncrease1 = false;
                    }
                } else {
                    if (loadingProgress1 <= 0) {
                        loadingIncrease1 = true;
                    }
                }

                loadingProgress1 = loadingIncrease1 ? loadingProgress1 + 1 : 0;
                loadingOpacity1 = loadingIncrease1 ? loadingOpacity1 - 0.02f : 0.6f;

                feature.addNumberProperty(PROPERTY_LOADING_PROGRESS1, loadingProgress1);
                feature.addNumberProperty(PROPERTY_LOADING_OPACITY1, loadingOpacity1);
                SymbolLayerMapillaryActivity activity = activityRef.get();
                if (activity != null) {
                    activity.refreshSource();
                }
                if (loadingProgress1 == 1) {
                    progressHandler.postDelayed(this, 500);
                } else {
                    progressHandler.postDelayed(this, LOADING_STEP_DURATION);
                }
            }
        };

        private void setLoadingState(boolean isLoading) {
            progressHandler.removeCallbacksAndMessages(null);
            progressHandler1.removeCallbacksAndMessages(null);
            feature.addBooleanProperty(PROPERTY_LOADING, isLoading);
            feature.addBooleanProperty(PROPERTY_LOADING1, isLoading);
            SymbolLayerMapillaryActivity activity = activityRef.get();
            if (activity != null) {
                activity.refreshSource();

                if (isLoading) { //zooming to a loading state
                    activity.setActivityStep(STEP_LOADING);
                }
            }
        }
    }

}
