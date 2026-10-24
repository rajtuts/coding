# Stubby – BookMyShow-style ticket booking (Spring Boot + Angular)

## Run the backend (Java 17+, Maven)
    cd backend && mvn spring-boot:run          # http://localhost:8080  (H2 console: /h2-console, jdbc:h2:mem:bms)

## Run the frontend (Node 18+)
    npx @angular/cli@17 new frontend --routing=false --style=css --skip-git
    cd frontend
    cp -r ../frontend-src/app/* src/app/       # overwrites app.component.ts, app.config.ts; adds app.routes.ts, core/, pages/
    cp ../frontend-src/styles.css src/styles.css
    ng serve                                    # http://localhost:4200

(If the CLI generated app.component.html/.css/.spec.ts, delete them — the new component is inline.)

## REST API
| Method | Path | Purpose |
|---|---|---|
| GET | /api/movies | list movies |
| GET | /api/movies/{id}/shows | upcoming shows, grouped by theatre in UI |
| GET | /api/shows/{id}/seats | seat map with status |
| POST | /api/shows/{id}/hold | {seatIds, email} → PENDING booking, seats LOCKED 5 min |
| POST | /api/bookings/{id}/confirm | mock payment → seats BOOKED |
| DELETE | /api/bookings/{id} | release hold |
| GET | /api/bookings/{id} | ticket |

## How double-booking is prevented
`SeatRepository.lockForUpdate` takes a pessimistic row lock (SELECT … FOR UPDATE) inside the `hold` transaction,
so concurrent requests for the same seat serialize; the second sees LOCKED/BOOKED and gets HTTP 409.
A scheduled job releases expired holds every 30 s.
