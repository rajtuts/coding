package com.demo.bms.model;

import jakarta.persistence.*;
import lombok.*;

@Entity @Getter @Setter @NoArgsConstructor
public class Movie {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String title, language, genre, tint;
    private int durationMins;
    public Movie(String t, String l, String g, int d, String tint) { title=t; language=l; genre=g; durationMins=d; this.tint=tint; }
}
