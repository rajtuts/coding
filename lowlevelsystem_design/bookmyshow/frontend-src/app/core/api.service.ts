import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface Movie { id: number; title: string; language: string; genre: string; durationMins: number; tint: string; }
export interface Show { id: number; movie: Movie; theatre: string; screen: string; city: string; startTime: string; price: number; }
export interface Seat { id: number; label: string; status: 'AVAILABLE' | 'LOCKED' | 'BOOKED'; }
export interface Booking { id: number; show: Show; email: string; seatLabels: string[]; amount: number; status: string; expiresAt: string; }

@Injectable({ providedIn: 'root' })
export class Api {
  private http = inject(HttpClient);
  private base = 'http://localhost:8080/api';

  movies()            { return this.http.get<Movie[]>(`${this.base}/movies`); }
  movie(id: number)   { return this.http.get<Movie>(`${this.base}/movies/${id}`); }
  shows(movieId: number) { return this.http.get<Show[]>(`${this.base}/movies/${movieId}/shows`); }
  show(id: number)    { return this.http.get<Show>(`${this.base}/shows/${id}`); }
  seats(showId: number) { return this.http.get<Seat[]>(`${this.base}/shows/${showId}/seats`); }
  hold(showId: number, seatIds: number[], email: string) {
    return this.http.post<Booking>(`${this.base}/shows/${showId}/hold`, { seatIds, email });
  }
  confirm(id: number) { return this.http.post<Booking>(`${this.base}/bookings/${id}/confirm`, {}); }
  cancel(id: number)  { return this.http.delete<void>(`${this.base}/bookings/${id}`); }
  booking(id: number) { return this.http.get<Booking>(`${this.base}/bookings/${id}`); }
}
