/*
 * ======================================================================
 *  BOOKMYSHOW - LOW LEVEL DESIGN (single-file, runnable)
 * ======================================================================
 *  Requires: Java 17+
 *
 *  Run demo :  java BookMyShowApp.java
 *  Run tests:  java BookMyShowApp.java test
 *  Or compile: javac BookMyShowApp.java && java BookMyShowApp [test]
 *
 *  Sections
 *   1. Entry point
 *   2. Enums, value objects, exceptions
 *   3. Entities (City, Theatre, Screen, Seat, Movie, Show, ShowSeat, User, Booking)
 *   4. Interfaces
 *   5. Strategies: pricing (decorators), payment, cancellation
 *   6. Observers and logging
 *   7. Repositories
 *   8. Services: Catalog, Query, SeatLock, Payment, Validator, Booking
 *   9. Composition root
 *  10. Demo
 *  11. Self tests
 * ======================================================================
 */

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.stream.Collectors;

/* =====================================================================
 * 1. ENTRY POINT
 * ===================================================================== */

public class BookMyShowApp {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equalsIgnoreCase("test")) SelfTests.runAll();
        else Demo.run();
    }
}

/* =====================================================================
 * 2. ENUMS, VALUE OBJECTS, EXCEPTIONS
 * ===================================================================== */

enum SeatType {
    SILVER(1.0), GOLD(1.3), PLATINUM(1.6);
    final double multiplier;
    SeatType(double m) { this.multiplier = m; }
}

enum ShowSeatStatus { AVAILABLE, LOCKED, BOOKED }

enum PaymentStatus { SUCCESS, FAILED, REFUNDED }

enum BookingStatus {
    PENDING, CONFIRMED, CANCELLED, EXPIRED;

    /** State machine: every legal transition lives here. */
    boolean canMoveTo(BookingStatus next) {
        return switch (this) {
            case PENDING   -> next == CONFIRMED || next == CANCELLED || next == EXPIRED;
            case CONFIRMED -> next == CANCELLED;
            case CANCELLED, EXPIRED -> false;
        };
    }
}

abstract class BookingException extends RuntimeException {
    protected BookingException(String m) { super(m); }
}
class InvalidRequestException       extends BookingException { InvalidRequestException(String m)      { super(m); } }
class SeatUnavailableException      extends BookingException { SeatUnavailableException(String seat)  { super("Seat not available: " + seat); } }
class BookingExpiredException       extends BookingException { BookingExpiredException(String m)      { super(m); } }
class IllegalBookingStateException  extends BookingException { IllegalBookingStateException(String m) { super(m); } }
class PaymentFailedException        extends BookingException { PaymentFailedException(String m)       { super(m); } }
class NotFoundException             extends BookingException { NotFoundException(String m)            { super(m); } }
class AccessDeniedException         extends BookingException { AccessDeniedException(String m)        { super(m); } }
class ShowConflictException         extends BookingException { ShowConflictException(String m)        { super(m); } }

record Money(BigDecimal amount, String currency) {
    static final String INR = "INR";

    Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() < 0) throw new InvalidRequestException("Money cannot be negative");
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }
    static Money inr(double v) { return new Money(BigDecimal.valueOf(v), INR); }
    static Money zero()        { return inr(0); }

    Money add(Money o) {
        if (!currency.equals(o.currency)) throw new InvalidRequestException("Currency mismatch");
        return new Money(amount.add(o.amount), currency);
    }
    Money multiply(double f) { return new Money(amount.multiply(BigDecimal.valueOf(f)), currency); }
    boolean isZero()         { return amount.signum() == 0; }
    @Override public String toString() { return currency + " " + amount; }
}

final class Ids {
    private static final AtomicLong COUNTER = new AtomicLong();
    private Ids() {}
    static String next(String prefix) { return prefix + "-" + COUNTER.incrementAndGet(); }
}

record City(String id, String name) {
    static City of(String name) { return new City(Ids.next("city"), name); }
}

record Movie(String id, String title, int durationMinutes, String language, String genre) {
    Movie {
        if (durationMinutes <= 0) throw new InvalidRequestException("Duration must be positive");
    }
    static Movie of(String title, int mins, String language, String genre) {
        return new Movie(Ids.next("movie"), title, mins, language, genre);
    }
}

record Seat(String id, String row, int number, SeatType type) implements Comparable<Seat> {
    @Override public int compareTo(Seat o) {
        int c = row.compareTo(o.row);
        return c != 0 ? c : Integer.compare(number, o.number);
    }
}

record User(String id, String name, String email, String phone) {
    static User of(String name, String email, String phone) {
        return new User(Ids.next("user"), name, email, phone);
    }
}

record Payment(String id, String bookingId, Money amount, String method, PaymentStatus status, String txnId) {}
record PaymentResult(boolean success, String txnId, String message) {}
record SeatInfo(String seatId, SeatType type, ShowSeatStatus status, Money price) {}
record CreateBookingRequest(User user, String showId, List<String> seatIds, String idempotencyKey) {}
record BookingEvent(String bookingId, BookingStatus status, Instant at, Booking booking) {}

/* =====================================================================
 * 3. ENTITIES
 * ===================================================================== */

/** Builder pattern: describes a screen's seat rows declaratively. */
class ScreenLayoutBuilder {
    private final List<Seat> seats = new ArrayList<>();

    ScreenLayoutBuilder row(String rowName, int count, SeatType type) {
        for (int i = 1; i <= count; i++) seats.add(new Seat(rowName + i, rowName, i, type));
        return this;
    }
    List<Seat> build() {
        List<Seat> copy = new ArrayList<>(seats);
        Collections.sort(copy);
        return Collections.unmodifiableList(copy);
    }
}

class Theatre {
    private final String id = Ids.next("theatre");
    private final String name;
    private final City city;
    private final List<Screen> screens = new CopyOnWriteArrayList<>();

    Theatre(String name, City city) { this.name = name; this.city = city; }

    Screen addScreen(String screenName, List<Seat> seats) {
        Screen s = new Screen(screenName, this, seats);
        screens.add(s);
        return s;
    }
    String getId() { return id; }
    String getName() { return name; }
    City getCity() { return city; }
    List<Screen> getScreens() { return screens; }
}

class Screen {
    private final String id = Ids.next("screen");
    private final String name;
    private final Theatre theatre;
    private final List<Seat> seats;

    Screen(String name, Theatre theatre, List<Seat> seats) {
        if (seats.isEmpty()) throw new InvalidRequestException("Screen needs seats");
        this.name = name; this.theatre = theatre; this.seats = seats;
    }
    String getId() { return id; }
    String getName() { return name; }
    Theatre getTheatre() { return theatre; }
    List<Seat> getSeats() { return seats; }
}

class Show {
    private final String id = Ids.next("show");
    private final Movie movie;
    private final Screen screen;
    private final Instant startTime;
    private final Instant endTime;
    private final Money basePrice;
    private final Map<String, ShowSeat> showSeats;          // fixed key set, mutable values

    Show(Movie movie, Screen screen, Instant startTime, Money basePrice) {
        this.movie = movie; this.screen = screen; this.startTime = startTime;
        this.endTime = startTime.plus(Duration.ofMinutes(movie.durationMinutes()));
        this.basePrice = basePrice;
        Map<String, ShowSeat> m = new LinkedHashMap<>();
        for (Seat s : screen.getSeats()) m.put(s.id(), new ShowSeat(s));
        this.showSeats = Collections.unmodifiableMap(m);
    }

