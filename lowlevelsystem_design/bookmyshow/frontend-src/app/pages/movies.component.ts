import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AsyncPipe } from '@angular/common';
import { Api } from '../core/api.service';

@Component({
  selector: 'app-movies',
  standalone: true,
  imports: [RouterLink, AsyncPipe],
  template: `
    <h1>Now showing</h1>
    <div class="grid">
      @for (m of movies$ | async; track m.id) {
        <a class="poster" [routerLink]="['/movie', m.id]" [style.--tint]="m.tint">
          <span class="poster-title">{{ m.title }}</span>
          <span class="poster-meta">{{ m.genre }} · {{ m.language }} · {{ m.durationMins }} min</span>
        </a>
      } @empty { <p class="muted">Loading movies…</p> }
    </div>`,
})
export class MoviesComponent {
  movies$ = inject(Api).movies();
}
