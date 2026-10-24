package com.demo.bms.repo;
import com.demo.bms.model.MovieShow;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ShowRepository extends JpaRepository<MovieShow, Long> {
    List<MovieShow> findByMovieIdAndStartTimeAfterOrderByTheatreAscStartTimeAsc(Long movieId, LocalDateTime after);
}