    ShowSeat getShowSeat(String seatId) {
        ShowSeat ss = showSeats.get(seatId);
        if (ss == null) throw new NotFoundException("Seat " + seatId + " does not exist for show " + id);
        return ss;
    }
    String getId() { return id; }
    Movie getMovie() { return movie; }
    Screen getScreen() { return screen; }
    Theatre getTheatre() { return screen.getTheatre(); }
    Instant getStartTime() { return startTime; }
    Instant getEndTime() { return endTime; }
    Money getBasePrice() { return basePrice; }
    Collection<ShowSeat> getShowSeats() { return showSeats.values(); }
}

/**
 * One seat in one show. All state changes are atomic (synchronized) so that
 * check-then-act races cannot double-book a seat. Lock expiry is evaluated lazily
 * on every access, so an expired hold frees the seat even if the sweeper is late.
 */
class ShowSeat {
    private final Seat seat;
    private ShowSeatStatus status = ShowSeatStatus.AVAILABLE;
    private String holder;                 // userId who locked/booked
    private Instant lockExpiry;

    ShowSeat(Seat seat) { this.seat = seat; }
    Seat getSeat() { return seat; }

    private void refreshExpiry(Instant now) {
        if (status == ShowSeatStatus.LOCKED && !now.isBefore(lockExpiry)) {
            status = ShowSeatStatus.AVAILABLE; holder = null; lockExpiry = null;
        }
    }

    synchronized ShowSeatStatus getStatus(Instant now) { refreshExpiry(now); return status; }

    /** Atomic check-and-set. The core of double-booking prevention. */
    synchronized boolean tryLock(String userId, Instant now, Duration ttl) {
        refreshExpiry(now);
        if (status == ShowSeatStatus.BOOKED) return false;
        if (status == ShowSeatStatus.LOCKED && !userId.equals(holder)) return false;
        status = ShowSeatStatus.LOCKED; holder = userId; lockExpiry = now.plus(ttl);
        return true;
    }

    synchronized boolean holdsLock(String userId, Instant now) {
        refreshExpiry(now);
        return status == ShowSeatStatus.LOCKED && userId.equals(holder);
    }

    /** LOCKED by this user and not expired -> BOOKED. */
    synchronized boolean confirm(String userId, Instant now) {
        if (!holdsLock(userId, now)) return false;
        status = ShowSeatStatus.BOOKED; lockExpiry = null;
        return true;
    }

    /** Releases a lock or a booking held by this user (cancel / rollback). */
    synchronized void releaseBy(String userId) {
        if (userId.equals(holder) && status != ShowSeatStatus.AVAILABLE) {
            status = ShowSeatStatus.AVAILABLE; holder = null; lockExpiry = null;
        }
    }

    synchronized void releaseIfExpired(Instant now) { refreshExpiry(now); }
}

class Booking {
    private final String id = Ids.next("booking");
    private final User user;
    private final Show show;
    private final List<String> seatIds;
    private final Money amount;
    private final String idempotencyKey;
    private final Instant createdAt;
    private final Instant holdExpiresAt;
    private final AtomicBoolean paymentInProgress = new AtomicBoolean(false);

    private BookingStatus status = BookingStatus.PENDING;     // guarded by this
    private Payment payment;
    private Money refund = Money.zero();

    private Booking(Builder b) {
        this.user = b.user; this.show = b.show; this.seatIds = List.copyOf(b.seatIds);
        this.amount = b.amount; this.idempotencyKey = b.idempotencyKey;
        this.createdAt = b.createdAt; this.holdExpiresAt = b.holdExpiresAt;
    }
    static Builder builder() { return new Builder(); }

    static class Builder {
        private User user; private Show show; private List<String> seatIds = List.of();
        private Money amount; private String idempotencyKey; private Instant createdAt, holdExpiresAt;
        Builder user(User u) { this.user = u; return this; }
        Builder show(Show s) { this.show = s; return this; }
        Builder seatIds(List<String> ids) { this.seatIds = ids; return this; }
        Builder amount(Money m) { this.amount = m; return this; }
        Builder idempotencyKey(String k) { this.idempotencyKey = k; return this; }
        Builder createdAt(Instant t) { this.createdAt = t; return this; }
        Builder holdExpiresAt(Instant t) { this.holdExpiresAt = t; return this; }
        Booking build() {
            Objects.requireNonNull(user); Objects.requireNonNull(show); Objects.requireNonNull(amount);
            Objects.requireNonNull(createdAt); Objects.requireNonNull(holdExpiresAt);
            return new Booking(this);
        }
    }

    synchronized void transitionTo(BookingStatus next) {
        if (!status.canMoveTo(next))
            throw new IllegalBookingStateException("Booking " + id + ": " + status + " -> " + next + " is not allowed");
        status = next;
    }
    synchronized void confirm(Payment p) { transitionTo(BookingStatus.CONFIRMED); this.payment = p; }
    synchronized void cancel(Money refundAmount) { transitionTo(BookingStatus.CANCELLED); this.refund = refundAmount; }
    synchronized void expire() { transitionTo(BookingStatus.EXPIRED); }

    boolean beginPayment() { return paymentInProgress.compareAndSet(false, true); }
    void endPayment()      { paymentInProgress.set(false); }
    boolean isPaymentInProgress() { return paymentInProgress.get(); }

    String getId() { return id; }
    User getUser() { return user; }
    Show getShow() { return show; }
    List<String> getSeatIds() { return seatIds; }
    Money getAmount() { return amount; }
    String getIdempotencyKey() { return idempotencyKey; }
    Instant getCreatedAt() { return createdAt; }
    Instant getHoldExpiresAt() { return holdExpiresAt; }
    synchronized BookingStatus getStatus() { return status; }
    synchronized Payment getPayment() { return payment; }
    synchronized Money getRefund() { return refund; }
}

/* =====================================================================
 * 4. INTERFACES
 * ===================================================================== */

interface PricingStrategy {
    Money priceFor(Show show, Seat seat);
}

interface PaymentMethod {
    PaymentResult pay(User user, Money amount);
    default boolean refund(String txnId, Money amount) { return true; }
}

interface CancellationPolicy {
    /** @return refund amount, or throws if cancellation is no longer allowed */
    Money refundFor(Booking booking, Instant now);
}

interface BookingObserver {
    void onEvent(BookingEvent event);
}

interface CatalogRepository {
    void saveMovie(Movie m);
    Optional<Movie> findMovie(String id);
    void saveTheatre(Theatre t);
    void saveShow(Show s);
    Optional<Show> findShow(String id);
    List<Show> showsOnScreen(String screenId);
    Collection<Show> allShows();
}

interface BookingRepository {
    /** Atomic: returns the existing booking when the idempotency key is already used. */
    Booking saveOrGetExisting(Booking b);
    Optional<Booking> findById(String id);
    Optional<Booking> findByIdempotencyKey(String key);
    List<Booking> findByUser(String userId);
    Optional<Booking> findLivePendingByUserAndShow(String userId, String showId, Instant now);
    List<Booking> findExpiredPending(Instant now);
}

/* =====================================================================
 * 5. STRATEGIES
 * ===================================================================== */

/* ---- pricing: Strategy + Decorator ---- */

class BasePricing implements PricingStrategy {
    @Override public Money priceFor(Show show, Seat seat) {
        return show.getBasePrice().multiply(seat.type().multiplier);
    }
}

