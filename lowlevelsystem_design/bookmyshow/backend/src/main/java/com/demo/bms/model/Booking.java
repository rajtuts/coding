package com.demo.bms.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.*;

@Entity @Getter @Setter @NoArgsConstructor
public class Booking {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.EAGER) private MovieShow show;
    private String email;
    @ElementCollection(fetch = FetchType.EAGER) private List<String> seatLabels = new ArrayList<>();
    private BigDecimal amount;
    @Enumerated(EnumType.STRING) private BookingStatus status = BookingStatus.PENDING;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime expiresAt;
}
