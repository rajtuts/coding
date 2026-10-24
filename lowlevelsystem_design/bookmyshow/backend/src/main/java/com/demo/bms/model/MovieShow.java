package com.demo.bms.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

@Entity @Table(name = "shows") @Getter @Setter @NoArgsConstructor
public class MovieShow {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.EAGER) private Movie movie;
    private String theatre, city, screen;
    private LocalDateTime startTime;
    private BigDecimal price;
}