class WeekendPricing implements PricingStrategy {
    private final PricingStrategy inner;
    private final double multiplier;
    WeekendPricing(PricingStrategy inner, double multiplier) { this.inner = inner; this.multiplier = multiplier; }

    @Override public Money priceFor(Show show, Seat seat) {
        Money p = inner.priceFor(show, seat);
        DayOfWeek d = show.getStartTime().atZone(ZoneOffset.UTC).getDayOfWeek();
        return (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) ? p.multiply(multiplier) : p;
    }
}

class PromoPricing implements PricingStrategy {
    private final PricingStrategy inner;
    private final double percentOff;
    PromoPricing(PricingStrategy inner, double percentOff) {
        if (percentOff < 0 || percentOff > 100) throw new InvalidRequestException("Bad promo percent");
        this.inner = inner; this.percentOff = percentOff;
    }
    @Override public Money priceFor(Show show, Seat seat) {
        return inner.priceFor(show, seat).multiply(1 - percentOff / 100.0);
    }
}

/* ---- payment: Strategy + Factory ---- */

class CardPayment implements PaymentMethod {
    public PaymentResult pay(User u, Money amt) {
        Log.info("payment user=%s amount=%s via CARD", u.name(), amt);
        return new PaymentResult(true, "CARD-" + UUID.randomUUID().toString().substring(0, 8), "ok");
    }
}
class UPIPayment implements PaymentMethod {
    public PaymentResult pay(User u, Money amt) {
        Log.info("payment user=%s amount=%s via UPI", u.name(), amt);
        return new PaymentResult(true, "UPI-" + UUID.randomUUID().toString().substring(0, 8), "ok");
    }
}
class WalletPayment implements PaymentMethod {
    public PaymentResult pay(User u, Money amt) {
        Log.info("payment user=%s amount=%s via WALLET", u.name(), amt);
        return new PaymentResult(true, "WLT-" + UUID.randomUUID().toString().substring(0, 8), "ok");
    }
}

final class PaymentMethodFactory {
    private PaymentMethodFactory() {}
    static PaymentMethod of(String type) {
        if (type == null) throw new InvalidRequestException("Payment method required");
        return switch (type.toUpperCase()) {
            case "CARD"   -> new CardPayment();
            case "UPI"    -> new UPIPayment();
            case "WALLET" -> new WalletPayment();
            default -> throw new InvalidRequestException("Unsupported payment method: " + type);
        };
    }
}

/* ---- cancellation policy ---- */

class TieredRefundPolicy implements CancellationPolicy {
    @Override public Money refundFor(Booking b, Instant now) {
        Duration untilShow = Duration.between(now, b.getShow().getStartTime());
        if (untilShow.compareTo(Duration.ofHours(24)) >= 0) return b.getAmount();            // 100%
        if (untilShow.compareTo(Duration.ofHours(2))  >= 0) return b.getAmount().multiply(0.5);  // 50%
        throw new IllegalBookingStateException("Cancellation is not allowed within 2 hours of the show");
    }
}

/* =====================================================================
 * 6. OBSERVERS AND LOGGING
 * ===================================================================== */

final class Log {
    static volatile boolean quiet = false;
    private Log() {}
    static void info(String fmt, Object... args) {
        if (!quiet) System.out.println("  [log] " + String.format(fmt, args));
    }
}

class EmailNotifier implements BookingObserver {
    public void onEvent(BookingEvent e) {
        if (Log.quiet) return;
        System.out.printf("  [Email -> %s] Booking %s is %s (seats %s)%n",
                e.booking().getUser().email(), e.bookingId(), e.status(), e.booking().getSeatIds());
    }
}

class SmsNotifier implements BookingObserver {
    public void onEvent(BookingEvent e) {
        if (!Log.quiet && e.status() == BookingStatus.CONFIRMED)
            System.out.printf("  [SMS -> %s] Booking confirmed for %s. Enjoy the show!%n",
                    e.booking().getUser().phone(), e.booking().getShow().getMovie().title());
    }
}

class AnalyticsObserver implements BookingObserver {
    private final Map<BookingStatus, LongAdder> counts = new ConcurrentHashMap<>();
    public void onEvent(BookingEvent e) { counts.computeIfAbsent(e.status(), k -> new LongAdder()).increment(); }
    Map<BookingStatus, Long> snapshot() {
        Map<BookingStatus, Long> m = new EnumMap<>(BookingStatus.class);
        counts.forEach((k, v) -> m.put(k, v.sum()));
        return m;
    }
}

/* =====================================================================
 * 7. REPOSITORIES (in-memory; swap for DB-backed without touching services)
 * ===================================================================== */

class InMemoryCatalogRepository implements CatalogRepository {
    private final Map<String, Movie> movies = new ConcurrentHashMap<>();
    private final Map<String, Theatre> theatres = new ConcurrentHashMap<>();
    private final Map<String, Show> shows = new ConcurrentHashMap<>();

    public void saveMovie(Movie m)     { movies.put(m.id(), m); }
    public Optional<Movie> findMovie(String id) { return Optional.ofNullable(movies.get(id)); }
    public void saveTheatre(Theatre t) { theatres.put(t.getId(), t); }
    public void saveShow(Show s)       { shows.put(s.getId(), s); }
    public Optional<Show> findShow(String id) { return Optional.ofNullable(shows.get(id)); }
    public List<Show> showsOnScreen(String screenId) {
        return shows.values().stream().filter(s -> s.getScreen().getId().equals(screenId)).collect(Collectors.toList());
    }
    public Collection<Show> allShows() { return shows.values(); }
}

class InMemoryBookingRepository implements BookingRepository {
    private final Map<String, Booking> store = new ConcurrentHashMap<>();
    private final Map<String, Booking> byKey = new ConcurrentHashMap<>();

    public Booking saveOrGetExisting(Booking b) {
        if (b.getIdempotencyKey() != null) {
            Booking prev = byKey.putIfAbsent(b.getIdempotencyKey(), b);     // atomic
            if (prev != null) return prev;
        }
        store.put(b.getId(), b);
        return b;
    }
    public Optional<Booking> findById(String id) { return Optional.ofNullable(store.get(id)); }
    public Optional<Booking> findByIdempotencyKey(String k) {
        return k == null ? Optional.empty() : Optional.ofNullable(byKey.get(k));
    }
    public List<Booking> findByUser(String userId) {
        return store.values().stream().filter(b -> b.getUser().id().equals(userId))
                .sorted(Comparator.comparing(Booking::getCreatedAt).reversed()).collect(Collectors.toList());
    }
    public Optional<Booking> findLivePendingByUserAndShow(String userId, String showId, Instant now) {
        return store.values().stream().filter(b -> b.getUser().id().equals(userId)
                && b.getShow().getId().equals(showId)
                && b.getStatus() == BookingStatus.PENDING
                && now.isBefore(b.getHoldExpiresAt())).findFirst();
    }
    public List<Booking> findExpiredPending(Instant now) {
        return store.values().stream().filter(b -> b.getStatus() == BookingStatus.PENDING
                && !now.isBefore(b.getHoldExpiresAt())).collect(Collectors.toList());
    }
}

/* =====================================================================
 * 8. SERVICES
 * ===================================================================== */

/** Admin side: theatres, movies and scheduling shows (with overlap protection). */
class CatalogService {
    private static final Duration CLEANING_BUFFER = Duration.ofMinutes(15);
    private final CatalogRepository repo;

