import { Routes } from '@angular/router';
import { MoviesComponent } from './pages/movies.component';
import { ShowsComponent } from './pages/shows.component';
import { SeatsComponent } from './pages/seats.component';
import { TicketComponent } from './pages/ticket.component';

export const routes: Routes = [
  { path: '', component: MoviesComponent },
  { path: 'movie/:id', component: ShowsComponent },
  { path: 'show/:id', component: SeatsComponent },
  { path: 'ticket/:id', component: TicketComponent },
  { path: '**', redirectTo: '' },
];
