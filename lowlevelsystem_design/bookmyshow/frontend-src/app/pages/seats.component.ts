import { Component, inject, signal, computed, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { DatePipe, CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Api, Seat, Show, Booking } from '../core/api.service';

@Component({
  selector: 'app-seats',
  standalone: true,
  imports: [FormsModule, DatePipe, CurrencyPipe],
  template: `
    @if (show(); as s) {
      <h1>{{ s.movie.title }}</h1>
      <p class="muted">{{ s.theatre }} · {{ s.screen }} · {{ s.startTime | date:'EEE d MMM, h:mm a' }}</p>
    }

    <div class="screen">Screen this way</div>
    <div class="seatmap">
      @for (row of rows(); track row.name) {
        <div class="row">
          <span class="row-name">{{ row.name }}</span>
          @for (seat of row.seats; track seat.id) {
            <button class="seat" [class.sel]="selected().includes(seat.id)"
                    [class.taken]="seat.status !== 'AVAILABLE'"
                    [disabled]="seat.status !== 'AVAILABLE' || !!booking()"
                    [attr.aria-label]="seat.label + ' ' + seat.status.toLowerCase()"
                    (click)="toggle(seat)">{{ seat.label.slice(1) }}</button>
          }
        </div>
      }
    </div>
    <div class="legend"><span class="seat">&nbsp;</span> Available <span class="seat sel">&nbsp;</span> Yours <span class="seat taken">&nbsp;</span> Taken</div>

    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }

    <footer class="checkout">
      @if (!booking()) {
        <div>
          <strong>{{ selected().length }} seat(s)</strong>
          <span class="total">{{ total() | currency:'INR':'symbol':'1.0-0' }}</span>
        </div>
        <input type="email" placeholder="Email for your ticket" [ngModel]="email()" (ngModelChange)="email.set($event)" />
        <button class="cta" [disabled]="!selected().length || !email() || busy()" (click)="hold()">Hold seats</button>
      } @else {
        <div>
          <strong>{{ booking()!.seatLabels.join(', ') }}</strong>
          <span class="timer">Held for {{ mmss() }}</span>
        </div>
        <button class="ghost" (click)="release()">Release</button>
        <button class="cta" [disabled]="busy()" (click)="pay()">Pay {{ booking()!.amount | currency:'INR':'symbol':'1.0-0' }}</button>
      }
    </footer>`,
})
export class SeatsComponent implements OnDestroy {
  private api = inject(Api);
  private router = inject(Router);
  private showId = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));
  private tick?: ReturnType<typeof setInterval>;

  show = signal<Show | null>(null);
  seats = signal<Seat[]>([]);
  selected = signal<number[]>([]);
  email = signal('');
  booking = signal<Booking | null>(null);
  remaining = signal(0);
  error = signal('');
  busy = signal(false);

  rows = computed(() => {
    const map = new Map<string, Seat[]>();
    this.seats().forEach(s => map.set(s.label[0], [...(map.get(s.label[0]) ?? []), s]));
    return [...map].map(([name, seats]) => ({ name, seats }));
  });
  total = computed(() => this.selected().length * (this.show()?.price ?? 0));
  mmss = computed(() => `${Math.floor(this.remaining() / 60)}:${String(this.remaining() % 60).padStart(2, '0')}`);

  constructor() {
    this.api.show(this.showId).subscribe(s => this.show.set(s));
    this.load();
  }

  ngOnDestroy() { clearInterval(this.tick); }

  private load() { this.api.seats(this.showId).subscribe(s => this.seats.set(s)); }

  toggle(seat: Seat) {
    this.error.set('');
    const cur = this.selected();
    if (cur.includes(seat.id)) this.selected.set(cur.filter(i => i !== seat.id));
    else if (cur.length >= 6) this.error.set('You can book up to 6 seats.');
    else this.selected.set([...cur, seat.id]);
  }

  hold() {
    this.busy.set(true); this.error.set('');
    this.api.hold(this.showId, this.selected(), this.email()).subscribe({
      next: b => { this.booking.set(b); this.busy.set(false); this.load(); this.startTimer(b); },
      error: e => {
        this.busy.set(false);
        this.error.set(e.error?.message ?? 'Could not hold seats. Try again.');
        this.selected.set([]); this.load();           // refresh so taken seats show as taken
      },
    });
  }

  private startTimer(b: Booking) {
    clearInterval(this.tick);
    const end = new Date(b.expiresAt).getTime();
    const update = () => {
      const left = Math.max(0, Math.round((end - Date.now()) / 1000));
      this.remaining.set(left);
      if (left === 0) { clearInterval(this.tick); this.booking.set(null); this.selected.set([]); this.error.set('Your hold expired. Select seats again.'); this.load(); }
    };
    update(); this.tick = setInterval(update, 1000);
  }

  pay() {                                              // mock payment: plug in Razorpay/Stripe here
    const b = this.booking(); if (!b) return;
    this.busy.set(true);
    this.api.confirm(b.id).subscribe({
      next: c => this.router.navigate(['/ticket', c.id]),
      error: e => { this.busy.set(false); this.error.set(e.error?.message ?? 'Payment failed.'); },
    });
  }

  release() {
    const b = this.booking(); if (!b) return;
    this.api.cancel(b.id).subscribe(() => { clearInterval(this.tick); this.booking.set(null); this.selected.set([]); this.load(); });
  }
}