    CatalogService(CatalogRepository repo) { this.repo = repo; }

    Movie addMovie(String title, int mins, String language, String genre) {
        Movie m = Movie.of(title, mins, language, genre);
        repo.saveMovie(m);
        return m;
    }
    Theatre addTheatre(String name, City city) {
        Theatre t = new Theatre(name, city);
        repo.saveTheatre(t);
        return t;
    }

    /** synchronized: scheduling is a rare admin operation, so simplicity beats throughput here. */
    synchronized Show scheduleShow(Movie movie, Screen screen, Instant start, Money basePrice) {
        Show candidate = new Show(movie, screen, start, basePrice);
        for (Show existing : repo.showsOnScreen(screen.getId())) {
            boolean overlaps = candidate.getStartTime().isBefore(existing.getEndTime().plus(CLEANING_BUFFER))
                    && existing.getStartTime().isBefore(candidate.getEndTime().plus(CLEANING_BUFFER));
            if (overlaps) throw new ShowConflictException("Screen " + screen.getName() + " is busy at that time");
        }
        repo.saveShow(candidate);
        return candidate;
    }
}

/** Read side: search and seat map (CQRS-style separation from booking). */
class ShowQueryService {
    private final CatalogRepository repo;
    private final PricingStrategy pricing;
    private final Clock clock;

    ShowQueryService(CatalogRepository repo, PricingStrategy pricing, Clock clock) {
        this.repo = repo; this.pricing = pricing; this.clock = clock;
    }

    List<Movie> moviesInCity(String cityId) {
        Instant now = clock.instant();
        return repo.allShows().stream()
                .filter(s -> s.getTheatre().getCity().id().equals(cityId) && s.getStartTime().isAfter(now))
                .map(Show::getMovie).distinct()
                .sorted(Comparator.comparing(Movie::title)).collect(Collectors.toList());
    }

    List<Show> searchShows(String cityId, String movieId, LocalDate date) {
        Instant now = clock.instant();
        return repo.allShows().stream()
                .filter(s -> s.getTheatre().getCity().id().equals(cityId))
                .filter(s -> s.getMovie().id().equals(movieId))
                .filter(s -> s.getStartTime().atZone(ZoneOffset.UTC).toLocalDate().equals(date))
                .filter(s -> s.getStartTime().isAfter(now))
                .sorted(Comparator.comparing(Show::getStartTime)).collect(Collectors.toList());
    }

    List<SeatInfo> seatMap(String showId) {
        Show show = repo.findShow(showId).orElseThrow(() -> new NotFoundException("Show not found: " + showId));
        Instant now = clock.instant();
        List<SeatInfo> out = new ArrayList<>();
        for (ShowSeat ss : show.getShowSeats())
            out.add(new SeatInfo(ss.getSeat().id(), ss.getSeat().type(), ss.getStatus(now), pricing.priceFor(show, ss.getSeat())));
        return out;
    }
}

/** Temporarily holds seats for a user. All-or-nothing, with rollback on partial failure. */
class SeatLockService {
    private final Clock clock;
    private final Duration ttl;

    SeatLockService(Clock clock, Duration ttl) { this.clock = clock; this.ttl = ttl; }

    /** @return the instant the hold expires */
    Instant lockSeats(Show show, List<String> seatIds, String userId) {
        List<String> ordered = seatIds.stream().sorted().collect(Collectors.toList());   // consistent order
        Instant now = clock.instant();
        List<ShowSeat> acquired = new ArrayList<>();
        for (String id : ordered) {
            ShowSeat ss = show.getShowSeat(id);
            if (ss.tryLock(userId, now, ttl)) {
                acquired.add(ss);
            } else {
                for (ShowSeat a : acquired) a.releaseBy(userId);                         // rollback
                throw new SeatUnavailableException(id);
            }
        }
        return now.plus(ttl);
    }

    void release(Show show, List<String> seatIds, String userId) {
        for (String id : seatIds) show.getShowSeat(id).releaseBy(userId);
    }
}

class PaymentService {
    private final Function<String, PaymentMethod> resolver;
    private final int maxAttempts;
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();

    PaymentService(Function<String, PaymentMethod> resolver, int maxAttempts) {
        this.resolver = resolver; this.maxAttempts = maxAttempts;
    }

    Payment charge(Booking b, Money amount, String methodName) {
        PaymentMethod method = resolver.apply(methodName);
        String id = Ids.next("pay");
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            PaymentResult r = method.pay(b.getUser(), amount);
            if (r.success()) {
                Payment p = new Payment(id, b.getId(), amount, methodName, PaymentStatus.SUCCESS, r.txnId());
                payments.put(id, p);
                return p;
            }
            Log.info("payment attempt %d/%d failed booking=%s reason=%s", attempt, maxAttempts, b.getId(), r.message());
        }
        payments.put(id, new Payment(id, b.getId(), amount, methodName, PaymentStatus.FAILED, null));
        throw new PaymentFailedException("Payment failed after " + maxAttempts + " attempts");
    }

    boolean refund(Payment p, Money amount) {
        boolean ok = resolver.apply(p.method()).refund(p.txnId(), amount);
        if (ok) payments.put(p.id(), new Payment(p.id(), p.bookingId(), p.amount(), p.method(), PaymentStatus.REFUNDED, p.txnId()));
        return ok;
    }

    Collection<Payment> all() { return payments.values(); }
}

class BookingValidator {
    static final int MAX_SEATS_PER_BOOKING = 6;
    private final BookingRepository bookings;
    BookingValidator(BookingRepository bookings) { this.bookings = bookings; }

    void validate(CreateBookingRequest r, Show show, Instant now) {
        if (r.user() == null) throw new InvalidRequestException("User required");
        if (r.seatIds() == null || r.seatIds().isEmpty()) throw new InvalidRequestException("Select at least one seat");
        if (r.seatIds().size() > MAX_SEATS_PER_BOOKING)
            throw new InvalidRequestException("At most " + MAX_SEATS_PER_BOOKING + " seats per booking");
        if (new HashSet<>(r.seatIds()).size() != r.seatIds().size())
            throw new InvalidRequestException("Duplicate seats in request");
        if (!show.getStartTime().isAfter(now)) throw new InvalidRequestException("Show has already started");
        for (String id : r.seatIds()) show.getShowSeat(id);              // throws NotFoundException if unknown
        if (bookings.findLivePendingByUserAndShow(r.user().id(), show.getId(), now).isPresent())
            throw new InvalidRequestException("Finish or cancel your pending booking for this show first");
    }
}

/** Orchestrator: lock seats -> price -> pay -> confirm, with cancel and expiry flows. */
class BookingService {
    private final CatalogRepository catalog;
    private final BookingRepository bookings;
    private final SeatLockService locks;
    private final PricingStrategy pricing;
    private final PaymentService payments;
    private final CancellationPolicy cancellationPolicy;
    private final BookingValidator validator;
    private final Clock clock;
    private final List<BookingObserver> observers = new CopyOnWriteArrayList<>();

    BookingService(CatalogRepository catalog, BookingRepository bookings, SeatLockService locks,
                   PricingStrategy pricing, PaymentService payments, CancellationPolicy cancellationPolicy,
                   BookingValidator validator, Clock clock) {
        this.catalog = catalog; this.bookings = bookings; this.locks = locks; this.pricing = pricing;
        this.payments = payments; this.cancellationPolicy = cancellationPolicy;
        this.validator = validator; this.clock = clock;
    }

