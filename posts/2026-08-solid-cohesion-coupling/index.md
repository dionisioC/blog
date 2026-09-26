# SOLID Without the Acronym: It's Just Cohesion and Coupling

SOLID isn't really five independent principles. It's mostly two long-standing design ideas, wearing
five names.

- **High cohesion** — keep the things that change together, together.
- **Low coupling** — depend on stable abstractions, not volatile details.

Four of the five letters are _named consequences_ of those two forces, and the fifth comes with an
asterisk. Here's the first cut:

| Force        | Principles    |
| ------------ | ------------- |
| **Cohesion** | SRP, ISP      |
| **Coupling** | OCP, LSP, DIP |

It's only a first cut. ISP is contested: the textbook files it under coupling, and its section shows
why both readings are right. LSP is the asterisk. It sits under coupling because callers couple to
the _base contract_, never to your subtype, but it isn't a dial you can turn too far. It's a
correctness constraint, which makes it a _detector_ rather than a design choice.

Once you see the two forces, you stop memorizing and start deriving, including when _not_ to apply
each letter. Every one of them has a cost, and SOLID applied without judgment produces its own kind
of unmaintainable code.

Every example lives in one system, the checkout slice of a payments product:
`CheckoutService.checkout(cart)` prices the cart, charges a payment method through a gateway,
records the order and returns a result. The domain _forces_ each principle, and you'll watch them
repair each other. For each one: what it means, where it shows up, and what over-applying it costs.

---

## S — Single Responsibility

**Definition.** A class should have one reason to change. The version that actually helps: **one
reason to change means one _actor_**, one group of people who can ask for that change.

Here the broken version is a `CheckoutManager` with `total()` and `renderReceipt()`. It _feels_ like
one thing ("checkout"), but `total()` answers to Finance, which runs a 10% loyalty discount, and
`renderReceipt()` answers to Marketing, which runs a points program and prints the lines that earned
points. Both programs cover the same lines today, so they share one helper. Two actors, two reasons
to change, one class: that's the smell.

```kotlin
class CheckoutManager(private val cart: Cart) {

    fun total(): Money =                             // answers to Finance
        cart.items.sum() - loyaltyDiscount()

    fun renderReceipt(): LoyaltyReceipt =            // answers to Marketing
        LoyaltyReceipt(cart.id.value, total(), rewarded = rewardedItems())  // "points earned on…"

    private fun loyaltyDiscount(): Money =           // Finance's discount...
        rewardedItems().sum() / 10                   // 10% back on rewarded items

    private fun rewardedItems(): List<Line> = ...    // ...and Marketing's points: one list, the trap
}
```

Here's how it goes wrong. Finance asks you to stop discounting gift wrap. A developer edits
`rewardedItems()`, the obvious place, and Marketing's receipt silently stops listing gift wrap under
"points earned on," which nobody in Marketing asked for. The total moves exactly as Finance wanted
(a smaller discount, every item still charged), so the diff looks correct. Review misses it: the
class has one name and one obvious topic, but that private helper couples two departments.

**The fix is a split you already know.** In a layered app the controller changes with the _API
shape_, the service with a _business rule_, the repository with _storage_: three reasons, three
classes. Here the same instinct fires _inside_ the service layer: `PriceCalculator` for Finance,
`ReceiptFormatter` for Marketing, `CheckoutService` for the flow.

Each class gets _its own copy_ of "which lines count": Finance's skips gift wrap, Marketing's
doesn't. DRY says merge them, but they're two actors' rules that merely agreed for a while, and
merging them is what caused the bug. Martin calls this _accidental duplication_: code that looks the
same but changes for different reasons isn't really duplicated. `SrpTest` pins both behaviors: the
smell's one edit moving discount _and_ receipt, and the split's two rules disagreeing. Whenever "who
asks for changes to this?" gets two answers, you're looking at two classes wearing one name.

