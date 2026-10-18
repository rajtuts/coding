# LOW LEVEL SYSTEM DESIGN

1. Ridesharing APP
2. BOOKMYSHOW

# Low-Level Design (LLD) Roadmap

## Phase 1: Programming foundations (1-2 weeks)

| Topic | What to learn |
|---|---|
| **OOP pillars** | Encapsulation, abstraction, inheritance, polymorphism, with real examples rather than definitions |
| **Class relationships** | Association, aggregation, composition, inheritance, dependency, and when to use each |
| **Composition vs inheritance** | Why "favor composition" is the default advice |
| **Interfaces and abstract classes** | When to program to a contract instead of an implementation |
| **Core language features** | Generics, enums, records/immutables, exceptions, collections, `equals`/`hashCode` |
| **Clean code habits** | Naming, small methods, no magic numbers, avoiding deep nesting |

## Phase 2: Design principles (1-2 weeks)

1. **SOLID:** single responsibility, open/closed, Liskov substitution, interface segregation, dependency inversion. For each one, write a violating example and then fix it.
2. **DRY, KISS, YAGNI:** knowing when *not* to abstract is as important as knowing how
3. **Law of Demeter:** avoid long chains like `a.getB().getC().doX()`
4. **Tell, don't ask:** put behavior where the data lives
5. **Dependency injection:** constructor injection, and why it makes code testable
6. **Cohesion and coupling:** aim for high cohesion and low coupling

## Phase 3: Design patterns (3-4 weeks)

Learn them in this order of how often they appear in interviews:

| Category | Patterns | Typical use |
|---|---|---|
| **Creational** | **Factory**, **Builder**, **Singleton**, Abstract Factory, Prototype | Object creation (payment methods, vehicles) |
| **Structural** | **Decorator**, **Adapter**, **Facade**, Proxy, Composite | Pricing add-ons, wrapping third-party APIs, file systems |
| **Behavioral** | **Strategy**, **Observer**, **State**, Command, Template Method, Chain of Responsibility, Iterator, Mediator | Pricing and matching rules, notifications, lifecycles, undo, approval flows |

For every pattern, be able to answer: what problem does it solve, what does the class diagram look like, and what is a case where using it is overkill.

## Phase 4: Modeling skills (2 weeks)

- **Requirement to class conversion:** underline the nouns for candidate classes and the verbs for methods
- **UML essentials:** class diagrams (the most important), sequence diagrams, state diagrams, use case diagrams
- **Choosing entities vs value objects vs services:** what holds state, what holds logic, what is immutable
- **State machines:** model lifecycles (order, ride, booking) with explicit transitions
- **Enums vs polymorphism:** when a `switch` should become a class hierarchy
- **Package structure:** model, service, repository, strategy layers

## Phase 5: Concurrency and robustness (2 weeks)

- **Thread safety basics:** race conditions, `synchronized`, `ReentrantLock`, `volatile`, atomic classes
- **Concurrent collections:** `ConcurrentHashMap`, `CopyOnWriteArrayList`, blocking queues
- **Check-then-act bugs:** double booking, overselling, duplicate processing, and fixing them with atomic operations
- **Executors and futures:** thread pools, `CompletableFuture`, timeouts
- **Deadlock and lock ordering**
- **Error handling:** custom exception hierarchy, validation at the right layer, idempotency, retries
- **Immutability:** records and defensive copies as the easiest route to thread safety

## Phase 6: Data, APIs and testing (1-2 weeks)

- **Repository pattern:** keep storage behind an interface
- **Schema design for the domain:** tables, keys, indexes derived from your classes
- **API design:** method signatures or REST endpoints, request and response shapes
- **Unit testing:** JUnit, Mockito, testing behavior through interfaces
- **Testing concurrency:** latch-based tests that fire many threads at once
- **Testability as a design check:** if a class is hard to test, its design is usually the problem

## Phase 7: Classic problems (4-6 weeks)

Practice in this order, since each adds new ideas:

