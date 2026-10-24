import { Component, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AsyncPipe, DatePipe, CurrencyPipe } from '@angular/common';
import { Api } from '../core/api.service';

@Component({
  selector: 'app-ticket',
  standalone: true,
  imports: [AsyncPipe, DatePipe, CurrencyPipe, RouterLink],
  template: `
    @if (booking$ | async; as b) {
      <article class="ticket">
        <div class="ticket-main">
          <h1>{{ b.show.movie.title }}</h1>
          <p>{{ b.show.theatre }} · {{ b.show.screen }}</p>
          <p>{{ b.show.startTime | date:'EEE d MMM, h:mm a' }}</p>
          <p class="seats">Seats {{ b.seatLabels.join(', ') }}</p>
        </div>
        <div class="ticket-stub">
          <span>Booking</span><strong>#{{ b.id }}</strong>
          <span>Paid</span><strong>{{ b.amount | currency:'INR':'symbol':'1.0-0' }}</strong>
        </div>
      </article>
      <p class="muted">Confirmation sent to {{ b.email }}.</p>
      <a routerLink="/" class="cta link">Book another movie</a>
    }`,
})
export class TicketComponent {
  booking$ = inject(Api).booking(Number(inject(ActivatedRoute).snapshot.paramMap.get('id')));
}