**The trade-off.** Fowler's _Refactoring_ names both failure modes as smells. Under-apply SRP and
you get the god class and its symptom, **divergent change**: one class edited for many unrelated
reasons. Over-apply it and you get **shotgun surgery**: one logical change spread across ten tiny
files, because you scattered things that change together. The dial is **cohesion**. Split by "these
change for different reasons," never by "this method feels different."

> If you remember one thing: SRP is the **cohesion** force. Too little separation and you get the
> god class; too much and you get shotgun surgery. The question is never "how small can this class
> be," it's "do these parts change for the same reason?"

---

## O — Open/Closed

**Definition.** A class should be _open for extension, closed for modification_: you add behavior by
adding a class, not by editing an existing, tested one. The enemy is the `if/else` that grows a
branch with every new payment method:

```kotlin
// Every new payment method = reopen this function and risk the branches already here.
fun charge(type: String, amount: Money): PaymentResult {
    if (type == "card") {
        ...
    } else if (type == "paypal") {
        ...
    } else if (type == "bizum") {
        ...
    }   // <- edit working code, again
    ...
}
```

Polymorphism buys you OCP: depend on an abstraction, and add an _implementation_ instead of a
_branch_.

```kotlin
interface PaymentMethod {
    fun charge(order: OrderId, amount: Money): PaymentResult
}   // closed

class CardPayment : PaymentMethod { ... }
class PaypalPayment : PaymentMethod { ... }
class BizumPayment : PaymentMethod { ... }        // adding one = a NEW file
```

New behavior is now a new file, _almost_. Something still maps `"bizum"` to `BizumPayment`, and
you'll add that line; here it's `PaymentMethodRegistry`, wired in the composition root at the end.
OCP doesn't delete the choice, it _concentrates_ it: out of tested business logic, into one
registration line with no logic to break. _Closed for modification_ never meant "zero edits
anywhere," only "no edits where the behavior lives."

That pays off because the _axis of variation_ was known: you expected new payment methods. Where the
opposite holds, you want the opposite tool.

**The inverse case: closed variation.** `PaymentResult` is what `checkout()` and every
`PaymentMethod.charge()` return:

```kotlin
sealed interface PaymentResult
data class Approved(val txn: TxnId, val amount: Money) : PaymentResult
data class Declined(val reason: String)               : PaymentResult
data object Timeout                                   : PaymentResult
data class Conflict(val reason: String)               : PaymentResult   // paid already, other terms

fun record(result: PaymentResult) = when (result) {
    is Approved -> ...
    is Declined -> ...
    Timeout     -> ...
    is Conflict -> ...  // add a variant → this 'when' stops compiling
}
```

OCP wants a new variant to touch nothing. A sealed type wants a new variant to _break every
exhaustive `when` at compile time_, because for a closed set you own, like an order's states or a
payment's outcomes, a silently unhandled case is the bug. Only an exhaustive `when` gets that
protection: an `else` branch opts out, and so does an `if (result is Approved)`. That's why
`CheckoutService`, which decides whether an outcome leaves an order behind, decides with a `when`.
(Java has the same pair: `sealed` types in 17, JEP 409, and the exhaustive pattern `switch` in 21,
JEP 441.)

Payment _methods_ are an open set anyone may extend: OCP and a registry. Payment _results_ are a
closed set you define: a sealed type. One domain, both answers; choosing per axis is the judgment.
The trade even has a name, the _expression problem_ (Philip Wadler, 1998): an open interface makes a
new variant cheap and a new operation expensive, because every implementation has to grow the
method, and a sealed type flips both. The LSP section shows the expensive direction, when `refund`
gets bolted onto every `PaymentMethod`.

**The trade-off.** OCP up front is indirection on a guess: **premature abstraction (YAGNI)**. You
get an interface with one implementation forever, a plugin system for plugins that never arrive, and
readers chasing the interface to find where the work happens. **Wait for the second case**: that's
when OCP starts paying for the indirection instead of just charging you for it.