    void addObserver(BookingObserver o) { observers.add(o); }

    /* ---------- step 1: hold seats ---------- */
    Booking createBooking(CreateBookingRequest req) {
        if (req == null) throw new InvalidRequestException("Request required");
        if (req.idempotencyKey() != null) {                              // double-tap safe
            Optional<Booking> existing = bookings.findByIdempotencyKey(req.idempotencyKey());
            if (existing.isPresent()) return existing.get();
        }
        Show show = catalog.findShow(req.showId())
                .orElseThrow(() -> new NotFoundException("Show not found: " + req.showId()));
        Instant now = clock.instant();
        validator.validate(req, show, now);

        String userId = req.user().id();
        Instant expiresAt = locks.lockSeats(show, req.seatIds(), userId);       // all or nothing
        try {
            Money total = Money.zero();
            for (String seatId : req.seatIds())
                total = total.add(pricing.priceFor(show, show.getShowSeat(seatId).getSeat()));

            Booking booking = Booking.builder().user(req.user()).show(show).seatIds(req.seatIds())
                    .amount(total).idempotencyKey(req.idempotencyKey())
                    .createdAt(now).holdExpiresAt(expiresAt).build();
            Booking saved = bookings.saveOrGetExisting(booking);
            if (saved != booking) return saved;                                 // lost an idempotency race
            publish(booking);
            return booking;
        } catch (RuntimeException e) {
            locks.release(show, req.seatIds(), userId);                         // never leak a lock
            throw e;
        }
    }

    /* ---------- step 2: pay and confirm ---------- */
    Booking confirmBooking(String bookingId, String userId, String paymentMethod) {
        Booking b = get(bookingId);
        authorize(b, userId);
        if (b.getStatus() == BookingStatus.CONFIRMED) return b;                 // idempotent
        if (b.getStatus() != BookingStatus.PENDING)
            throw new IllegalBookingStateException("Booking is " + b.getStatus());
        if (!b.beginPayment()) throw new IllegalBookingStateException("Payment already in progress");

        try {
            Instant now = clock.instant();
            if (!now.isBefore(b.getHoldExpiresAt())) { expire(b); throw new BookingExpiredException("Seat hold expired"); }
            for (String id : b.getSeatIds()) {
                if (!b.getShow().getShowSeat(id).holdsLock(userId, now)) {
                    expire(b);
                    throw new BookingExpiredException("Seat " + id + " is no longer held");
                }
            }

            Payment payment = payments.charge(b, b.getAmount(), paymentMethod);   // may throw; booking stays PENDING

            List<ShowSeat> confirmed = new ArrayList<>();
            boolean ok = true;
            for (String id : b.getSeatIds()) {
                ShowSeat ss = b.getShow().getShowSeat(id);
                if (ss.confirm(userId, clock.instant())) confirmed.add(ss); else { ok = false; break; }
            }
            if (ok) {
                try { b.confirm(payment); publish(b); return b; }
                catch (IllegalBookingStateException e) { ok = false; }          // expired by sweeper meanwhile
            }
            // Hold lapsed while paying: roll back seats and refund. The customer is never charged for nothing.
            for (ShowSeat ss : confirmed) ss.releaseBy(userId);
            payments.refund(payment, payment.amount());
            try { b.expire(); publish(b); } catch (IllegalBookingStateException ignored) { }
            throw new BookingExpiredException("Hold expired during payment; amount refunded");
        } finally {
            b.endPayment();
        }
    }

    /* ---------- cancel ---------- */
    /** @return refund amount */
    Money cancelBooking(String bookingId, String userId) {
        Booking b = get(bookingId);
        authorize(b, userId);
        Instant now = clock.instant();

        switch (b.getStatus()) {
            case PENDING -> {
                if (!now.isBefore(b.getHoldExpiresAt())) { expire(b); return Money.zero(); }
                b.cancel(Money.zero());
                locks.release(b.getShow(), b.getSeatIds(), userId);
                publish(b);
                return Money.zero();
            }
            case CONFIRMED -> {
                Money refund = cancellationPolicy.refundFor(b, now);            // throws if too late
                b.cancel(refund);                                               // atomic: only one cancel wins
                locks.release(b.getShow(), b.getSeatIds(), userId);             // seats go back on sale
                if (!refund.isZero()) payments.refund(b.getPayment(), refund);
                publish(b);
                return refund;
            }
            default -> throw new IllegalBookingStateException("Booking is " + b.getStatus());
        }
    }

    /* ---------- background sweeper (scheduled every ~30s in production) ---------- */
    int expireStaleBookings() {
        int count = 0;
        for (Booking b : bookings.findExpiredPending(clock.instant())) {
            if (b.isPaymentInProgress()) continue;                              // let the payment flow finish
            try { expire(b); count++; } catch (IllegalBookingStateException ignored) { /* raced with confirm */ }
        }
        return count;
    }

    List<Booking> history(String userId) { return bookings.findByUser(userId); }

    /* ---------- helpers ---------- */
    private void expire(Booking b) {
        b.expire();
        Instant now = clock.instant();
        for (String id : b.getSeatIds()) b.getShow().getShowSeat(id).releaseIfExpired(now);
        publish(b);
    }

    private Booking get(String id) {
        return bookings.findById(id).orElseThrow(() -> new NotFoundException("Booking not found: " + id));
    }

    private void authorize(Booking b, String userId) {
        if (!b.getUser().id().equals(userId)) throw new AccessDeniedException("Not your booking");
    }

    private void publish(Booking b) {
        BookingStatus s = b.getStatus();
        Log.info("booking=%s status=%s seats=%s", b.getId(), s, b.getSeatIds());
        BookingEvent e = new BookingEvent(b.getId(), s, clock.instant(), b);
        for (BookingObserver o : observers) {
            try { o.onEvent(e); }
            catch (RuntimeException ex) { Log.info("observer %s failed: %s", o.getClass().getSimpleName(), ex); }
        }
    }
}

/* ---- test/demo helpers ---- */

class FakeClock extends Clock {
    private final AtomicReference<Instant> now;
    private final ZoneId zone;
    FakeClock(Instant start) { this(start, ZoneOffset.UTC); }
    private FakeClock(Instant start, ZoneId zone) { this.now = new AtomicReference<>(start); this.zone = zone; }
    void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return new FakeClock(now.get(), z); }
    @Override public Instant instant() { return now.get(); }
}

/* =====================================================================
 * 9. COMPOSITION ROOT (manual dependency injection)
 * ===================================================================== */

class BookMyShowSystem {
    static final Duration HOLD_TTL = Duration.ofMinutes(10);

    final FakeClock clock;
    final CatalogRepository catalogRepo = new InMemoryCatalogRepository();
    final BookingRepository bookingRepo = new InMemoryBookingRepository();
    final CatalogService catalog = new CatalogService(catalogRepo);
    final ShowQueryService query;
    final PaymentService payments;
    final AnalyticsObserver analytics = new AnalyticsObserver();
    final BookingService bookings;

    BookMyShowSystem(FakeClock clock, PricingStrategy pricing, Function<String, PaymentMethod> paymentResolver) {
        this.clock = clock;
        this.query = new ShowQueryService(catalogRepo, pricing, clock);
        this.payments = new PaymentService(paymentResolver, 2);
        this.bookings = new BookingService(catalogRepo, bookingRepo, new SeatLockService(clock, HOLD_TTL),
                pricing, payments, new TieredRefundPolicy(), new BookingValidator(bookingRepo), clock);
        bookings.addObserver(analytics);
    }

