import { Component, inject, signal, computed } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DatePipe, CurrencyPipe } from '@angular/common';
import { Api, Movie, Show } from '../core/api.service';

@Component({
  selector: 'app-shows',
  standalone: true,
  imports: [RouterLink, DatePipe, CurrencyPipe],
  template: `
    @if (movie(); as m) { <h1>{{ m.title }}</h1><p class="muted">{{ m.genre }} · {{ m.language }} · {{ m.durationMins }} min</p> }
    @for (t of theatres(); track t.name) {
      <section class="theatre">
        <h2>{{ t.name }}</h2>
        <div class="slots">
          @for (s of t.shows; track s.id) {
            <a class="slot" [routerLink]="['/show', s.id]">
              <strong>{{ s.startTime | date:'EEE d MMM, h:mm a' }}</strong>
              <small>{{ s.screen }} · {{ s.price | currency:'INR':'symbol':'1.0-0' }}</small>
            </a>
          }
        </div>
      </section>
    } @empty { <p class="muted">No upcoming shows.</p> }`,
})
export class ShowsComponent {
  private api = inject(Api);
  private id = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));
  movie = signal<Movie | null>(null);
  private shows = signal<Show[]>([]);
  theatres = computed(() => {
    const map = new Map<string, Show[]>();
    this.shows().forEach(s => map.set(s.theatre, [...(map.get(s.theatre) ?? []), s]));
    return [...map].map(([name, shows]) => ({ name, shows }));
  });
  constructor() {
    this.api.movie(this.id).subscribe(m => this.movie.set(m));
    this.api.shows(this.id).subscribe(s => this.shows.set(s));
  }
}
