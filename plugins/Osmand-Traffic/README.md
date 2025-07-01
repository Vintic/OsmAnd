Thanks! I’ll get started on designing an OSMAnd plugin for Android that fetches live traffic data from a JSON endpoint, uses it to influence initial route planning (favoring avoidance of heavy traffic), updates once per day or on demand, and works offline with cached data.

I’ll let you know once I have a plan and implementation details ready.


# Developing an OsmAnd Traffic Plugin for Android

**Overview:** OsmAnd (OpenStreetMap Automated Navigation Directions) does not natively support live traffic-aware routing – by default any traffic overlays are purely visual and not factored into navigation. To address this, we can create a custom OsmAnd plugin (targeting Android) that fetches live traffic data from a JSON feed and uses it to **display traffic conditions** on the map and **influence route planning**. OsmAnd supports user-created plugins, allowing us to extend its functionality. Below, we outline the approach:

## Fetching Live Traffic Data

First, the plugin will periodically fetch a JSON file from a given URL containing traffic updates. The JSON is expected to be an array of points, for example:

```json
[
  { "lat": 47.0093185, "lon": 28.8276958, "traffic": "moderate" },
  { "lat": 47.0089381, "lon": 28.8275242, "traffic": "heavy" },
  { "lat": 47.0027632, "lon": 28.8278246, "traffic": "light" }
]
```

Key steps for data handling:

* **Scheduled Updates:** The plugin can fetch this data **once a day** (e.g. using a background service or Android `AlarmManager`) or on-demand via a **manual “Update Traffic” button** in the plugin’s UI. This meets the requirement of daily/manual updates.
* **Networking:** Use an HTTP client (e.g. OkHttp or Android `HttpURLConnection`) to download the JSON. Ensure this runs **asynchronously** (in a background thread) so as not to block the UI. OsmAnd’s own code or Android libraries can facilitate this.
* **JSON Parsing:** Parse the fetched JSON (using a library like Jackson or org.json). The plugin will extract a list of traffic points, each with latitude, longitude, and a severity level (e.g. “light”, “moderate”, “heavy”).

## Offline Caching of Data

To fulfill offline functionality, the plugin should **cache the last retrieved traffic data** on the device. After a successful fetch, save the JSON (or the parsed data structure) to local storage (for example, in OsmAnd’s folder or app-specific storage). If the user is offline, the plugin can load the last known traffic dataset from this cache. This way, the map can still display traffic info and use it for routing without an active connection.

## Displaying Traffic Conditions on the Map

Next, the plugin will visually indicate traffic conditions on the OsmAnd map:

* **Custom Map Layer:** Implement a new map overlay layer (e.g. a `TrafficLayer` class) within the plugin. OsmAnd plugins can define custom map layers similar to how the GPX track layer or other overlays work. The TrafficLayer will draw markers or colored lines at the specified coordinates.
* **Markers/Icons:** For simplicity, represent each traffic point with a colored icon or circle:

  * **Heavy traffic:** Red marker (indicating severe congestion)
  * **Moderate traffic:** Orange/Yellow marker
  * **Light traffic:** Green marker
    These markers can be simple drawables or canvas circles drawn at the GPS coordinates on the map canvas. You might load small PNG icons for each category or use Android canvas drawing in the layer’s `draw()` method.
* **Map Rendering:** As OsmAnd’s map is vector-based and scalable, ensure the markers scale or remain visible at various zoom levels. The plugin can hook into OsmAnd’s rendering cycle via the custom layer to draw these markers on top of the base map.
* **Optional Road Highlighting:** If feasible, the plugin can go further by highlighting the actual road segments corresponding to traffic points. For example, find the road segment near each point and draw a colored polyline along that road section (red for heavy, etc.). This is more complex (requires mapping lat/lon to road geometry), but it provides a Google Maps-like traffic line effect. The GrenobleFuté project demonstrated this by creating a Traffic layer to “draw the road sections” in color, modeled after OsmAnd’s GPX drawing code.

With this layer enabled, the user will see a live overlay of traffic conditions on their map.

**Note:** This visual overlay does not alter OsmAnd’s map data; it’s an independent layer, so it won’t interfere with offline map functionality.

## Influencing Route Calculation (Avoiding Heavy Traffic)

The core challenge is to incorporate the traffic data into routing. OsmAnd’s routing engine doesn’t support real-time traffic by default, so we’ll employ some **tricks** to avoid congested areas during the **initial route planning** (fulfilling the requirement that dynamic re-routing isn’t necessary):

* **"Avoid Road" Mechanism:** OsmAnd has a feature to manually avoid specific roads (via context menu) which adds them to a *“non-used for routing”* list. Our plugin can leverage this internally:

  * For each traffic point marked “heavy,” determine the road segment at that location. This can be done by reverse-geocoding the lat/lon or querying OsmAnd’s map data for the nearest road. OsmAnd’s APIs or data structures (if accessible in the plugin) can help find the road ID or way.
  * Programmatically mark that road as “avoided” for routing. OsmAnd doesn’t expose a simple public API for this, but one trick is to simulate the effect of the *Avoid Road* action: essentially adding the road segment to the app’s avoid-list. This might involve calling internal OsmAnd methods or updating the preferences that store avoided roads.
  * Alternatively, define a small **avoidance radius** around the heavy traffic point. OsmAnd also allows avoiding an area by specifying a region to avoid (in newer versions, users can draw an avoid rectangle). The plugin could add a tiny avoid area (e.g. a circle or square of \~50m radius) around the congestion point to force the router to detour around that point.
