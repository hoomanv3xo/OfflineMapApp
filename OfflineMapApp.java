import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;

import processing.core.PApplet;
import processing.data.Table;
import processing.data.TableRow;
import de.fhpotsdam.unfolding.UnfoldingMap;
import de.fhpotsdam.unfolding.geo.Location;
import de.fhpotsdam.unfolding.marker.Marker;
import de.fhpotsdam.unfolding.marker.SimplePointMarker;
import de.fhpotsdam.unfolding.providers.MBTilesMapProvider;
import de.fhpotsdam.unfolding.utils.MapUtils;

/**
 * Offline base map (local MBTiles) with USGS earthquakes from the past 30 days.
 * The earthquake CSV is cached in data/earthquakes.csv. At startup the cache is
 * used if it is under an hour old, otherwise it is re-downloaded. While the app
 * is open, it re-downloads in the background every 5 minutes and updates the map.
 * If a refresh fails (for example, no Internet), the current data stays on screen.
 *
 * Controls:
 *   - Drag the slider at the bottom to choose how many days back to show (1-30)
 *   - UP / DOWN arrow keys raise / lower the minimum magnitude
 *   - R refreshes the data right now
 */
public class OfflineMapApp extends PApplet {

	public static String mbTilesString = "data/blankLight-1-3.mbtiles";

	// All earthquakes magnitude 2.5+ in the past 30 days (USGS)
	static final String QUAKE_URL =
			"https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_month.csv";
	static final String QUAKE_FILE = "data/earthquakes.csv";

	// How often to refresh while the app is open (USGS updates its feed every minute)
	static final long REFRESH_MS = 5L * 60 * 1000;

	// Layout: map on top, slider strip underneath
	static final int MAP_H = 750;
	static final int STRIP_H = 50;
	static final float SLIDER_X0 = 190;
	static final float SLIDER_X1 = 830;
	static final float SLIDER_Y = MAP_H + STRIP_H / 2f;

	static final long DAY_MS = 24L * 60 * 60 * 1000;

	UnfoldingMap map;

	ArrayList<SimplePointMarker> allMarkers = new ArrayList<SimplePointMarker>();
	float minMag = 2.5f;       // magnitude filter
	int daysBack = 30;         // time filter (1-30)
	long latestTime = 0;       // newest quake in the data (reference for "days back")
	int shownCount = 0;
	boolean draggingSlider = false;

	// Auto-refresh state (the download runs on a background thread)
	volatile Table pendingTable = null;   // set by the download thread, consumed in draw()
	volatile boolean refreshing = false;
	volatile String status = "";
	long lastRefreshStart = 0;
	String lastUpdated = "from cache";

	public void setup() {
		size(1000, MAP_H + STRIP_H);
		map = new UnfoldingMap(
				this, 0, 0, width, MAP_H,
				new MBTilesMapProvider(mbTilesString));
		MapUtils.createDefaultEventDispatcher(this, map);

		map.setZoomRange(2, 3);
		map.zoomAndPanTo(2, new Location(20f, 0f));
		map.setPanningRestriction(new Location(0f, 0f), 10000f);

		loadInitialData();
		applyFilter();
		lastRefreshStart = millis();
	}

	// Startup: use the cache if it is fresh, otherwise download (blocking, once)
	void loadInitialData() {
		File cached = new File(sketchPath(QUAKE_FILE));
		Table table = null;

		long oneHour = 60 * 60 * 1000;
		boolean stale = !cached.exists()
				|| (System.currentTimeMillis() - cached.lastModified()) > oneHour;

		try {
			if (stale) {
				println("Downloading earthquake data...");
				table = loadTable(QUAKE_URL, "header,csv");
				if (table != null) {
					saveTable(table, QUAKE_FILE);   // cache for offline use
					lastUpdated = timeNow();
				}
			}
		} catch (Exception e) {
			println("Could not download: " + e);
		}

		// Fall back to the cached file if the download failed or wasn't needed
		if (table == null && cached.exists()) {
			println("Using cached earthquake data");
			table = loadTable(cached.getAbsolutePath(), "header,csv");
		}

		if (table == null) {
			println("No earthquake data. Download " + QUAKE_URL
					+ " and save it as " + QUAKE_FILE);
			status = "No data";
			return;
		}
		buildMarkers(table);
	}

