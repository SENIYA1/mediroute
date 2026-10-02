# MediRoute: Emergency Ambulance Allocation System

Assigns ambulances to emergency calls automatically (priority, distance, equipment level, hospital capacity) and recalculates the whole plan whenever something changes. Includes login with role-based access and a live OpenStreetMap map.

## Tech stack
| Layer | What we used |
|---|---|
| Language / server | Java 8+, JDK built-in HTTP server (no framework, no dependencies) |
| Build | Maven (`pom.xml`, optional) |
| Frontend | HTML, CSS, JavaScript (single page) |
| Map | Leaflet 1.9.4 + OpenStreetMap tiles |
| Hospital data | OpenStreetMap via the Overpass API (fetched by the server, or by the admin's browser) |
| Auth | PBKDF2-SHA256 password hashing, HttpOnly session cookie, login throttling |
| Storage | In memory (resets when the server restarts) |

## Run
Requirements: Java 8 or newer.

    java -jar target/mediroute.jar

Open http://localhost:8080. Windows: double-click `run.bat`. Change the port with `PORT`.
**Set an admin password before sharing the server:** `$env:ADMIN_PASSWORD="Admin123"` (PowerShell) first.
Rebuild with `mvn package`, or `javac -d out src\main\java\com\mediroute\*.java` and copy `src\main\resources\static` into `out`.

## Roles
| Role | Can do |
|---|---|
| Administrator | Everything: manage users, load real hospitals for an area, reset data, plus all dispatcher actions |
| Dispatcher | See all calls, ambulances and hospitals; add and resolve calls; put ambulances and hospitals in or out of service; edit beds |
| Ambulance crew | See only their own ambulance and assigned call; go on or off duty; share live GPS; open turn-by-turn navigation; complete the trip |
| Hospital desk | See only patients heading to their hospital; update their own beds and accepting status |
| Public | Request an ambulance (own location, condition, life-threatening yes/no); see and cancel only their own requests; see hospital names and status |

Public sign-up is open and always creates a Public account. Staff accounts are created by the administrator (Users panel). Permissions are enforced on the server, not just hidden in the UI.

## Demo accounts (change or delete before real use)
| Username | Password | Role |
|---|---|---|
| admin | Admin123 (or `ADMIN_PASSWORD`) | Administrator |
| dispatcher1 | dispatch123 | Dispatcher |
| driver1 / driver2 | driver123 | Crew of A1 / A2 |
| hospital1 | hospital123 | Hospital desk (H1) |
| citizen1 | public123 | Public |

After loading a new area, hospital IDs (H1, H2...) are reassigned, so re-create hospital accounts and link them to the right hospital.

## Real hospital locations
**Automatic:** when a call is created (by staff or the public) and no real hospital is within 15 km of it, the server loads the nearest hospitals from OpenStreetMap, replaces the demo hospitals, and moves the demo fleet to that area, parking each ambulance at one of those hospitals (so they always sit on land). Every call then goes to the nearest open hospital that has a free bed. If OpenStreetMap can't be reached, the call is still created and uses the hospitals already loaded.

**Manual:** Log in as admin, pan or zoom the map to your city, click **Load hospitals here**. The browser fetches named hospitals (up to 40 nearest, skipping ones tagged `emergency=no`) from OpenStreetMap, and the server swaps them in and parks the demo ambulances at those hospitals.
Notes: OpenStreetMap has no live bed counts or specialities, so each hospital starts with 10 beds and all services. Hospital desk accounts keep beds up to date. Ambulance positions are live only when a crew shares GPS.

## How allocation works
1. Calls are handled tier by tier: Critical, Serious, Moderate, Minor.
2. A destination hospital is picked first: open, offers the service, has a free bed, nearest to the patient.
3. Ambulances are matched with the Hungarian algorithm, minimising total pickup distance for the tier.
4. Equipment (BLS < ALS < ICU) must meet the call's need; over-equipped units carry a small penalty.
5. Any event (new call, ambulance or hospital status change, beds, call resolved) recomputes the plan. GPS updates move the markers and live ETAs but do not reshuffle assignments.
Distances are straight-line (haversine); ETA assumes 0.6 km per minute.

## API (form-encoded POST, header `X-MR: 1` required on non-GET)
POST /api/login, /api/register, /api/logout, GET /api/state (filtered by role),
POST /api/emergencies, DELETE /api/emergencies/{id}, POST /api/ambulances/{id}/toggle|location,
POST /api/hospitals/{id}/toggle|beds, POST /api/region (admin), POST /api/reset (admin),
GET/POST /api/users, DELETE /api/users/{name} (admin)

## Limits and next steps
- HTTP only: browsers allow GPS on `localhost` but not on plain-HTTP LAN addresses. Deploy behind HTTPS for real use.
- In-memory data: add PostgreSQL to persist users and calls.
- Road routing (OSRM) instead of straight-line distance.
