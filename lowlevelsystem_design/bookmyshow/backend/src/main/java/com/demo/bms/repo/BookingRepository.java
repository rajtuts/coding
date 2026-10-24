package com.demo.bms.repo;
import com.demo.bms.model.*;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus s, LocalDateTime t);
}
