package com.demo.bms.service;

import com.demo.bms.model.*;
import com.demo.bms.repo.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor
public class BookingService {
    private final SeatRepository seats;
    private final ShowRepository shows;
    private final BookingRepository bookings;
    @Value("${bms.hold-minutes}") private long holdMinutes;

    /** Step 1: temporarily hold seats and create a PENDING booking. */
    @Transactional
    public Booking hold(Long showId, List<Long> seatIds, String email) {
        MovieShow show = shows.findById(showId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Show not found"));
        if (seatIds.size() > 6) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can book up to 6 seats");

        List<Seat> locked = seats.lockForUpdate(showId, seatIds);
        if (locked.size() != seatIds.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid seat selection");

        LocalDateTime now = LocalDateTime.now();
        for (Seat s : locked) {
            boolean heldByOther = s.getStatus() == SeatStatus.LOCKED && s.getHeldUntil().isAfter(now);
            if (s.getStatus() == SeatStatus.BOOKED || heldByOther)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat " + s.getLabel() + " was just taken. Pick another.");
        }

        Booking b = new Booking();
        b.setShow(show);
        b.setEmail(email);
        b.setExpiresAt(now.plusMinutes(holdMinutes));
        b.setAmount(show.getPrice().multiply(BigDecimal.valueOf(locked.size())));
        locked.forEach(s -> b.getSeatLabels().add(s.getLabel()));
        b = bookings.save(b);

        for (Seat s : locked) {
            s.setStatus(SeatStatus.LOCKED);
            s.setHeldUntil(b.getExpiresAt());
            s.setBookingId(b.getId());
        }
        return b;
    }

    /** Step 2: after (mock) payment succeeds, convert the hold into a confirmed booking. */
    @Transactional
    public Booking confirm(Long bookingId) {
        Booking b = bookings.findById(bookingId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
        if (b.getStatus() == BookingStatus.CONFIRMED) return b;                    // idempotent
        if (b.getStatus() != BookingStatus.PENDING || b.getExpiresAt().isBefore(LocalDateTime.now()))
            throw new ResponseStatusException(HttpStatus.GONE, "Your hold expired. Please select seats again.");

        seats.findByBookingId(bookingId).forEach(s -> { s.setStatus(SeatStatus.BOOKED); s.setHeldUntil(null); });
        b.setStatus(BookingStatus.CONFIRMED);
        return b;
    }

    @Transactional
    public void cancelHold(Long bookingId) {
        Booking b = bookings.findById(bookingId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (b.getStatus() == BookingStatus.PENDING) release(b, BookingStatus.CANCELLED);
    }

    /** Safety net: free seats whose hold timed out. */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void releaseExpired() {
        bookings.findByStatusAndExpiresAtBefore(BookingStatus.PENDING, LocalDateTime.now())
                .forEach(b -> release(b, BookingStatus.EXPIRED));
    }

    private void release(Booking b, BookingStatus status) {
        seats.findByBookingId(b.getId()).forEach(s -> {
            if (s.getStatus() == SeatStatus.LOCKED) { s.setStatus(SeatStatus.AVAILABLE); s.setHeldUntil(null); s.setBookingId(null); }
        });
        b.setStatus(status);
    }
}