* **Increased Cost or Blocking:** If modifying OsmAnd’s routing graphs is possible in the plugin, set the travel cost of passing through a heavy traffic point to a very high value (or treat it as temporarily closed). For example, if using a custom routing profile (XML), you could define a special penalty for certain coordinates or a custom tag. However, OsmAnd’s profile system doesn’t natively support location-based penalties, so directly using the avoid-list approach is simpler.
* **Route Planning:** With heavy segments marked as avoided, when the user calculates a route (start + destination), OsmAnd’s offline router will naturally try alternative roads. This avoids heavy-traffic roads **on the first route computation**, as desired. Moderate traffic points could be handled similarly with a lesser penalty (or you may choose not to avoid them but perhaps just display them).
* **Static Routing (No Dynamic Updates):** The plugin will apply the traffic avoidance only at route planning time. Once the route is computed, OsmAnd will follow it as usual. We are not continuously altering the route if traffic changes in real-time – this keeps the implementation simpler and meets the requirement of no dynamic re-routing. If the user wants to re-route with updated traffic, they can manually refresh the traffic data and recalc the route.

## Implementation Tips and Plugin Integration

* **Plugin Structure:** OsmAnd custom plugins are packaged as `.osf` (OsmAnd package) files, essentially ZIPs with JSON definitions and resources. However, for advanced functionality (like network calls and custom drawing), you will likely integrate the plugin as part of OsmAnd’s source code (in Java/Kotlin) and then build the app with this plugin included. Ensure to give the plugin a unique ID and include it in OsmAnd’s plugin manager so the user can enable/disable it.
* **Permissions:** The plugin will require internet permission (for downloading the JSON). OsmAnd already requests internet access for online features, but double-check that your plugin’s network usage fits within OsmAnd’s allowed operations.
* **UI Elements:** Add a settings menu for the plugin under **Plugins** in OsmAnd. For example, provide a toggle for “Show Traffic” (to turn the overlay on/off) and a “Update Now” button for manual refresh. The OsmAnd plugin framework allows adding such preferences and actions in the plugin’s XML/JSON config.
* **JSON Data Source:** Since the question suggests using a “random” source, you can simulate data for development. In practice, this could be replaced with a real traffic API or feed. Just ensure the JSON structure remains consistent. If the data source is large (covering many points), consider filtering to relevant region (around the user’s vicinity or along their route) to avoid performance issues drawing too many points.
* **Example Workflow:**

  1. User opens OsmAnd, enables the Traffic plugin.
  2. Plugin (if set to auto-update daily) fetches the JSON in the background (or user taps “Update Traffic”).
  3. TrafficLayer parses and caches the data, then draws colored markers on the map for each traffic point.
  4. User plans a route. The plugin intercepts the routing preparation by marking heavy traffic roads as avoided. (This could be done by hooking into the routing request or preemptively adding all heavy points to OsmAnd’s avoid list.)
  5. OsmAnd calculates the route, avoiding those segments. The route is shown on the map, and the traffic overlay lets the user see congested areas (including those they are detouring around).
  6. All navigation is offline (using offline maps and the cached traffic info). The only online requirement is the occasional JSON update.

By following this design, the plugin uses live data to improve navigation: heavy traffic areas are effectively treated as “temporary roadblocks” in routing, and the user can visually see traffic severity on the map. This solution works within OsmAnd’s offline-first paradigm – once the data is downloaded, everything (map display and routing) works offline, which is ideal for users with limited connectivity.

**References:**

* OsmAnd documentation on creating custom plugins and the context-menu *Avoid Road* feature (which marks roads as not usable for routing). This is leveraged to bypass heavy-traffic roads in route calculations.
* Community discussions confirm that default OsmAnd traffic overlays are visual-only and do not impact navigation, hence the need for our plugin.
* Prior research projects (e.g., GrenobleFuté 2016) have successfully extended OsmAnd with a traffic layer, parsing JSON data and drawing colored road sections, demonstrating the feasibility of this approach within OsmAnd’s plugin framework.




The initial implementation of OsmAnd Android plugin. Here's what it does:

### ✅ Implemented

* Downloads traffic data from a JSON URL
* Caches it locally for offline use
* Draws colored markers (red/yellow/green) on the map based on traffic severity
* Automatically loads cached data on startup
* Allows daily/manual updates via code (UI button wiring can be added next)

### 🔜 Next Step (Optional)

The plugin currently does **not yet** influence routing. For that, we'll need to hook into OsmAnd's routing system and dynamically “avoid” road segments near heavy traffic points. Let me know if you want me to proceed with that part.

### TO DO:

* A UI button for manual refresh?
* Hook into route planning to avoid "heavy" traffic points?