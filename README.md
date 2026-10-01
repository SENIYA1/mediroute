# MediRoute: Emergency Ambulance Allocation System

MediRoute assigns ambulances to emergency calls automatically. It treats dispatch as a constraint-based allocation problem and recalculates the whole plan whenever something changes (a new call, an ambulance going out of service, a hospital closing or filling up).

## Tech stack

| Layer | What we used |
|---|---|
| Language | Java (works on Java 8 and newer) |
| Backend server | JDK built-in HTTP server (`com.sun.net.httpserver`), no framework |
| Build tool | Maven (`pom.xml`), optional, only to rebuild the jar |
| Frontend | Plain HTML, CSS and JavaScript in a single page (`index.html`) |
| Map | Inline SVG drawn with JavaScript, no map library |
| Font | Public Sans (Google Fonts), with system-font fallback |
| Data format | JSON over a simple REST-style API |
| Storage | In memory (resets when the server restarts) |
| Version control | Git and GitHub |

There are no external libraries or databases, so there is nothing extra to install apart from Java.

## Project structure

```
mediroute/
├── pom.xml                      Maven build file (produces target/mediroute.jar)
├── run.bat                      Windows shortcut to start the app
├── README.md
├── target/mediroute.jar         Prebuilt, runnable jar
└── src/main/
    ├── java/com/mediroute/
    │   ├── Main.java            Starts the HTTP server, routes API calls, serves the UI
    │   ├── Models.java          Data classes: Ambulance, Hospital, Emergency, Assignment
    │   ├── Allocator.java       The allocation algorithm (core logic)
    │   └── Dispatch.java        Live state, re-allocation on every change, JSON output
    └── resources/static/
        └── index.html           The whole dispatch console UI
```

## How to run

Requirements: Java 8 or newer.

```
java -jar target/mediroute.jar
```

Then open http://localhost:8080. On Windows you can also double-click `run.bat`. To use a different port, set the `PORT` environment variable first.

To rebuild from source (needs a JDK and Maven):

```
mvn package
java -jar target/mediroute.jar
```

## How allocation works

1. Calls are handled tier by tier: Critical, then Serious, Moderate and Minor.
2. For each call, a destination hospital is picked first. It must be open, offer the needed service (general, cardiac, trauma, stroke or respiratory), have a free bed, and be the nearest such hospital to the patient.
3. Ambulances are matched to the calls in that tier with the **Hungarian algorithm**, which minimises the total travel distance, so the group of calls gets the best overall pairing instead of first-come-first-served.
4. Ambulance equipment level (BLS < ALS < ICU) must meet the call's need. Critical calls need ALS or better. Over-equipped ambulances get a small penalty so they stay free for harder cases.
5. If no feasible ambulance or hospital exists, the call stays unassigned and the reason is shown.
6. Any change triggers a full recalculation, and the activity log records which ambulances were swapped.

Travel time is estimated from straight-line distance, assuming 0.6 km per minute.

## API

All POST bodies are form-encoded. All responses are JSON.

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/state` | Current ambulances, hospitals, calls, assignments and log |
| POST | `/api/emergencies` | Add a call (`patient`, `kind`, `priority`, `x`, `y`) |
| DELETE | `/api/emergencies/{id}` | Resolve a call |
| POST | `/api/ambulances/{id}/toggle` | Put an ambulance in or out of service |
| POST | `/api/hospitals/{id}/toggle` | Open or close a hospital |
| POST | `/api/hospitals/{id}/beds` | Set bed count (`n`) |
| POST | `/api/reset` | Restore the demo data |

## UI features

- Call queue sorted by priority, with the assigned ambulance, hospital and ETA for each call
- City map showing ambulances, hospitals and calls (click the map to add a call)
- Switches to take ambulances and hospitals out of service, and bed controls for hospitals
- Activity log of every dispatch and re-allocation
- Responsive layout with keyboard focus styles

## Ideas for next steps

- Replace in-memory state with PostgreSQL
- Use real road distances and traffic (map routing API) instead of straight-line distance
- Add user login for dispatchers
- Add ambulance movement simulation and automated tests for `Allocator.java`
