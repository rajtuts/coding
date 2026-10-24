package com.demo.bms.repo;
import com.demo.bms.model.*;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByShowIdOrderByIdAsc(Long showId);
    List<Seat> findByBookingId(Long bookingId);

    /** Row-level write lock (SELECT ... FOR UPDATE): two users can't grab the same seat at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.show.id = :showId and s.id in :ids order by s.id")
    List<Seat> lockForUpdate(@Param("showId") Long showId, @Param("ids") List<Long> ids);
}
