# StormDeck

A weather app for Android built for weather nerds: high-resolution NEXRAD radar, worldwide radar and satellite,
live hurricane and typhoon tracking with forecast cones, and an El Niño / La Niña dashboard. No accounts, no API keys,
no ads: every source is free public data.

## What's in it

**Radar (map tab)**
- **US NEXRAD mosaic**: every US radar merged, 5-minute loop.
- **Single station**: one NEXRAD radar at full resolution (super-res reflectivity, storm-relative velocity and any
  other products the station publishes). Tap a station dot to switch; the app picks your local radar automatically.
- **Global radar**: a worldwide composite for anywhere outside the US.
- **Satellite**: GOES-East, GOES-West and Himawari, infrared or GeoColor.
- **Overlays**: live NWS watches & warnings, hurricanes with tracks and cones, and sea-surface-temperature anomalies.
- Play/pause, scrub the loop, opacity and loop-speed controls, color legend.

**Forecast**
- Current conditions with feels-like, dew point, pressure trend, visibility, UV, instability (CAPE), freezing level.
- 15-minute "rain starting in…" nowcast, 48-hour chart, 10-day outlook.
- Default forecast: NOAA's **National Blend of Models**, the calibrated multi-model blend the National Weather Service
  starts its own forecasts from. Anywhere or any hour it doesn't cover (outside the US, past its range) is filled in from
  Open-Meteo's best match automatically.
- Switch forecast model: National Blend, best match, NOAA HRRR / GFS, ECMWF, ICON, GEM, UK Met Office, JMA, Météo-France.
- NWS alerts, the local office's text forecast and the forecasters' Area Forecast Discussion (US).

**Tropics**
- Every active tropical cyclone worldwide (NHC + GDACS/JTWC): category, winds, pressure, motion, distance from you,
  and whether you're in the cone. NHC Tropical Weather Outlooks.

**El Niño / La Niña**
- NOAA's current ENSO alert status and synopsis, this week's Niño 3.4 / 1+2 / 3 / 4 anomalies, the Oceanic Niño Index
  history, a live sea-temperature anomaly map of the tropical Pacific, and what the current phase usually means.

**Extras**
- Severe weather notifications (NWS warnings / watches / advisories — your choice).
- Storm notifications: when a new storm forms, and when one's forecast track heads your way.
- Home-screen widget (resizable).
- Android Auto: full-screen animated radar on the car display, with forecast, alerts and tropics lists.
- Light and dark themes; the launcher icon switches to its dark version with the app.
- Updates itself from this repo's Releases.

## Install

Grab the latest `StormDeck-1.0.N.apk` from [Releases](../../releases) on your phone and open it. After that, the app
updates itself.

For Android Auto, sideloaded apps need *Unknown sources* turned on once in Android Auto's developer settings
(Android Auto settings → tap the version 10 times → ⋮ → Developer settings → Unknown sources).

## Data sources

| What | Source |
| --- | --- |
| Forecasts | [Open-Meteo](https://open-meteo.com) |
| US radar tiles | NWS NEXRAD via [Iowa Environmental Mesonet](https://mesonet.agron.iastate.edu) |
| Global radar | [RainViewer](https://www.rainviewer.com/api.html) (personal use) |
| Alerts, text forecasts, AFD | [NWS API](https://api.weather.gov) |
| Watches & warnings map | NOAA/NWS map services |
| Satellite, sea temps | NASA [GIBS](https://nasa-gibs.github.io/gibs-api-docs/) (GOES, Himawari, GHRSST MUR) |
| Hurricanes | [NHC](https://www.nhc.noaa.gov) and [GDACS](https://www.gdacs.org) |
| ENSO | NOAA [Climate Prediction Center](https://www.cpc.ncep.noaa.gov) |
| Base maps | [OpenFreeMap](https://openfreemap.org) / OpenStreetMap; car map tiles by CARTO |

## Building

Every push to `main` builds the APK on GitHub Actions, runs the parser tests, renders UI screenshots (published to the
`screenshots` pre-release) and publishes a release. Locally: `./gradlew assembleRelease`.

The signing key in `keystore/` is committed on purpose so any build installs over the previous one — it's for
sideloading, not an app store.
