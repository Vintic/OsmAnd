// OsmAnd plugin: Live Traffic Overlay & Routing Influencer
// File: TrafficPlugin.java

package net.osmand.plus.plugins;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.AsyncTask;
import android.util.Log;

import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.views.OsmandMapLayer;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.routing.RouteCalculationParams;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class TrafficPlugin extends OsmandPlugin {
    private static final String TAG = "TrafficPlugin";
    private static final String JSON_URL = "https://example.com/traffic.json"; // Replace with real URL
    private static final String CACHE_FILE = "traffic_cache.json";

    private List<TrafficPoint> trafficPoints = new ArrayList<>();
    private TrafficLayer trafficLayer;

    @Override
    public void initApplication(OsmandApplication app) {
        super.initApplication(app);
        trafficLayer = new TrafficLayer(app);
        loadCachedData(app);
        fetchTrafficData(app);
    }

    @Override
    public OsmandMapLayer getMapLayer() {
        return trafficLayer;
    }

    private void loadCachedData(Context context) {
        try {
            File file = new File(context.getFilesDir(), CACHE_FILE);
            if (!file.exists()) return;
            FileInputStream fis = new FileInputStream(file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            parseTrafficJson(sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to load cache", e);
        }
    }

    private void fetchTrafficData(Context context) {
        new AsyncTask<Void, Void, String>() {
            protected String doInBackground(Void... voids) {
                try {
                    URL url = new URL(JSON_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    return sb.toString();
                } catch (Exception e) {
                    Log.e(TAG, "Download failed", e);
                    return null;
                }
            }

            protected void onPostExecute(String json) {
                if (json != null) {
                    try {
                        FileOutputStream fos = context.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE);
                        fos.write(json.getBytes());
                        fos.close();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to cache", e);
                    }
                    parseTrafficJson(json);
                    trafficLayer.refresh();
                }
            }
        }.execute();
    }

    private void parseTrafficJson(String json) {
        try {
            JSONArray array = new JSONArray(json);
            trafficPoints.clear();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                double lat = obj.getDouble("lat");
                double lon = obj.getDouble("lon");
                String traffic = obj.getString("traffic");
                trafficPoints.add(new TrafficPoint(lat, lon, traffic));
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse error", e);
        }
    }

    private class TrafficLayer extends OsmandMapLayer {
        private Paint heavyPaint, moderatePaint, lightPaint;
        private OsmandApplication app;

        public TrafficLayer(OsmandApplication app) {
            this.app = app;
            heavyPaint = new Paint();
            heavyPaint.setColor(Color.RED);
            heavyPaint.setAlpha(180);
            moderatePaint = new Paint();
            moderatePaint.setColor(Color.YELLOW);
            moderatePaint.setAlpha(180);
            lightPaint = new Paint();
            lightPaint.setColor(Color.GREEN);
            lightPaint.setAlpha(180);
        }

        @Override
        public void onDraw(Canvas canvas, OsmandMapTileView view) {
            for (TrafficPoint tp : trafficPoints) {
                LatLon loc = new LatLon(tp.lat, tp.lon);
                android.graphics.Point p = view.getMapPosition().getPoint(loc);
                Paint paint = getPaint(tp.traffic);
                canvas.drawCircle(p.x, p.y, 10, paint);
            }
        }

        private Paint getPaint(String level) {
            switch (level) {
                case "heavy": return heavyPaint;
                case "moderate": return moderatePaint;
                case "light": return lightPaint;
                default: return lightPaint;
            }
        }
    }

    private static class TrafficPoint {
        double lat, lon;
        String traffic;

        TrafficPoint(double lat, double lon, String traffic) {
            this.lat = lat;
            this.lon = lon;
            this.traffic = traffic;
        }
    }

    // TODO: Hook into routing to avoid heavy traffic
    // Can be done by manipulating route params or setting avoid points in initial route planning
}