package com.demo.bms.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

@Entity @Getter @Setter @NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"show_id", "label"}))
public class Seat {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @JsonIgnore @ManyToOne(fetch = FetchType.LAZY) private MovieShow show;
    private String label;                       // e.g. "C7"
    @Enumerated(EnumType.STRING) private SeatStatus status = SeatStatus.AVAILABLE;
    @JsonIgnore private LocalDateTime heldUntil;
    @JsonIgnore private Long bookingId;
}