> If you remember one thing: OCP is a **coupling** principle. It decouples _what varies_ (the
> implementations) from _what's stable_ (the code that uses them). Add a class, don't edit one. But
> don't add the interface before the second thing needs it, and when the set is closed, invert the
> whole idea and let a sealed type break every exhaustive `when` on purpose.

---

## L — Liskov Substitution

**Definition.** A subtype must be usable anywhere its base type is expected, through a base
reference, with no surprises (Liskov & Wing's _behavioral subtyping_, 1994). The reframing that
matters: **`extends` isn't a code-sharing mechanism, it's a published claim**, "every promise the
parent makes, I keep."

The expensive promises aren't in method signatures. They're properties that hold for an object's
whole lifetime, like "balance is never negative" or "the captured amount never exceeds the
authorized amount." Callers assume them without checking, which is their whole value and why
breaking one costs so much. Broken promises come in two forms, and they fail in opposite ways.

**Form 1 — the silent wrong answer.** Our system can issue store credit (it's where gift-card
refunds land, as you'll see):

```kotlin
open class StoreCredit {
    protected var credit: Money = Money(0)   // the promise: credit >= Money(0), always
    fun balance(): Money = credit            // the promise, observable by every caller
    fun topUp(amount: Money) {
        require(amount >= Money(0))          // every way in guards the promise
        credit += amount
    }
    open fun redeem(amount: Money) {
        require(amount >= Money(0))
        if (amount > credit) throw InsufficientCreditException()
        credit -= amount
    }
}

class VipStoreCredit : StoreCredit() {       // "let VIPs spend past their balance"
    override fun redeem(amount: Money) {
        require(amount >= Money(0))          // same guard as the parent...
        credit -= amount                     // ...but the promise is gone: no exception, just debt
    }
}
```

Callers written against `StoreCredit` assume `balance()` never goes negative, after _any_ sequence
of calls: reconciliation, the balance the app shows, the liability line Finance reports. Hand them a
`VipStoreCredit` and they're all wrong at once, with no exception, no crash, and not one changed
line of _their_ code. Quietly incorrect is the expensive kind of wrong.

The guards matter too. `topUp` and `redeem` refuse negative amounts, and `Money` throws on overflow
instead of wrapping. Without them, a plain `StoreCredit` could go negative by itself, and the
example would blame inheritance for the parent's own bug. The subclass keeps the guard, so breaking
the balance promise is the _only_ thing it does wrong.

**Form 2 — the loud refusal.** Checkout grows refunds, and in this product gift cards can't take
them (a business rule of this example, not of payments in general). The obvious move widens the
strategy for everyone:

```kotlin
interface PaymentMethod {
    fun charge(order: OrderId, amount: Money): PaymentResult
    fun refund(txn: TxnId)                    // widened for everyone
}

class GiftCardPayment : PaymentMethod {
    override fun charge(order: OrderId, amount: Money): PaymentResult {
        ...
    }   // fine
    override fun refund(txn: TxnId) =
        throw UnsupportedOperationException("gift cards cannot take refunds")
}

val method: PaymentMethod = registry.resolve("giftcard")
method.refund(txn)                            // boom — at runtime, in prod, on refund day
```

The type promises something the object refuses to do, and the refusal arrives at runtime instead of
compile time. At least it announces itself. Throwing isn't the violation on its own: a contract that
allows refusal is kept by refusing. This one promised refunds to every caller, so the refusal breaks
it. The fix is to stop claiming the contract:

```kotlin
interface PaymentMethod {
    fun charge(order: OrderId, amount: Money): PaymentResult
}
interface RefundableMethod : PaymentMethod {
    fun refund(txn: TxnId)
}
```

`RefundableMethod` extends `PaymentMethod` in the only safe direction: a method that can also refund
keeps every promise a charge-only view makes, never the reverse. Gift cards implement only
`PaymentMethod`, so there's no `refund` on them to call and nothing to throw.

In the repo, that fix carries real weight:

- **Refunds enter by order.** The order knows which method took the money and which transaction to
  reverse, so nobody can pair a card's refund with a gift card's charge.
- **The capability is asked, not assumed.** Methods arrive by name, so one question stays at
  runtime: can this one refund? `PaymentMethod.refundable()` promises only an answer, and `null`
  keeps that promise; `RefundableMethod` answers with itself. That names a contract, not a class,
  unlike the `is GiftCardPayment` patch below, so a new refundable method touches nothing.
- **It survives wrappers.** An `as? RefundableMethod` stops at a `PaymentMethod by inner` wrapper,
  quietly turning every card refund into store credit. `by` forwards the question to the card
  inside.
- **Gift cards settle in store credit.** A gift-card order comes back `NotRefundable`, and
  `SupportCreditFlow` issues store credit instead: the class whose promise you just watched a
  subclass break.
- **Each order settles once.** The order records its settlement, so a second press of the refund
  button answers `AlreadySettled` and moves no money.

What repaired the broken contract was **segregating the interface**, which happens to be the next
letter. The principles aren't five separate rules; they repair each other.

**The trade-off.** LSP isn't a dial: nothing is "too substitutable," and it can only be kept or
broken. Keeping it has a price, and the price lives in the contract. You can weaken the base
contract until every subtype can keep it, as `java.util.Collection` does: its Javadoc marks `add`
and `remove` as optional operations that may throw `UnsupportedOperationException`, so an
unmodifiable list keeps the contract by refusing, and every caller handles a refusal the type
allows. Or you keep the contract strong and split it, as `RefundableMethod` did, and pay in
interfaces: ISP's explosion. Weaker promises or more types: that's the real dial.

What LSP rules out is the third option, patching the caller.
`if (method is GiftCardPayment) skipRefund()` fixes the wrong answer by breaking OCP, so now two
principles are broken instead of one. That's LSP's real job in your toolbox: it detects bad
inheritance. When a tempting IS-A can't honor the full contract, stop inheriting. Narrow the
contract until every implementation can keep it, or hold the object in a field instead of extending
it.

> If you remember one thing: LSP is a **coupling** principle. Callers couple to the _base contract_,
> and every subtype must be safe behind it. No surprises through a base reference. It's not a dial,
> it's a detector: when IS-A can't keep the contract, don't inherit. The dial it leaves you is in
> the contract itself: weaker promises or more types.

---

## I — Interface Segregation

**Definition.** No client should be forced to depend on methods it doesn't use. The key word is
**client**: you segregate by _role_, one interface per _kind of caller_, not by chopping an
interface into pieces. Ask "who calls this, and which slice do they actually need?", never "how many
methods is too many?"

```kotlin
// One implementation may serve every role...
class StripePaymentGateway : PaymentGateway, PaymentReader { ... }

// ...but each client sees only the contract its role needs.
interface PaymentGateway {                        // the role that moves money
    fun charge(req: ChargeRequest): PaymentResult
    fun refund(txn: TxnId)
}
interface PaymentReader {                         // the role that looks at it
    fun transactions(range: DateRange): List<Txn>
}

class StatementsScreen(private val payments: PaymentReader) { ... }                // can't move money
class CardPayment(private val gateway: PaymentGateway) : RefundableMethod { ... }  // can't read statements
```

The implementation didn't split; the _view_ of it did, and the benefits are concrete:

- The statements screen has no `charge` or `refund` in scope, so it can't move money by accident.
  That narrows access rather than proving it (a cast could still reach the other role of the same
  object), but least privilege by default is what a payments audit asks for.
- A change to a charging signature no longer touches any read-only client.
- The test double for `StatementsScreen` stubs one query method instead of a whole PSP (payment
  service provider).

The system has now segregated twice, on two different questions: `RefundableMethod` by _what an
implementation can truly promise_, this split by _the role a client plays_. They compose.
`RefundFlow` decides a refund is allowed (capability, on the domain method), then `CardPayment`
carries it out through `PaymentGateway.refund` (mechanism, on the port). No client gets the raw
port: `RefundHandler`, the support desk's refund button, holds `RefundFlow`. Handed `PaymentGateway`
instead, it could refund any transaction, gift cards included, without asking.

**The symptom to look for.** An adapter full of no-ops means the interface above it was never cut by
role. The repo has a quiet version: the checkout tests' fake gateways only ever charge, yet each one
stubs `refund` as a no-op. One stub per fake is the cheap end of the dial, and splitting
`PaymentGateway` over it would be the explosion described next. When the fakes stub three or four
methods, cut.

**The trade-off.** Over-apply ISP and you get **interface explosion**: a hundred one-method
interfaces, every call site holding a different name for the same object, and nobody able to say
what the thing _is_ anymore. That's SRP's failure pair one level up: the fat interface is the god
class of contracts, and fragmentation is their shotgun surgery. ISP _is_ SRP applied to interfaces,
with the same dial: segregate by the client roles that _actually exist_, not by method count. Two
roles mean two interfaces. Five methods don't mean five interfaces.

The textbook files ISP under **coupling**, and Robert C. Martin's own formulation backs it: "clients
should not be forced to depend upon interfaces that they do not use" (_The C++ Report_, 1996),
because forcing them "results in an inadvertent coupling between all the clients." Both framings are
right; they answer different questions. What segregation _buys_ is decoupling. What tells you _where
to cut_ is cohesion: the roles whose methods change together.

> If you remember one thing: ISP is the **cohesion** force applied to contracts. Split by caller,
> not by method. Too few cuts and you get the fat interface; too many and you get interface
> explosion; the dial is the roles that actually exist.

---

## D — Dependency Inversion

**Definition.** The original formulation has two halves: _high-level modules should not depend on
low-level modules — both should depend on abstractions; and abstractions should not depend on
details — details should depend on abstractions._ What gets inverted isn't "now there's an
interface." It's **ownership**: the high-level policy _owns_ the abstraction, and the low-level
detail _implements_ it. The test is a single question: **which module declares the interface?**

```kotlin
// module: domain — the high-level policy OWNS the ports.
// (PaymentGateway and PaymentReader from the last section live here too.)
interface OrderRepository {                      // written in the domain's vocabulary,
    fun find(id: OrderId): Order?                // living in the domain's module
    fun save(order: Order)
}
fun interface Clock {
    fun now(): Instant
}

class CheckoutService(
    private val prices: PriceCalculator,
    private val methods: PaymentMethodRegistry,
    private val orders: OrderRepository,
    private val clock: Clock,
) {
    fun checkout(cart: Cart): PaymentResult {
        ...
    }   // business rules; zero infra imports
}
```

```kotlin
// module: infrastructure — depends on domain; domain has never heard of it
class StripePaymentGateway(private val client: StripeClient) : PaymentGateway, PaymentReader { ... }
class DynamoOrderRepository(private val db: DynamoDbClient) : OrderRepository { ... }
```

Follow the compile-time arrow: `infrastructure` imports `domain`, so the domain compiles alone, with
no Stripe SDK and no AWS on its classpath. That inward arrow is the dependency rule of hexagonal
architecture (ports and adapters): the domain owns the port, and infrastructure provides the
adapter. Hexagonal adds more than the arrow, such as an explicit application boundary and adapters
on both the driving and the driven side, but the arrow itself is DIP at the module boundary. Here it
is as the repo's actual layout:

```text
checkout/
  domain/            # no infra imports; a test enforces it
    Money  Cart  Order  Receipt  PaymentResult (sealed)  RefundResult (sealed)
    PaymentGateway  PaymentReader  OrderRepository  Clock      <- ports
    PaymentMethod / RefundableMethod (Card, Paypal, Bizum; GiftCard is charge-only)
    PaymentMethodRegistry  RefundFlow  PriceCalculator  ReceiptFormatter  CheckoutService
    StoreCredit  SupportCreditFlow                             <- where gift-card refunds land
  infrastructure/    # depends on domain; domain has never heard of it
    StripePaymentGateway  StripeClient  InMemoryOrderRepository  Meter  KeyStore
    RetryingGateway  MeteredGateway  IdempotentGateway         <- decorators ('by')
  clients/           # also depends on domain: the callers, not the adapters
    StatementsScreen                                           <- ISP's read-only role view
    RefundHandler                                              <- holds RefundFlow, never the port
  smells/            # the broken examples, compiling, each pinned by a test
    CheckoutManager  VipStoreCredit
  app/
    Main.kt          # the composition root: wires everything; DIP with no framework
```

Two honest caveats. First, so `main` runs anywhere with zero credentials, the adapters are an
`InMemoryOrderRepository` and a no-network `StripeClient` stand-in, not the real Dynamo and Stripe
SDKs. The ports can't tell the difference, and that a database can become a map in one line of
wiring is DIP's whole claim.

Second, `domain` and `infrastructure` are packages in one Gradle project, so the compiler alone
wouldn't stop a domain file from importing an adapter. `ArchitectureTest` does, with two checks:

- **Imports.** A `domain/` file may import, or name in full, only the domain, Kotlin, and the JDK's
  `java.time` and `java.util`. The JDK allowance is narrow on purpose: `java.sql` and
  `java.net.http` ship with the JDK too, and they're infrastructure.
- **Ambient reads.** `java.lang` needs no import, and allowing `java.time` for `Instant` also allows
  `Instant.now()`, exactly the read the `Clock` port exists to replace. This check fails on the
  system clock, `System`, `Runtime`, `Thread` or `ProcessBuilder` anywhere in domain code.

Both are text scans: a tripwire, not a proof. In a production codebase, make them Gradle subprojects
and the build enforces the arrow for you.

**The gotcha: DI != DIP.** Dependency _injection_ is a mechanism: someone hands objects their
collaborators. Dependency _inversion_ is a principle about who owns the abstraction, and you can
have either without the other. `@Autowired StripePaymentGateway`, the concrete class, is DI with
zero DIP: a framework injecting your coupling for you. Hand-wiring in `main` is DI in its purest
form, and DIP too, because the domain owns the interfaces being wired. Depend on an interface your
own module owns and you have DIP, container or not.

**The payoff.** Testability, with cause and effect in the right order. You can hand
`CheckoutService` a fake gateway and an in-memory `OrderRepository` _because_ it depends on
abstractions the domain owns. The mock isn't the point; it's the _evidence_. If you can't test a
class without booting the database, DIP is telling you an arrow points the wrong way.

**The trade-off.** The degenerate form is **interface-for-everything**:
`FooService`/`FooServiceImpl` pairs that exist because "we always do it that way," OCP's premature
abstraction moved up a layer. Abstract at **true frontiers**, the I/O boundaries (the database,
HTTP, queues, the clock, someone else's SDK) where a second implementation genuinely exists: the
real one and the test fake, at minimum. An interface between two classes in the same package that
always change together isn't low coupling; it's low cohesion disguised as low coupling.

Every port here sits on such a frontier, `Clock` included. It's deliberately narrower than
`java.time.Clock`, which is abstract, carries a time zone the domain never reads, and no lambda can
implement. The JDK's `java.time.InstantSource` (Java 17) has the right shape (one method, no zone,
lambda-friendly) and would do; the domain declares its own anyway, like every other port, so the
policy states its need in its own words. Three lines is the whole price.

> If you remember one thing: DIP is the **coupling** principle at architecture scale. The domain
> owns the interface, details implement it, arrows point inward. DI is a mechanism; DIP is a
> direction. Abstract at real frontiers, not everywhere.

---

## The Composition Root

Every abstraction has to become an object somewhere, and in this system exactly one place gets to do
it: `Main.kt`, the file where all five principles stop being prose.

```kotlin
// app/Main.kt
fun main() {
    val meter = Meter()
    val clock = Clock { Instant.now() }   // the domain's own port; java.time.Clock never leaks inward

    // ISP: one Stripe adapter, two roles. The money-moving role gets the decorator stack below;
    // the read-only role gets the adapter itself, since a statement needs no retries and no keys.
    val stripe = StripePaymentGateway(StripeClient(clock))

    // Composition: cross-cutting concerns as a decorator stack. The ORDER is a
    // decision, and it lives here, in wiring, not in a class hierarchy.
    val gateway: PaymentGateway =
        MeteredGateway(
            RetryingGateway(
                IdempotentGateway(stripe, KeyStore()),
            ),
            meter,
        )

    // OCP's real cost, concentrated: one plain line per payment method.
    val methods = PaymentMethodRegistry(
        "card" to { CardPayment(gateway) },
        "paypal" to { PaypalPayment(gateway) },
        "bizum" to { BizumPayment(gateway) },
        "giftcard" to { GiftCardPayment(gateway) },   // claims no refund capability
    )

    // DIP: details handed to a domain that has never heard of them.
    val orders = InMemoryOrderRepository()   // prod: DynamoOrderRepository, same port, one line
    val checkout = CheckoutService(
        PriceCalculator(),                   // SRP: Finance's class, alone
        methods,
        orders,
        clock,                               // the same clock the PSP stand-in stamps with
    )

    // The clients, each holding only the role it plays.
    val statements = StatementsScreen(stripe)                     // ISP: reads, can't move money
    val refundDesk = RefundHandler(RefundFlow(orders, methods))   // LSP: refunds only through the gate
    val storeCredit = SupportCreditFlow(StoreCredit())            // where a gift card's refund lands

    // …then a short demo script plays a customer and the support desk: a card order and a
    // gift-card order, the statement, both refunds, and the card's refund button pressed twice.
    demo(checkout, orders, statements, refundDesk, storeCredit)
    println("logical charges metered: ${meter.count("charges")}")
}
```

Read it as a checklist:

- **Composition.** The gateway is wrapped three times: `IdempotentGateway` so an approved order
  isn't charged again, `RetryingGateway` so a timed-out call gets another try, and `MeteredGateway`
  so someone can count what happened. Each wrapper holds the _port_, not a concrete class, which is
  why they stack at all.
- **Order.** Where each wrapper sits is a decision made here. `MeteredGateway(RetryingGateway(…))`
  counts _logical_ charges; `RetryingGateway(MeteredGateway(…))` counts _attempts_. Neither is
  wrong; they're different metrics, a one-line diff apart. Neither counts PSP calls, though: in
  both, idempotency sits inside the meter, so a double-click the cache answers still gets metered.
  To count PSP calls, wrap the Stripe adapter itself.
- **OCP.** The registry: the one registration line the OCP section promised you.
- **ISP.** One Stripe adapter, two roles: `CardPayment` sees a decorated `PaymentGateway`,
  `StatementsScreen` an undecorated `PaymentReader`, since a statement needs no retries or keys.
  Cross-cutting concerns attach per role.
- **LSP.** Details arrive typed as ports (`PaymentGateway`, not `StripePaymentGateway`), and refunds
  reach money only through `RefundFlow`, which asks each method for the capability.
- **DIP.** No framework in sight: DI in its purest form, down to one clock shared by the domain and
  the PSP stand-in.
- **SRP.** `main` holds no business logic, so its single reason to change is "the wiring changed."
  The demo script lives in a function of its own.

The decorators forward with Kotlin's `by inner`: delegate the whole port to the wrapped object, then
override only what you care about. That's black-box reuse with no fragile base class, at one cost:
`by` forwards whatever it isn't told about. `refund` already passes through all three layers
unmetered, unretried and without a key, and a method added to `PaymentGateway` tomorrow would slip
through the same way, silently. It's the open default, the opposite of a sealed `when` that makes
you decide.

**What the idempotency layer can't do.** It's a local memory of approvals for calls made one at a
time. If the PSP approves but its response never arrives, or two calls race past the cache, only the
key sent to the PSP protects you, which is why the Stripe adapter forwards it. Both layers also
check what a key is reused _for_:

- **Same request:** the original answer.
- **Same order, another amount or method:** `Conflict`, the way
  [Stripe refuses a key reused with different parameters](https://docs.stripe.com/api/idempotent_requests).
  Otherwise a cart edited after payment would come back "approved" at a total nobody charged.
- **Why not `Declined`:** a decline means no money moved, and here some may have. If the PSP charged
  the first attempt but its response never arrived, a charge exists that no order records, and
  "declined" would tell a customer who paid to pay again. Finding that charge by its key is
  reconciliation, out of scope here.
- **Same price, other lines:** the PSP only sees amount and method, so swapping size M for L replays
  cleanly through both layers. Only the domain knows the lines, so `CheckoutService` compares them
  with the recorded order and answers `Conflict` too.

Keying the order has one more price: a real PSP replays the _first_ answer for a key, declines
included, so paying another way after a decline needs a new key (order plus attempt number), which
the sample leaves out.

**What the retry layer won't do.** It retries only `Timeout`, through an exhaustive `when`: a
decline is an answer, not a failure, and asking again only asks the same question. It also retries
only a request that carries a key. A `Timeout` means nobody knows whether the PSP charged, so a
keyed retry is safe (the PSP dedupes it) and a keyless one could charge twice. The wired flow always
has a key, because an `OrderId` can't be blank. Add another `PaymentResult` and that `when` stops
compiling until someone decides whether the new outcome is worth retrying.

---

## Throw Away the Acronym

Here's the whole article as two questions, the two to actually ask in code review:

1. **"Do these things change for the same reason?"** — the _cohesion_ question. If yes, keep them
   together; if no, separate them. SRP asks it about classes, ISP about interfaces.

2. **"If this changes, what else is forced to move?"** — the _coupling_ question. OCP asks it about
   new features (nothing should move: add a class), LSP about subtypes (callers of the base must
   never notice), DIP about architecture (details move; policy doesn't).

The two feed each other: group what changes together and fewer changes cross a module line, so
coupling falls; cut a dependency and each side comes out more focused, so cohesion rises.

One bounded context was enough for all five letters, because the domain forced each one: pricing and
receipt copy answer to different departments, new payment methods keep arriving, gift cards can't
refund, a statements screen has no business moving money, and checkout has to be testable without a
PSP. Here is every dial in one place:

| Principle | Under-applied                           | Over-applied                | The dial                                    |
| --------- | --------------------------------------- | --------------------------- | ------------------------------------------- |
| SRP       | God class                               | Shotgun surgery             | One actor per class                         |
| OCP       | Growing `if/else`                       | Speculative interfaces      | Wait for the second case; seal what you own |
| LSP       | _Broken:_ `is`-check patches in callers | — (constraint, not a dial)  | Can't keep the contract → don't inherit     |
| ISP       | Fat interface                           | Interface explosion         | One interface per role                      |
| DIP       | Domain imports infrastructure           | `FooServiceImpl` everywhere | Abstract at true frontiers only             |

The LSP row reads differently on purpose: a constraint isn't under-applied, it's _broken_, and the
`is`-check patches are the symptom you see in callers.

Cohesion and coupling aren't SOLID's children; they're its grandparents. Stevens, Myers, and
Constantine named the pair in "Structured Design" (_IBM Systems Journal_, 1974); Parnas nailed the
underlying idea as _information hiding_ in 1972; the acronym arrived three decades later. The
letters are the most successful marketing campaign those two ideas ever had, though LSP also carries
a correctness rule that neither force gives you on its own. Useful as mnemonics, dangerous as a
checklist: a checklist tells you to add an interface, and the forces tell you whether the interface
bought you anything.

---

_Everything in this article lives in
[the companion repo](https://github.com/dionisioC/blog/tree/main/posts/2026-08-solid-cohesion-coupling/code):
one Gradle project holding the clean slices and the `smells` package side by side, with a test
pinning each claim (the VIP balance really goes negative, the same cart really charges the PSP once,
a gift-card order really can't take a refund, the decorator order really changes the metric) and a
`main()` you can run._