    BookMyShowSystem(FakeClock clock, PricingStrategy pricing) {
        this(clock, pricing, PaymentMethodFactory::of);
    }
}

/* =====================================================================
 * 10. DEMO
 * ===================================================================== */

class Demo {
    static void run() {
        FakeClock clock = new FakeClock(Instant.parse("2026-10-08T09:00:00Z"));
        PricingStrategy pricing = new WeekendPricing(new BasePricing(), 1.2);     // Saturday show gets +20%
        BookMyShowSystem sys = new BookMyShowSystem(clock, pricing);
        sys.bookings.addObserver(new EmailNotifier());
        sys.bookings.addObserver(new SmsNotifier());

        City hyd = City.of("Hyderabad");
        Theatre pvr = sys.catalog.addTheatre("PVR Nexus", hyd);
        Screen audi1 = pvr.addScreen("Audi 1", new ScreenLayoutBuilder()
                .row("A", 4, SeatType.PLATINUM).row("B", 4, SeatType.GOLD).row("C", 4, SeatType.SILVER).build());
        Movie movie = sys.catalog.addMovie("The Great Heist", 130, "English", "Thriller");
        Show show = sys.catalog.scheduleShow(movie, audi1, Instant.parse("2026-10-10T18:00:00Z"), Money.inr(200));

        User alice = User.of("Alice", "alice@mail.com", "9000000001");
        User bob   = User.of("Bob",   "bob@mail.com",   "9000000002");
        User carol = User.of("Carol", "carol@mail.com", "9000000003");

        System.out.println("\n== 1. Search ==");
        System.out.println("  Movies in Hyderabad: " + sys.query.moviesInCity(hyd.id()).stream().map(Movie::title).toList());
        System.out.println("  Shows on 2026-10-10: " + sys.query.searchShows(hyd.id(), movie.id(), LocalDate.of(2026, 10, 10)).size());

        System.out.println("\n== 2. Seat map ==");
        printSeatMap(sys, show);

        System.out.println("\n== 3. Alice holds A1, A2 and pays ==");
        Booking aliceBooking = sys.bookings.createBooking(new CreateBookingRequest(alice, show.getId(), List.of("A1", "A2"), "alice-1"));
        System.out.println("  Amount to pay: " + aliceBooking.getAmount() + " (hold until " + aliceBooking.getHoldExpiresAt() + ")");
        sys.bookings.confirmBooking(aliceBooking.getId(), alice.id(), "UPI");

        System.out.println("\n== 4. Bob tries A2 + B1 (A2 is taken) ==");
        try {
            sys.bookings.createBooking(new CreateBookingRequest(bob, show.getId(), List.of("A2", "B1"), null));
        } catch (SeatUnavailableException e) {
            System.out.println("  Rejected: " + e.getMessage() + " | B1 was rolled back -> "
                    + show.getShowSeat("B1").getStatus(clock.instant()));
        }

        System.out.println("\n== 5. Bob holds B1, B2 but never pays (hold expires) ==");
        Booking bobBooking = sys.bookings.createBooking(new CreateBookingRequest(bob, show.getId(), List.of("B1", "B2"), null));
        clock.advance(Duration.ofMinutes(11));
        try {
            sys.bookings.confirmBooking(bobBooking.getId(), bob.id(), "CARD");
        } catch (BookingExpiredException e) {
            System.out.println("  Rejected: " + e.getMessage());
        }

        System.out.println("\n== 6. Carol holds C1, sweeper releases it ==");
        sys.bookings.createBooking(new CreateBookingRequest(carol, show.getId(), List.of("C1"), null));
        clock.advance(Duration.ofMinutes(11));
        System.out.println("  Sweeper expired bookings: " + sys.bookings.expireStaleBookings());

        System.out.println("\n== 7. Seat map after all that ==");
        printSeatMap(sys, show);

        System.out.println("\n== 8. Alice cancels (show is >24h away -> full refund) ==");
        Money refund = sys.bookings.cancelBooking(aliceBooking.getId(), alice.id());
        System.out.println("  Refund: " + refund);

        System.out.println("\n== 9. Bob cannot cancel Alice's booking ==");
        try {
            sys.bookings.cancelBooking(aliceBooking.getId(), bob.id());
        } catch (AccessDeniedException e) {
            System.out.println("  Blocked: " + e.getMessage());
        }

        System.out.println("\n== 10. Analytics (events per status) ==");
        System.out.println("  " + sys.analytics.snapshot());
    }

    private static void printSeatMap(BookMyShowSystem sys, Show show) {
        StringBuilder sb = new StringBuilder();
        String lastRow = "";
        for (SeatInfo si : sys.query.seatMap(show.getId())) {
            String row = si.seatId().substring(0, 1);
            if (!row.equals(lastRow)) { if (sb.length() > 0) sb.append('\n'); sb.append("  Row ").append(row)
                    .append(" (").append(si.type()).append(", ").append(si.price()).append("): "); lastRow = row; }
            char c = switch (si.status()) { case AVAILABLE -> '.'; case LOCKED -> 'L'; case BOOKED -> 'X'; };
            sb.append(si.seatId()).append('[').append(c).append("] ");
        }
        System.out.println(sb);
        System.out.println("  Legend: . available   L held   X booked");
    }
}

/* =====================================================================
 * 11. SELF TESTS (run: java BookMyShowApp.java test)
 * ===================================================================== */

class SelfTests {
    interface Body { void run() throws Exception; }
    private static int passed = 0, failed = 0;

    private static void test(String name, Body body) {
        try { body.run(); passed++; System.out.println("  PASS  " + name); }
        catch (Throwable t) { failed++; System.out.println("  FAIL  " + name + "  -> " + t); }
    }
    private static void assertTrue(boolean c, String m) { if (!c) throw new AssertionError(m); }
    private static void assertEq(Object a, Object b, String m) {
        if (!Objects.equals(a, b)) throw new AssertionError(m + " expected=" + b + " actual=" + a);
    }
    private static void expectThrows(Class<? extends Throwable> type, Body body) {
        try { body.run(); }
        catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError("Expected " + type.getSimpleName() + " but nothing was thrown");
    }

    /** Fixture: one theatre, screen with A1-A5 (GOLD) and B1-B5 (SILVER), show on Fri 2026-10-09 18:00Z, base 200. */
    private static class Fx {
        final BookMyShowSystem sys; final Show show; final Movie movie; final City city; final Screen screen;
        Fx(Function<String, PaymentMethod> resolver) {
            FakeClock clock = new FakeClock(Instant.parse("2026-10-08T09:00:00Z"));
            sys = new BookMyShowSystem(clock, new BasePricing(), resolver);
            city = City.of("Hyderabad");
            Theatre t = sys.catalog.addTheatre("PVR", city);
            screen = t.addScreen("Audi 1", new ScreenLayoutBuilder().row("A", 5, SeatType.GOLD).row("B", 5, SeatType.SILVER).build());
            movie = sys.catalog.addMovie("Test Movie", 120, "English", "Drama");
            show = sys.catalog.scheduleShow(movie, screen, Instant.parse("2026-10-09T18:00:00Z"), Money.inr(200));
        }
        Fx() { this(PaymentMethodFactory::of); }
        CreateBookingRequest req(User u, String... seats) { return new CreateBookingRequest(u, show.getId(), List.of(seats), null); }
    }
    private static User user(String n) { return User.of(n, n + "@mail.com", "9000000000"); }