	// Turn a table of quakes into markers (replaces the existing ones)
	void buildMarkers(Table table) {
		allMarkers.clear();
		latestTime = 0;

		for (TableRow row : table.rows()) {
			float lat = row.getFloat("latitude");
			float lon = row.getFloat("longitude");
			float depth = row.getFloat("depth");
			float mag = row.getFloat("mag");
			String place = row.getString("place");
			String timeStr = row.getString("time");     // e.g. 2026-10-08T18:08:05.123Z

			long timeMs;
			try {
				timeMs = Instant.parse(timeStr).toEpochMilli();
			} catch (Exception e) {
				continue;   // skip rows with an unreadable time
			}
			latestTime = Math.max(latestTime, timeMs);

			HashMap<String, Object> props = new HashMap<String, Object>();
			props.put("place", place);
			props.put("mag", mag);
			props.put("depth", depth);
			props.put("time", timeMs);
			props.put("timeStr", timeStr.replace("T", " ").replace("Z", " UTC"));

			SimplePointMarker m = new SimplePointMarker(new Location(lat, lon), props);
			m.setRadius(map(mag, 2.5f, 8f, 3f, 25f));   // bigger = stronger
			m.setColor(depthColor(depth));
			m.setStrokeColor(color(80, 0, 0, 120));
			m.setStrokeWeight(1);
			allMarkers.add(m);
		}
		println("Loaded " + allMarkers.size() + " earthquakes");
	}

	// Rebuild the markers on the map using the current magnitude and time filters
	void applyFilter() {
		map.getDefaultMarkerManager().clearMarkers();
		shownCount = 0;
		long cutoff = latestTime - daysBack * DAY_MS;
		for (SimplePointMarker m : allMarkers) {
			float mag = ((Number) m.getProperty("mag")).floatValue();
			long t = ((Number) m.getProperty("time")).longValue();
			if (mag >= minMag && t >= cutoff) {
				map.addMarkers(m);
				shownCount++;
			}
		}
	}

	// ---- auto-refresh ------------------------------------------------------

	// Download in a background thread so the map never freezes
	void startRefresh() {
		if (refreshing) {
			return;
		}
		refreshing = true;
		status = "Refreshing...";
		new Thread() {
			public void run() {
				try {
					Table t = loadTable(QUAKE_URL, "header,csv");
					if (t != null) {
						pendingTable = t;
					} else {
						status = "Refresh failed (offline?)";
					}
				} catch (Exception e) {
					status = "Refresh failed (offline?)";
				} finally {
					refreshing = false;
				}
			}
		}.start();
	}

	// Called from draw(): apply a finished download, and start the next one when due
	void checkRefresh() {
		if (pendingTable != null) {
			Table t = pendingTable;
			pendingTable = null;
			saveTable(t, QUAKE_FILE);      // keep the offline cache current
			buildMarkers(t);
			applyFilter();
			lastUpdated = timeNow();
			status = "";
		}
		if (!refreshing && millis() - lastRefreshStart > REFRESH_MS) {
			lastRefreshStart = millis();
			startRefresh();
		}
	}

	String timeNow() {
		return nf(hour(), 2) + ":" + nf(minute(), 2) + ":" + nf(second(), 2);
	}

	// ---- input -----------------------------------------------------------

	public void keyPressed() {
		if (keyCode == UP && minMag < 7f) {
			minMag += 0.5f;
			applyFilter();
		} else if (keyCode == DOWN && minMag > 2.5f) {
			minMag -= 0.5f;
			applyFilter();
		} else if (key == 'r' || key == 'R') {
			lastRefreshStart = millis();
			startRefresh();
		}
	}

	public void mousePressed() {
		// Only start dragging if the click is in the slider strip
		if (mouseY >= MAP_H) {
			draggingSlider = true;
			updateSlider();
		}
	}

	public void mouseDragged() {
		if (draggingSlider) {
			updateSlider();
		}
	}

