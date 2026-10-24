package com.demo.bms.web;

import com.demo.bms.model.*;
import com.demo.bms.repo.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {
    private final MovieRepository movies;
    private final ShowRepository shows;
    private final SeatRepository seats;

    @Override public void run(String... args) {
        List<Movie> ms = movies.saveAll(List.of(
            new Movie("Monsoon Express", "Hindi", "Thriller", 142, "#c44536"),
            new Movie("The Last Lighthouse", "English", "Drama", 118, "#2a7f9e"),
            new Movie("Charminar Nights", "Telugu", "Romance", 155, "#8a5fbf"),
            new Movie("Orbit Zero", "English", "Sci-Fi", 131, "#3c9d6b")));

        String[][] theatres = {{"PVR Inorbit Mall", "Screen 3"}, {"AMB Cinemas", "Screen 1"}, {"Prasads IMAX", "Screen 2"}};
        LocalDate day = LocalDate.now().plusDays(1);
        for (Movie m : ms) for (String[] t : theatres) for (LocalTime time : List.of(LocalTime.of(14, 0), LocalTime.of(18, 30), LocalTime.of(22, 0))) {
            MovieShow s = new MovieShow();
            s.setMovie(m); s.setTheatre(t[0]); s.setScreen(t[1]); s.setCity("Hyderabad");
            s.setStartTime(LocalDateTime.of(day, time));
            s.setPrice(BigDecimal.valueOf(t[0].contains("IMAX") ? 450 : 280));
            shows.save(s);
            List<Seat> grid = new ArrayList<>();
            for (char row = 'A'; row <= 'F'; row++) for (int n = 1; n <= 10; n++) {
                Seat seat = new Seat(); seat.setShow(s); seat.setLabel("" + row + n); grid.add(seat);
            }
            seats.saveAll(grid);
        }
    }
}
