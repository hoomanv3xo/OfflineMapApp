# OfflineMapApp: Live Earthquake Map

An interactive Java desktop app that plots recent earthquakes on a world map. The base map is read from a **local MBTiles file**, so it needs no Internet connection to draw tiles. Earthquake data comes from the **USGS feed**, is cached on disk, and refreshes automatically while the app is open.

<!-- Add a screenshot or GIF here, for example: -->
<!-- ![OfflineMapApp screenshot](screenshot.png) -->

## Features

- **Offline base map:** tiles are loaded from `data/blankLight-1-3.mbtiles` (no tile server needed).
- **Real earthquake data:** every magnitude 2.5+ quake from the past 30 days (about 1,800 events), from the USGS GeoJSON/CSV feed.
- **Visual encoding:**
  - Dot **size** shows magnitude (bigger = stronger).
  - Dot **color** shows depth: red is shallow (under 70 km), orange is medium (70-300 km), blue is deep (over 300 km).
- **Time-range slider:** drag to show the last 1 to 30 days.
- **Magnitude filter:** raise or lower the minimum magnitude with the arrow keys.
- **Hover tooltips:** place, magnitude, depth, and time (UTC).
- **Auto-refresh:** re-downloads data in the background every 5 minutes without freezing the map.
- **Offline cache:** data is saved to `data/earthquakes.csv`, and the app falls back to it if a download fails.

## Controls

| Input | Action |
|-------|--------|
| Drag the bottom slider | Choose how many days back to show (1-30) |
| Up arrow | Raise the minimum magnitude by 0.5 |
| Down arrow | Lower the minimum magnitude by 0.5 |
| `R` | Refresh the data now |
| Mouse drag / scroll | Pan and zoom the map |
| Hover over a dot | Show quake details |

## Tech stack

- Java
- [Processing](https://processing.org/) (`PApplet`) for drawing and input
- [Unfolding Maps](http://unfoldingmaps.org/) 0.9.7 for the map, markers, and MBTiles support
- [USGS Earthquake Hazards Program](https://earthquake.usgs.gov/earthquakes/feed/) data feed

## Getting started

### Prerequisites

- A JDK (the project was developed on JDK 24 in Eclipse)
- The Unfolding Maps library and Processing core JARs on the build path
- `blankLight-1-3.mbtiles` placed in the `data/` folder

### Project layout

```
project-root/
├── OfflineMapApp.java
├── data/
│   ├── blankLight-1-3.mbtiles     # offline base map tiles
│   └── earthquakes.csv            # created automatically on first run
└── lib/                           # Unfolding Maps + Processing JARs
```

### Run

1. Import the project into Eclipse (or your IDE of choice) with the libraries on the build path.
2. Make sure the class name and the `main` method agree:

   ```java
   public class OfflineMapApp extends PApplet {
       ...
       public static void main(String[] args) {
           PApplet.main("OfflineMapApp");
       }
   }
   ```

3. Run `OfflineMapApp` as a Java application. The first run needs an Internet connection to download the earthquake data. After that the app works offline using the cached file.

If the working directory is different from the project root, the relative `data/` paths won't resolve. The app prints the working directory and cache location to the console to help debug this.

## How it works

1. **Startup:** the app uses `data/earthquakes.csv` if it is under an hour old. Otherwise it downloads a fresh copy from USGS.
2. **Markers:** each CSV row becomes an Unfolding `SimplePointMarker` with magnitude, depth, place, and time stored as properties.
3. **Filtering:** the slider and magnitude keys rebuild the visible markers from the full list.
4. **Auto-refresh:** a background thread downloads new data every 5 minutes. The main thread swaps the new data in, so the UI never blocks. If a refresh fails, the current data stays on screen.

To change the refresh interval, edit `REFRESH_MS` at the top of the class.

## Known notes

- Java 17+ may print `WARNING: A restricted method in java.lang.System has been called` from the SQLite library that reads the MBTiles file. It is harmless. To silence it, add `--enable-native-access=ALL-UNNAMED` to the VM arguments in your run configuration.
- The "days back" filter is measured from the newest quake in the data, so it still behaves sensibly when running from an old cached file.

## Ideas for next steps

- Animate quakes appearing over time
- Click a quake to pin it and show details in a side panel
- Use a dark MBTiles tile set
- Export the filtered data to CSV

## Credits

- Earthquake data: [USGS Earthquake Hazards Program](https://earthquake.usgs.gov/)
- Mapping library: [Unfolding Maps](http://unfoldingmaps.org/)
- Built with [Processing](https://processing.org/)