	public void mouseReleased() {
		draggingSlider = false;
	}

	void updateSlider() {
		float x = constrain(mouseX, SLIDER_X0, SLIDER_X1);
		int newDays = round(map(x, SLIDER_X0, SLIDER_X1, 1, 30));
		if (newDays != daysBack) {
			daysBack = newDays;
			applyFilter();
		}
	}

	// ---- drawing ---------------------------------------------------------

	// shallow = red, intermediate = orange, deep = blue
	int depthColor(float depth) {
		if (depth < 70) return color(255, 40, 40, 150);
		if (depth < 300) return color(255, 150, 0, 150);
		return color(40, 90, 255, 150);
	}

	public void draw() {
		checkRefresh();

		background(217);
		map.draw();

		// Hover tooltip (only over the map area)
		Marker hit = (mouseY < MAP_H) ? map.getFirstHitMarker(mouseX, mouseY) : null;
		if (hit != null) {
			String place = hit.getStringProperty("place");
			String label = place
					+ "\nMagnitude " + hit.getProperty("mag")
					+ "  |  Depth " + hit.getProperty("depth") + " km"
					+ "\n" + hit.getStringProperty("timeStr");
			textSize(13);
			float w = max(textWidth(place), 230) + 16;
			float x = constrain(mouseX + 12, 0, width - w);
			float y = constrain(mouseY + 12, 0, MAP_H - 62);
			fill(0, 200);
			noStroke();
			rect(x, y, w, 58, 4);
			fill(255);
			text(label, x + 8, y + 17);
		}

		drawLegend();
		drawSliderStrip();
	}

	void drawLegend() {
		fill(255, 220);
		noStroke();
		rect(10, MAP_H - 142, 215, 132, 4);
		fill(0);
		textSize(12);
		text("Last " + daysBack + " day" + (daysBack == 1 ? "" : "s"), 18, MAP_H - 124);
		text("Magnitude " + nf(minMag, 1, 1) + "+  (" + shownCount + " shown)", 18, MAP_H - 108);
		text("UP / DOWN: filter   R: refresh", 18, MAP_H - 92);
		text("Updated: " + lastUpdated, 18, MAP_H - 76);
		if (status.length() > 0) {
			text(status, 18, MAP_H - 60);
		}
		fill(depthColor(10));  ellipse(24, MAP_H - 46, 10, 10);
		fill(0);               text("Shallow (<70 km)", 36, MAP_H - 42);
		fill(depthColor(100)); ellipse(24, MAP_H - 32, 10, 10);
		fill(0);               text("Medium (70-300 km)", 36, MAP_H - 28);
		fill(depthColor(400)); ellipse(24, MAP_H - 18, 10, 10);
		fill(0);               text("Deep (300+ km)", 36, MAP_H - 14);
	}

	void drawSliderStrip() {
		// strip background
		noStroke();
		fill(30);
		rect(0, MAP_H, width, STRIP_H);

		// label
		fill(255);
		textSize(13);
		textAlign(LEFT, CENTER);
		text("Time range: last " + daysBack + (daysBack == 1 ? " day" : " days"), 16, SLIDER_Y);

		// track
		stroke(150);
		strokeWeight(4);
		line(SLIDER_X0, SLIDER_Y, SLIDER_X1, SLIDER_Y);

		// filled part of the track
		float hx = map(daysBack, 1, 30, SLIDER_X0, SLIDER_X1);
		stroke(255, 150, 0);
		line(SLIDER_X0, SLIDER_Y, hx, SLIDER_Y);

		// handle
		noStroke();
		fill(255);
		ellipse(hx, SLIDER_Y, 18, 18);

		// end labels
		fill(180);
		textSize(11);
		textAlign(CENTER, CENTER);
		text("1d", SLIDER_X0, SLIDER_Y + 17);
		text("30d", SLIDER_X1, SLIDER_Y + 17);
		textAlign(LEFT, BASELINE);   // reset for the rest of the drawing
		strokeWeight(1);
	}

	public static void main(String[] args) {
		PApplet.main("OfflineMapApp");
	}

}
