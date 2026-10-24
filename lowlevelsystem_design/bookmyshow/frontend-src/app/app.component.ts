import { Component } from '@angular/core';
import { RouterOutlet, RouterLink } from '@angular/router';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink],
  template: `
    <header class="bar"><a routerLink="/" class="brand">Stubby</a><span class="city">Hyderabad</span></header>
    <main class="page"><router-outlet /></main>`,
})
export class AppComponent {}
