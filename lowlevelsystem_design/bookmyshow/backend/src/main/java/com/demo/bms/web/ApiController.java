package com.demo.bms.web;

import com.demo.bms.model.*;
import com.demo.bms.repo.*;
import com.demo.bms.service.BookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class ApiController {
    private final MovieRepository movies;
    private final ShowRepository shows;
    private final SeatRepository seats;
    private final BookingRepository bookings;
    private final BookingService service;

    public record HoldRequest(@NotEmpty List<Long> seatIds, @Email @NotBlank String email) {}

    @GetMapping("/movies") public List<Movie> movies() { return movies.findAll(); }

    @GetMapping("/movies/{id}") public Movie movie(@PathVariable Long id) {
        return movies.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/movies/{id}/shows") public List<MovieShow> showsFor(@PathVariable Long id) {
        return shows.findByMovieIdAndStartTimeAfterOrderByTheatreAscStartTimeAsc(id, LocalDateTime.now());
    }

    @GetMapping("/shows/{id}") public MovieShow show(@PathVariable Long id) {
        return shows.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/shows/{id}/seats") public List<Seat> seats(@PathVariable Long id) { return seats.findByShowIdOrderByIdAsc(id); }

    @PostMapping("/shows/{id}/hold") @ResponseStatus(HttpStatus.CREATED)
    public Booking hold(@PathVariable Long id, @Valid @RequestBody HoldRequest r) { return service.hold(id, r.seatIds(), r.email()); }

    @PostMapping("/bookings/{id}/confirm") public Booking confirm(@PathVariable Long id) { return service.confirm(id); }

    @DeleteMapping("/bookings/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long id) { service.cancelHold(id); }

    @GetMapping("/bookings/{id}") public Booking booking(@PathVariable Long id) {
        return bookings.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