    static void runAll() {
        Log.quiet = true;
        System.out.println("Running self tests...\n");

        test("state machine: legal and illegal booking transitions", () -> {
            assertTrue(BookingStatus.PENDING.canMoveTo(BookingStatus.CONFIRMED), "PENDING->CONFIRMED");
            assertTrue(BookingStatus.CONFIRMED.canMoveTo(BookingStatus.CANCELLED), "CONFIRMED->CANCELLED");
            assertTrue(!BookingStatus.CONFIRMED.canMoveTo(BookingStatus.EXPIRED), "CONFIRMED->EXPIRED blocked");
            assertTrue(!BookingStatus.CANCELLED.canMoveTo(BookingStatus.CONFIRMED), "terminal");
            assertTrue(!BookingStatus.EXPIRED.canMoveTo(BookingStatus.CONFIRMED), "terminal");
        });

        test("pricing: seat type multiplier, weekend and promo decorators", () -> {
            Fx f = new Fx();
            Seat gold = f.screen.getSeats().get(0), silver = f.screen.getSeats().get(5);
            assertEq(new BasePricing().priceFor(f.show, gold), Money.inr(260), "GOLD = 200 x 1.3");
            assertEq(new BasePricing().priceFor(f.show, silver), Money.inr(200), "SILVER = 200 x 1.0");
            Show saturday = f.sys.catalog.scheduleShow(f.movie, f.screen, Instant.parse("2026-10-10T18:00:00Z"), Money.inr(200));
            PricingStrategy weekend = new WeekendPricing(new BasePricing(), 1.2);
            assertEq(weekend.priceFor(saturday, gold), Money.inr(312), "weekend surge on Saturday");
            assertEq(weekend.priceFor(f.show, gold), Money.inr(260), "no surge on Friday");
            assertEq(new PromoPricing(weekend, 10).priceFor(saturday, gold), Money.inr(280.80), "promo stacks");
        });

        test("seat lock: second user cannot take a held seat; rollback leaves nothing locked", () -> {
            Fx f = new Fx();
            f.sys.bookings.createBooking(f.req(user("a"), "A1"));
            expectThrows(SeatUnavailableException.class, () -> f.sys.bookings.createBooking(f.req(user("b"), "A2", "A1")));
            assertEq(f.show.getShowSeat("A2").getStatus(f.sys.clock.instant()), ShowSeatStatus.AVAILABLE, "A2 rolled back");
        });

        test("concurrency: 50 users race for one seat -> exactly one wins", () -> {
            Fx f = new Fx();
            ExecutorService ex = Executors.newFixedThreadPool(50);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger(), losses = new AtomicInteger();
            for (int i = 0; i < 50; i++) {
                User u = user("u" + i);
                ex.submit(() -> {
                    go.await();
                    try { f.sys.bookings.createBooking(f.req(u, "A1")); wins.incrementAndGet(); }
                    catch (SeatUnavailableException e) { losses.incrementAndGet(); }
                    return null;
                });
            }
            go.countDown(); ex.shutdown();
            assertTrue(ex.awaitTermination(10, TimeUnit.SECONDS), "finished");
            assertEq(wins.get(), 1, "winners"); assertEq(losses.get(), 49, "losers");
        });

        test("concurrency: opposite seat order [A1,A2] vs [A2,A1] -> no deadlock, one winner", () -> {
            for (int round = 0; round < 20; round++) {
                Fx f = new Fx();
                ExecutorService ex = Executors.newFixedThreadPool(2);
                CountDownLatch go = new CountDownLatch(1);
                AtomicInteger wins = new AtomicInteger();
                List<List<String>> orders = List.of(List.of("A1", "A2"), List.of("A2", "A1"));
                for (int i = 0; i < 2; i++) {
                    User u = user("u" + i); List<String> order = orders.get(i);
                    ex.submit(() -> {
                        go.await();
                        try { f.sys.bookings.createBooking(new CreateBookingRequest(u, f.show.getId(), order, null)); wins.incrementAndGet(); }
                        catch (SeatUnavailableException ignored) { }
                        return null;
                    });
                }
                go.countDown(); ex.shutdown();
                assertTrue(ex.awaitTermination(5, TimeUnit.SECONDS), "no deadlock");
                assertEq(wins.get(), 1, "exactly one winner");
            }
        });

        test("concurrency: 10 users on 10 different seats all succeed", () -> {
            Fx f = new Fx();
            List<String> all = f.screen.getSeats().stream().map(Seat::id).toList();
            ExecutorService ex = Executors.newFixedThreadPool(10);
            AtomicInteger ok = new AtomicInteger();
            for (String seat : all) {
                User u = user("u" + seat);
                ex.submit(() -> { f.sys.bookings.createBooking(f.req(u, seat)); ok.incrementAndGet(); return null; });
            }
            ex.shutdown();
            assertTrue(ex.awaitTermination(10, TimeUnit.SECONDS), "finished");
            assertEq(ok.get(), 10, "all booked");
        });

        test("full flow: hold, pay, confirm; seats BOOKED; payment recorded", () -> {
            Fx f = new Fx(); User u = user("a");
            Booking b = f.sys.bookings.createBooking(f.req(u, "A1", "B1"));
            assertEq(b.getAmount(), Money.inr(460), "260 + 200");
            f.sys.bookings.confirmBooking(b.getId(), u.id(), "UPI");
            assertEq(b.getStatus(), BookingStatus.CONFIRMED, "confirmed");
            assertEq(f.show.getShowSeat("A1").getStatus(f.sys.clock.instant()), ShowSeatStatus.BOOKED, "A1 booked");
            assertEq(f.sys.payments.all().size(), 1, "one payment");
            assertTrue(f.sys.bookings.confirmBooking(b.getId(), u.id(), "UPI") == b, "confirm is idempotent");
            assertEq(f.sys.payments.all().size(), 1, "still one payment");
        });

        test("expiry: confirm after hold TTL fails and seat is bookable by someone else", () -> {
            Fx f = new Fx(); User a = user("a"), b = user("b");
            Booking ba = f.sys.bookings.createBooking(f.req(a, "A1"));
            f.sys.clock.advance(Duration.ofMinutes(11));
            expectThrows(BookingExpiredException.class, () -> f.sys.bookings.confirmBooking(ba.getId(), a.id(), "UPI"));
            assertEq(ba.getStatus(), BookingStatus.EXPIRED, "expired");
            f.sys.bookings.createBooking(f.req(b, "A1"));        // now free
        });

        test("expiry: seat frees lazily even before the sweeper runs", () -> {
            Fx f = new Fx();
            f.sys.bookings.createBooking(f.req(user("a"), "A1"));
            f.sys.clock.advance(Duration.ofMinutes(10));
            f.sys.bookings.createBooking(f.req(user("b"), "A1"));   // succeeds without any sweep
        });

        test("sweeper: expires stale pending bookings and frees seats", () -> {
            Fx f = new Fx();
            Booking b = f.sys.bookings.createBooking(f.req(user("a"), "A1", "A2"));
            f.sys.clock.advance(Duration.ofMinutes(11));
            assertEq(f.sys.bookings.expireStaleBookings(), 1, "one expired");
            assertEq(b.getStatus(), BookingStatus.EXPIRED, "expired");
            assertEq(f.show.getShowSeat("A2").getStatus(f.sys.clock.instant()), ShowSeatStatus.AVAILABLE, "freed");
        });

        test("payment failure keeps booking PENDING; retry succeeds before expiry", () -> {
            AtomicInteger calls = new AtomicInteger();
            Fx f = new Fx(name -> (u, amt) -> {
                int n = calls.incrementAndGet();
                return n <= 2 ? new PaymentResult(false, null, "declined") : new PaymentResult(true, "T" + n, "ok");
            });
            User u = user("a");
            Booking b = f.sys.bookings.createBooking(f.req(u, "A1"));
            expectThrows(PaymentFailedException.class, () -> f.sys.bookings.confirmBooking(b.getId(), u.id(), "ANY"));
            assertEq(b.getStatus(), BookingStatus.PENDING, "still pending");
            f.sys.bookings.confirmBooking(b.getId(), u.id(), "ANY");
            assertEq(b.getStatus(), BookingStatus.CONFIRMED, "confirmed on retry");
        });

        test("payment succeeds but hold lapses during payment -> refund and seat released", () -> {
            Fx[] holder = new Fx[1];
            holder[0] = new Fx(name -> new PaymentMethod() {
                public PaymentResult pay(User u, Money amt) {
                    holder[0].sys.clock.advance(Duration.ofMinutes(11));            // slow gateway
                    return new PaymentResult(true, "SLOW-1", "ok");
                }
            });
            Fx f = holder[0]; User u = user("a");
            Booking b = f.sys.bookings.createBooking(f.req(u, "A1"));
            expectThrows(BookingExpiredException.class, () -> f.sys.bookings.confirmBooking(b.getId(), u.id(), "SLOW"));
            assertEq(f.sys.payments.all().iterator().next().status(), PaymentStatus.REFUNDED, "refunded");
            assertEq(f.show.getShowSeat("A1").getStatus(f.sys.clock.instant()), ShowSeatStatus.AVAILABLE, "released");
        });

        test("cancellation: 100% >24h, 50% 2-24h, blocked <2h", () -> {
            Fx f = new Fx(); User a = user("a"), b = user("b"), c = user("c");
            Booking b1 = f.sys.bookings.createBooking(f.req(a, "A1"));
            f.sys.bookings.confirmBooking(b1.getId(), a.id(), "UPI");
            assertEq(f.sys.bookings.cancelBooking(b1.getId(), a.id()), Money.inr(260), "full refund (33h left)");
            assertEq(f.show.getShowSeat("A1").getStatus(f.sys.clock.instant()), ShowSeatStatus.AVAILABLE, "seat back on sale");

            Booking b2 = f.sys.bookings.createBooking(f.req(b, "A2"));
            f.sys.bookings.confirmBooking(b2.getId(), b.id(), "UPI");
            f.sys.clock.advance(Duration.ofHours(10));                              // 23h left
            assertEq(f.sys.bookings.cancelBooking(b2.getId(), b.id()), Money.inr(130), "50% refund");

            Booking b3 = f.sys.bookings.createBooking(f.req(c, "A3"));
            f.sys.bookings.confirmBooking(b3.getId(), c.id(), "UPI");
            f.sys.clock.advance(Duration.ofHours(21));                              // 2h left
            f.sys.clock.advance(Duration.ofMinutes(30));                            // 1.5h left -> too late
            expectThrows(IllegalBookingStateException.class, () -> f.sys.bookings.cancelBooking(b3.getId(), c.id()));
        });

        test("cancelling twice is rejected (only one cancel wins)", () -> {
            Fx f = new Fx(); User a = user("a");
            Booking b = f.sys.bookings.createBooking(f.req(a, "A1"));
            f.sys.bookings.confirmBooking(b.getId(), a.id(), "UPI");
            f.sys.bookings.cancelBooking(b.getId(), a.id());
            expectThrows(IllegalBookingStateException.class, () -> f.sys.bookings.cancelBooking(b.getId(), a.id()));
        });

        test("idempotency: same key returns the same booking", () -> {
            Fx f = new Fx(); User u = user("a");
            CreateBookingRequest r = new CreateBookingRequest(u, f.show.getId(), List.of("A1"), "key-1");
            assertTrue(f.sys.bookings.createBooking(r) == f.sys.bookings.createBooking(r), "same booking");
        });

        test("validation: seat limits, duplicates, unknown seat, started show, second pending booking", () -> {
            Fx f = new Fx(); User u = user("a");
            expectThrows(InvalidRequestException.class, () -> f.sys.bookings.createBooking(f.req(u)));
            expectThrows(InvalidRequestException.class, () -> f.sys.bookings.createBooking(f.req(u, "A1", "A2", "A3", "A4", "A5", "B1", "B2")));
            expectThrows(InvalidRequestException.class, () -> f.sys.bookings.createBooking(f.req(u, "A1", "A1")));
            expectThrows(NotFoundException.class, () -> f.sys.bookings.createBooking(f.req(u, "Z9")));
            f.sys.bookings.createBooking(f.req(u, "A1"));
            expectThrows(InvalidRequestException.class, () -> f.sys.bookings.createBooking(f.req(u, "A2")));
            f.sys.clock.advance(Duration.ofHours(40));
            expectThrows(InvalidRequestException.class, () -> f.sys.bookings.createBooking(f.req(user("late"), "B1")));
        });

        test("authorization: other users cannot confirm or cancel", () -> {
            Fx f = new Fx(); User a = user("a"), b = user("b");
            Booking bk = f.sys.bookings.createBooking(f.req(a, "A1"));
            expectThrows(AccessDeniedException.class, () -> f.sys.bookings.confirmBooking(bk.getId(), b.id(), "UPI"));
            expectThrows(AccessDeniedException.class, () -> f.sys.bookings.cancelBooking(bk.getId(), b.id()));
        });

        test("scheduling: overlapping show on the same screen is rejected", () -> {
            Fx f = new Fx();
            expectThrows(ShowConflictException.class, () -> f.sys.catalog.scheduleShow(
                    f.movie, f.screen, Instant.parse("2026-10-09T19:00:00Z"), Money.inr(200)));
            f.sys.catalog.scheduleShow(f.movie, f.screen, Instant.parse("2026-10-09T21:00:00Z"), Money.inr(200));  // fits after buffer
        });

        test("search: by city, movie and date; seat map reflects holds", () -> {
            Fx f = new Fx();
            assertEq(f.sys.query.searchShows(f.city.id(), f.movie.id(), LocalDate.of(2026, 10, 9)).size(), 1, "one show");
            assertEq(f.sys.query.searchShows(f.city.id(), f.movie.id(), LocalDate.of(2026, 10, 11)).size(), 0, "none");
            assertEq(f.sys.query.moviesInCity(f.city.id()).size(), 1, "one movie");
            f.sys.bookings.createBooking(f.req(user("a"), "A1"));
            SeatInfo a1 = f.sys.query.seatMap(f.show.getId()).stream().filter(s -> s.seatId().equals("A1")).findFirst().get();
            assertEq(a1.status(), ShowSeatStatus.LOCKED, "A1 held");
        });

        test("observer failure never breaks a booking", () -> {
            Fx f = new Fx();
            f.sys.bookings.addObserver(e -> { throw new IllegalStateException("boom"); });
            Booking b = f.sys.bookings.createBooking(f.req(user("a"), "A1"));
            assertEq(b.getStatus(), BookingStatus.PENDING, "booking still created");
        });

        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }
}