| Level | Problems | Key concept practiced |
|---|---|---|
| **Easy** | Parking lot | Entities, enums, strategy for pricing |
| | Tic-tac-toe / Chess | Rules, validation, polymorphism |
| | Vending machine | State pattern |
| | Library management | Entities and relationships, search |
| **Medium** | Elevator system | State, scheduling strategy |
| | ATM | State, chain of responsibility (cash dispenser) |
| | Splitwise | Graph of balances, split strategies |
| | Notification system | Observer, strategy, retries |
| | Logger framework | Singleton, chain of responsibility, appenders |
| | LRU cache | Data structures, thread safety |
| **Hard** | **Ride sharing** | Matching strategy, state machine, concurrency |
| | BookMyShow | Seat locking, concurrency, payments |
| | Food delivery | Orders, assignment, multiple actors |
| | Rate limiter | Token bucket, per-user state, concurrency |
| | Pub/sub / message queue | Observer, threads, blocking queues |
| | In-memory file system | Composite pattern |
| | Online chess / multiplayer game | Turn management, state, events |

## The interview framework (use on every problem)

1. **Clarify requirements** (3-5 min): functional scope, what's out of scope, scale hints
2. **Identify actors and use cases** (2 min)
3. **Core entities and enums** (5 min): the nouns, with attributes
4. **Class diagram** (10 min): relationships, key methods, interfaces
5. **Apply patterns where change is likely** (5 min): strategy, state, observer, with reasons
6. **Write core code** (15-20 min): the main flow, not every getter
7. **Concurrency and edge cases** (5 min): races, failures, validation
8. **Extensibility discussion** (3 min): how a new feature slots in without edits

## What interviewers look for

| Signal | What it looks like |
|---|---|
| **Requirement discipline** | You ask before assuming, and state what you leave out |
| **Clean modeling** | Sensible classes, no god-class, no needless hierarchy |
| **Pattern judgment** | Patterns used where change is likely, not decoration |
| **Extensibility** | "To add X, I add one class and touch nothing else" |
| **Correctness under concurrency** | You spot the race condition before being asked |
| **Working code** | Compiles in your head, with sensible names and clear flow |
| **Trade-off awareness** | You explain why you chose this design over another |

## Common mistakes to avoid

1. Jumping to code before agreeing on requirements
2. Over-engineering: five patterns for a problem needing one
3. A single huge `Manager` or `System` class doing everything
4. Inheritance where composition fits better (for example `Car extends Vehicle` for every behavior difference)
5. Ignoring concurrency in booking-style problems
6. Using `double` for money
7. Hardcoding rules (prices, limits) inside methods instead of behind a strategy
8. Forgetting invalid state transitions
9. Not mentioning tests or how the design could be tested

## Resources

**Books**
- *Head First Design Patterns*: the most approachable pattern introduction
- *Clean Code* by Robert Martin: naming, functions, small classes
- *Effective Java* by Joshua Bloch: practical Java design judgment (immutability, builders, generics)
- *Refactoring* by Martin Fowler: turning bad code into good design
- *Java Concurrency in Practice*: the reference for Phase 5

**Practice**
- Refactoring Guru (free pattern catalog with diagrams and code)
- The "awesome-low-level-design" repositories on GitHub for worked problems
- Implement each classic problem yourself, then compare with someone else's solution and list the differences

## Suggested timeline

| Weeks | Focus |
|---|---|
| 1-2 | Phase 1 and SOLID from Phase 2 |
| 3-4 | Rest of Phase 2 and the creational and structural patterns |
| 5-6 | Behavioral patterns and Phase 4 (modeling and UML) |
| 7-8 | Phase 5 (concurrency) and Phase 6 |
| 9-14 | Phase 7: one problem every 2-3 days, with coded solutions |
| Ongoing | Re-solve earlier problems from scratch without looking |

## How to practice each problem

1. Set a 45-minute timer.
2. Write requirements, entities, a class diagram and the core code without looking anything up.
3. Run it, then write 3 tests, including one concurrency or edge case.
4. Compare with a reference solution and write down 2-3 things you missed.
5. Redo it a week later. Repetition is what makes the thinking automatic.

## How LLD and HLD fit together

| | Low-level design | High-level design |
|---|---|---|
| Scope | Classes, methods, patterns within one service | Services, databases, queues across the system |
| Output | Class diagram, code | Architecture diagram, data flow |
| Concerns | Extensibility, correctness, testability | Scale, availability, latency |
| Example (ride sharing) | `MatchingStrategy`, `Ride` state machine, `tryReserve()` | Location service, Kafka, geohash sharding |

The two meet at the service boundary: a service in your HLD box is a small LLD of its own. Doing the ride-sharing problem at both levels, as we have in this conversation, is one of the best ways to see how they connect.
