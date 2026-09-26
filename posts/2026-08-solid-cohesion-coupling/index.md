# SOLID Without the Acronym: It's Just Cohesion and Coupling

SOLID isn't really five independent principles. It's mostly two long-standing software design ideas
expressed in different ways.

- **High cohesion** — keep the things that change together, together.
- **Low coupling** — depend on stable abstractions, not volatile details.

Four of the five letters are _named consequences_ of those two forces, and the fifth comes with an
asterisk. Here's the first cut:

| Force        | Principles    |
| ------------ | ------------- |
| **Cohesion** | SRP, ISP      |
| **Coupling** | OCP, LSP, DIP |

A first cut is all it is. The rest of the article complicates it, and the complications are where
the thinking is. Two are worth flagging now. ISP is the contested one: the textbook files it under
coupling, and the ISP section explains why both readings are right. LSP is the asterisk: it belongs
under coupling because callers couple to the _base contract_, never to your subtype, but unlike the
other four letters it isn't a dial you can turn too far. It's a correctness constraint, which is
what makes it a _detector_ rather than a design choice.

Once you see that, you stop memorizing and start deriving, and you learn when _not_ to apply each
one, because every single one of them has a cost. Applied without judgment, SOLID produces its own
kind of unmaintainable code.

Every example lives in one system: the checkout slice of a payments product. One use case, end to
end: `CheckoutService.checkout(cart)` prices the cart, charges a payment method through a gateway,
records the order, and returns a result. Every principle below shows up because the domain _forces_
it, and principles that share a codebase interact: you'll watch them repair each other.

For each principle: what it means, where it shows up here, and what it costs when you over-apply it.

---

## S — Single Responsibility

**Definition.** A class should have one reason to change. The version that actually helps: **one
reason to change means one _actor_**, one group of people who can ask for that change. In our system
the broken version is a `CheckoutManager` with both `total()` and `renderReceipt()`. It _feels_ like
one thing ("checkout"), but `total()` answers to Finance, which runs a 10% loyalty discount, and
`renderReceipt()` answers to Marketing, which runs a points program and prints the lines that earned
points. Two programs that happen to cover the same lines today. Two actors, two reasons to change,
one class: that's the smell.

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

Here's how it goes wrong: Finance asks you to stop discounting gift wrap. A developer edits
`rewardedItems()`, the obvious place, and Marketing's receipt silently drops gift wrap from its
"points earned on" line. Nobody in Marketing asked to stop awarding points on it. The total moves
exactly as Finance asked (every item still charged, a slightly smaller discount), so the diff looks
correct. Nobody saw two departments in one edit. That shared private helper is coupling between
actors, and a code review can easily miss it, because the class has one name and one obvious topic.

**The same shape in the wild.** You already apply SRP without naming it: the layered split. The
controller changes when the _API shape_ changes, the service when a _business rule_ changes, the
repository when _storage_ changes. Three reasons, three classes. In this system the same instinct
fires once more _inside_ the service layer, and it's the split `CheckoutManager` refused to make:
pricing math is `PriceCalculator` (Finance's), receipt copy is `ReceiptFormatter` (Marketing's),
orchestration is `CheckoutService` (the product flow).

The part that feels wrong is what happens to `rewardedItems()`: each class gets _its own copy_ of
"which lines count." Finance's skips gift wrap; Marketing's still includes it. DRY says merge them
back, but they're two rules that agreed for a while, owned by two actors, and merging them is what
caused the bug. Martin calls this _accidental duplication_: code that looks the same but changes for
different reasons isn't really duplicated. In the repo, `SrpTest` runs the smell both ways (a
`rewardGiftWrap` flag stands in for the edit) and shows the one edit moving the discount _and_ the
receipt. Then it shows the split doing what `CheckoutManager` can't: no discount on gift wrap,
points on it anyway. The moment "who asks for changes to this?" gets two different answers, you're
looking at two classes wearing one name.

**The trade-off.** SRP has two failure modes. Under-apply it and you get the god class everyone
warns about. But over-apply it and you get something just as bad and harder to spot: **shotgun
surgery**, where a single logical change forces edits across ten tiny files because you scattered
things that actually change together. The dial between the two extremes is **cohesion**: _group what
changes together._ Splitting by "this method feels different" is how you end up with the ten-file
problem; splitting by "these change for different reasons" is SRP.

> If you remember one thing: SRP is the **cohesion** force. Too little separation and you get the
> god class; too much and you get shotgun surgery. The question is never "how small can this class
> be," it's "do these parts change for the same reason?"

---

## O — Open/Closed

**Definition.** A class should be _open for extension, closed for modification._ In practice that
means: you should be able to add new behavior by adding a new class, not by editing an existing,
tested one. The enemy this principle fights is the `if/else` that grows a new branch every time a
payment method lands:

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

The mechanism that buys you OCP is polymorphism: depend on an abstraction, and add a new
_implementation_ instead of a new _branch_.

```kotlin
interface PaymentMethod {
    fun charge(order: OrderId, amount: Money): PaymentResult
}   // closed

class CardPayment : PaymentMethod { ... }
class PaypalPayment : PaymentMethod { ... }
class BizumPayment : PaymentMethod { ... }        // adding one = a NEW file
```

New behavior is now a new file, _almost_. _Something_ still has to decide which `PaymentMethod` to
instantiate, and that dispatch point does move when Bizum arrives: somewhere there's one line saying
`"bizum" is a BizumPayment`, and you will add it. In this system that somewhere is
`PaymentMethodRegistry`, and you'll see the line in the composition root at the end. OCP doesn't
delete the choice; it _concentrates_ it. The choice moves out of tested business logic, where every
edit risks the branches already there, and into one registration line in a place with no logic to
break. _Closed for modification_ was never "zero edits anywhere"; it's "no edits where the behavior
lives."

Notice the condition hiding in all of this: the _axis of variation_, the one direction along which
you expected change to arrive, was _known_. OCP pays off exactly where variation is expected, which
makes it worth looking at an axis where the opposite holds.

**The inverse case: closed variation.** You've already seen this type. `PaymentResult` is what
`checkout()` and every `PaymentMethod.charge()` returns:

```kotlin
sealed interface PaymentResult
data class Approved(val txn: TxnId, val amount: Money) : PaymentResult
data class Declined(val reason: String)               : PaymentResult
data object Timeout                                   : PaymentResult

fun record(result: PaymentResult) = when (result) {
    is Approved -> ...
    is Declined -> ...
    Timeout     -> ...  // add a 4th variant → this 'when' stops compiling
}
```

(Java has the same pair: `sealed` types shipped in 17, JEP 409, with the exhaustive pattern `switch`
that completes them finalized in 21, JEP 441.) This is the **deliberate inverse of OCP**. OCP wants
adding a variant to touch nothing; sealed wants adding a variant to _break every exhaustive `when`
at compile time_ (one with an `else` branch opts out), because for a closed set you own, like the
states of an order or the outcomes of a payment, a silently unhandled case is the bug. Payment
_methods_ are an open set: anyone may invent one, so OCP and the registry. Payment _results_ are a
closed set: you decide what an outcome can be, so sealed and an exhaustive `when`. One domain, both
answers. Choosing per axis is the judgment.

**The trade-off.** Designing for OCP up front means adding indirection on a guess. The cost is
**premature abstraction (YAGNI)**: an interface with exactly one implementation forever, a plugin
system for plugins that never arrive, and every reader now has to chase that interface to find the
one place the work happens. The rule that helps: **wait for the second case.** Add the abstraction
when the _second_ implementation shows up; that's when OCP starts paying for the indirection instead
of just charging you for it.

> If you remember one thing: OCP is a **coupling** principle. It decouples _what varies_ (the
> implementations) from _what's stable_ (the code that uses them). Add a class, don't edit one. But
> don't add the interface before the second thing needs it, and when the set is closed, invert the
> whole idea and let a sealed type break every exhaustive `when` on purpose.

---

## L — Liskov Substitution

**Definition.** A subtype must be usable anywhere its base type is expected: through a base
reference, with no surprises (Liskov & Wing's _behavioral subtyping_, 1994). The reframing that
matters: **`extends` is not a code-sharing mechanism, it's a published claim.** "Every promise the
parent makes, I keep." LSP is that claim taken seriously.

The promises are not only the ones written into method signatures. The expensive ones are the
properties that hold for an object's entire lifetime, like "balance is never negative" or "the
captured amount never exceeds the authorized amount," because callers are entitled to assume them
without ever checking. That is their whole value, and it's what makes breaking one so costly.

Broken promises come in two forms, and they're worth seeing side by side because they fail in
opposite ways.

**Form 1 — the silent wrong answer.** Our system can issue store credit (it's where gift-card
refunds land, as you'll see shortly):

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

Every caller written against `StoreCredit` is entitled to assume `balance()` never comes back
negative, after _any_ sequence of calls: reconciliation, the balance the app displays, the liability
line Finance reports (unspent store credit is a liability on someone's books). None of them
re-check, because the promise said they didn't have to. Hand them a `VipStoreCredit` and all of them
are wrong at once, with no exception, no crash, and not one changed line of _their_ code. Nothing
fails. Everything is quietly incorrect, which is the expensive kind of wrong.

The guards matter too. `topUp` and `redeem` both refuse negative amounts, and `Money`'s arithmetic
throws on overflow instead of wrapping. Without them, a plain `StoreCredit` could go negative on its
own, and this example would be blaming inheritance for a bug the parent already had. The subclass
keeps the same guard for the same reason: breaking the balance promise should be the _only_ thing it
does wrong.

**Form 2 — the loud refusal.** Checkout eventually grows refunds, and in this product gift cards
can't take them (a business rule of this example, not of payments in general). The obvious move is
still to widen the strategy for everyone:

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
compile time. That's the opposite failure to Form 1, and the easier one, because at least it
announces itself. Throwing isn't the violation on its own: a contract that allows refusal is kept by
refusing. This interface promised refunds to every caller, so the refusal breaks it. The fix is to
stop claiming the contract:

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

In the repo, refunds enter through `RefundFlow`, and they enter _by order_, not by transaction: the
order recorded which method took the money and which transaction to reverse, so nobody can pair a
card's refund with a gift card's charge. One question is still asked at runtime, because a payment
method arrives as a string: the registry's `refundable(name)` asks whether the resolved method has
the capability (`as? RefundableMethod`). That isn't the `is GiftCardPayment` patch you'll see
condemned below. It names a contract, not a class, so a new refundable method still touches nothing.
A gift-card order comes back `NotRefundable`, and the support flow (`SupportCreditFlow`) issues
store credit instead: the class whose promise you just watched a subclass break.

And note _what_ repaired the broken contract: **segregating the interface**, which happens to be the
next letter. The principles aren't five separate rules; they repair each other.

**The trade-off.** LSP itself isn't a dial: there's no such thing as "too substitutable." SRP, OCP,
ISP and DIP can all be over-applied; LSP can only be kept or broken. Keeping it still has a price,
though, and the price lives in the contract. You can always make every subtype substitutable by
weakening the base contract until it promises nothing a subtype can't do. `java.util.Collection`
does exactly that: its Javadoc marks `add` and `remove` as optional operations that may throw
`UnsupportedOperationException`, so an unmodifiable list keeps the contract by refusing, and every
caller pays by handling a refusal the type allows. Or you keep the contract strong and split it, as
`RefundableMethod` did, and pay in interfaces: ISP's explosion. Weaker promises or more types.
That's the real dial, and it belongs to the contract.

What LSP rules out is the third option, patching the caller.
`if (method is GiftCardPayment) skipRefund()` fixes the wrong answer by breaking OCP, and now two
principles are broken instead of one. That's why LSP's real job in your toolbox is diagnostic: it's
the detector for bad inheritance. When a tempting IS-A can't honor the full contract, stop
inheriting. Narrow the contract until every implementation can keep it, or hold the object in a
field instead of extending it.

> If you remember one thing: LSP is a **coupling** principle. Callers couple to the _base contract_,
> and every subtype must be safe behind it. No surprises through a base reference. It's not a dial,
> it's a detector: when IS-A can't keep the contract, don't inherit. The dial it leaves you is in
> the contract itself: weaker promises or more types.

---

## I — Interface Segregation

**Definition.** No client should be forced to depend on methods it doesn't use. The key word is
**client**: you don't segregate an interface by chopping it into pieces, you segregate it by _role_,
one interface per _kind of caller_. The question is "who calls this, and which slice do they
actually need?", never "how many methods is too many?"

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

The implementation didn't split; the _view_ of it did. And the benefits are concrete, not aesthetic.
The statements screen has no charge or refund method in scope, so it can't move money by accident.
That narrows access rather than proving it (a cast could still reach the other role of the same
object), but in a payments system, least privilege by default is what an audit asks for. A change to
a charging signature no longer touches any read-only client. And the test double for
`StatementsScreen` stubs one query method instead of a whole PSP (payment service provider).

Notice this system has now segregated twice, on two different questions: the `RefundableMethod`
split cut by _the capability an implementation can truly promise_, this one by _the role a client
actually plays_. They aren't rivals; they compose. `RefundFlow` decides a refund is allowed (the
order's method has the capability), then `CardPayment` carries it out through
`PaymentGateway.refund`: capability on the domain method, mechanism on the infra port. What no
client gets is the raw port. `RefundHandler`, the support desk's refund button in the repo, holds
`RefundFlow`, not `PaymentGateway`. A client that moves money goes through the use case that decides
whether it may; hand it the port and it could refund any transaction, gift cards included, without
asking.

**The symptom to look for.** An adapter full of no-ops, a class whose entire purpose is to supply
empty implementations of methods you were forced to declare, is ISP screaming. Wherever you find
one, the interface above it was never cut by role. The repo has a quiet version of it: the checkout
tests' fake gateways only ever charge, yet each one stubs `refund` as a no-op. One stub per fake is
the cheap end of the dial, and splitting `PaymentGateway` over it would be the explosion described
next. If the fakes start stubbing three or four methods, that's your signal to cut.

**The trade-off.** Over-apply it and you get **interface explosion**: a hundred one-method
interfaces, every call-site holding a different name for the same object, and nobody able to say
what the thing _is_ anymore. Notice this is exactly SRP's failure pair one level up. Under-apply it
and you get the fat interface (the god class of contracts); over-apply it and you get fragmentation
(shotgun surgery of contracts). That's because ISP _is_ SRP applied to interfaces. Both are the
cohesion force, and the dial is the same: segregate by the client roles that _actually exist_, not
by method count. Two roles mean two interfaces. Five methods don't mean five interfaces.

The textbook files ISP under **coupling**, not cohesion. Robert C. Martin's own formulation, "no
client should be forced to depend on methods it doesn't use," is a sentence about client coupling.
Both framings are correct; they answer different questions. What segregation _buys_ is decoupling:
clients stop depending on methods they never call. What tells you _where to cut_ is cohesion: the
roles whose methods change together.

> If you remember one thing: ISP is the **cohesion** force applied to contracts. Split by caller,
> not by method. Too few cuts and you get the fat interface; too many and you get interface
> explosion; the dial is the roles that actually exist.

---

## D — Dependency Inversion

**Definition.** The original formulation has two halves: _high-level modules should not depend on
low-level modules — both should depend on abstractions; and abstractions should not depend on
details — details should depend on abstractions._ The word doing the work is _inversion_, and what
gets inverted is not "now there's an interface." It's **ownership**. The high-level policy _owns_
the abstraction; the low-level detail _implements_ it. The test is a single question: **which module
declares the interface?**

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

Follow the compile-time arrow: `infrastructure` imports `domain`. The domain compiles alone, with no
Stripe SDK and no AWS on its classpath. That inverted arrow, with dependencies pointing _inward_
toward policy, is the dependency rule of hexagonal architecture (ports and adapters): "port" is the
interface the domain owns, "adapter" is the implementation infra provides. Hexagonal adds more than
the arrow (an explicit application boundary, and adapters both for the callers that drive the
application and for the systems it calls), but the arrow itself is DIP applied at the module
boundary. Here is the same fact drawn as a directory, the repo's actual layout, DIP made physical:

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

One deliberate swap in the runnable repo: so `main` runs anywhere with zero credentials, the shipped
adapters are an `InMemoryOrderRepository` and a no-network `StripeClient` stand-in rather than the
real Dynamo and Stripe SDKs. The ports can't tell the difference, and that a database can become a
map in one line of wiring is DIP's whole claim.

One simplification, too: in the repo, `domain` and `infrastructure` are packages in a single Gradle
project, not separate modules, so the compiler alone wouldn't stop a domain file from importing an
adapter. `ArchitectureTest` does: it fails if anything in `domain/` depends on code outside the
domain, Kotlin, and the JDK's `java.time` and `java.util`. The JDK allowance is narrow on purpose,
because `java.sql` and `java.net.http` ship with the JDK too, and they're infrastructure. In a
production codebase, make them Gradle subprojects and the build enforces the arrow for you.

**The gotcha: DI != DIP.** Dependency _injection_ is a mechanism: someone hands objects their
collaborators. Dependency _inversion_ is a principle about who owns the abstraction, and you can
have either without the other. `@Autowired StripePaymentGateway`, the concrete class, is DI with
zero DIP: a framework injecting your coupling for you. Hand-wiring in `main` with no framework at
all is DI in its purest form, and it's DIP too, because the domain owns the interfaces being wired.
If your service depends on an interface its own module owns, you have DIP whether or not a container
exists.

**The payoff.** Testability, with cause and effect in the right order. You can hand
`CheckoutService` a fake gateway and an in-memory `OrderRepository` _because_ it depends on
abstractions the domain owns. The mock isn't the point; the mock is the _evidence_. And if you can't
test a class without booting the database, that's DIP telling you an arrow points the wrong way.

**The trade-off.** The degenerate form is **interface-for-everything**:
`FooService`/`FooServiceImpl` pairs that exist because "we always do it that way," the premature
abstraction OCP warned about, moved up a layer. Abstract at **true frontiers**: I/O boundaries like
the database, HTTP, queues, the clock and someone else's SDK, where a second implementation
genuinely exists (the real one and the test fake, at minimum). Every port in this domain sits on
exactly that kind of frontier. That includes `Clock`, which shares a name with `java.time.Clock` but
not its width: the JDK class is abstract, carries a time zone the domain never reads, and no lambda
can implement it. The domain asks for `now()` and nothing else, so that's the port it owns. An
interface between two classes in the same package that always change together isn't low coupling;
it's low cohesion disguised as low coupling.

> If you remember one thing: DIP is the **coupling** principle at architecture scale. The domain
> owns the interface, details implement it, arrows point inward. DI is a mechanism; DIP is a
> direction. Abstract at real frontiers, not everywhere.

---

## The Composition Root

Every abstraction in this system has to become an object eventually, and there is exactly one place
where that's allowed to happen. Here it is: `Main.kt`, the file where all five principles stop being
prose.

```kotlin
// app/Main.kt
fun main() {
    val meter = Meter()

    // Composition: cross-cutting concerns as a decorator stack. The ORDER is a
    // decision, and it lives here, in wiring, not in a class hierarchy.
    val gateway: PaymentGateway =
        MeteredGateway(
            RetryingGateway(
                IdempotentGateway(StripePaymentGateway(StripeClient()), KeyStore()),
            ),
            meter,
        )

    // OCP's real cost, concentrated: one plain line per payment method.
    val methods = PaymentMethodRegistry(
        "card" to { CardPayment(gateway) },
        "paypal" to { PaypalPayment(gateway) },
        "bizum" to { BizumPayment(gateway) },
        "giftcard" to { GiftCardPayment(gateway) },   // claims no refund contract
    )

    // DIP: details handed to a domain that has never heard of them.
    val orders = InMemoryOrderRepository()   // prod: DynamoOrderRepository, same port, one line
    val checkout = CheckoutService(
        PriceCalculator(),                   // SRP: Finance's class, alone
        methods,
        orders,
        Clock { Instant.now() },             // the domain's own port; java.time.Clock never leaks inward
    )

    // …then Main.kt builds a Cart, runs checkout.checkout(cart), and prints the sealed
    // result, plus the saved order's receipt: ReceiptFormatter getting its turn.
}
```

Read it as a checklist:

- **Composition.** The gateway is wrapped three times: `IdempotentGateway` so an order already
  approved isn't charged again, `RetryingGateway` so a call that timed out gets another try, and
  `MeteredGateway` so someone can count what happened. Each wrapper holds the _port_ rather than a
  concrete class, which is why they stack at all.
- **Order.** Where each wrapper sits is a real decision, made here in wiring.
  `MeteredGateway(RetryingGateway(…))` counts _logical_ charges, one however many retries it takes,
  while `RetryingGateway(MeteredGateway(…))` counts _attempts_, every retry included. Neither order
  is wrong; they're different metrics, and swapping them is a one-line diff in a code review rather
  than a new class.
- **OCP.** The registry is the dispatch point, concentrated into the one place with no logic to
  break: the line the OCP section promised you.
- **ISP and LSP.** Every detail reaches the domain typed as a _port_ (`PaymentGateway`, not
  `StripePaymentGateway`), so role views and substitutability are what the rest of the system sees.
- **DIP.** The domain classes take their details from outside, with no framework in sight: DI in its
  purest form.
- **SRP.** Nothing in this function contains business logic, because its single reason to change is
  "the wiring changed." SRP, applied to `main` itself.

Neither nesting counts PSP calls, though. The idempotency layer sits inside the meter in both, so
the meter counts a double-click that the idempotency cache answers, though it never reaches the PSP.
To count real PSP calls, wrap the Stripe adapter itself.

The decorators get their forwarding from Kotlin's `by inner`: implement the port by delegating
everything to the wrapped object, then override only what you care about. That's black-box reuse
with no fragile base class, and it has one cost worth knowing: `by` forwards whatever it isn't told
about. `refund` already passes through all three layers unmetered, unretried and without a key, and
a method added to `PaymentGateway` tomorrow would slip through the same way, silently. It's the open
default, the opposite of a sealed `when` that makes you decide.

Know the limits of the idempotency layer, too. It's a local memory of approvals for calls made one
at a time: if the PSP approves a charge but the response is lost, or two calls race past the cache
together, it can't help. The guarantee that survives those cases is the same key sent to the PSP,
which is why the Stripe adapter forwards it. Both layers also check what a caller reuses a key
_for_. A replay of the same request gets the original answer, but both layers refuse the same order
at a different amount or with a different method, the way
[Stripe refuses a key reused with different parameters](https://docs.stripe.com/api/idempotent_requests).
Without that check, a cart edited after payment would come back "approved" at a total nobody
charged. Keying the order has a price as well: a real PSP replays the _first_ answer for a key, a
decline included, so paying another way after a decline needs a new key (the order plus an attempt
number), which the sample leaves out.

The retry layer gets its limits from the sealed type. It retries only `Timeout` and hands back
`Approved` and `Declined` as they are, through an exhaustive `when`: a decline is an answer, not a
failure, and asking again only asks the same question. Add a fourth `PaymentResult` and that `when`
stops compiling until someone decides whether the new outcome is worth retrying.

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
receipt copy answer to different departments, new payment methods arrive constantly, gift cards
can't refund, a statements screen has no business moving money, and checkout has to be testable
without a PSP. Here is every dial in one place:

| Principle | Under-applied                           | Over-applied                | The dial                                    |
| --------- | --------------------------------------- | --------------------------- | ------------------------------------------- |
| SRP       | God class                               | Shotgun surgery             | One actor per class                         |
| OCP       | Growing `if/else`                       | Speculative interfaces      | Wait for the second case; seal what you own |
| LSP       | _Broken:_ `is`-check patches in callers | — (constraint, not a dial)  | Can't keep the contract → don't inherit     |
| ISP       | Fat interface                           | Interface explosion         | One role per client                         |
| DIP       | Domain imports infrastructure           | `FooServiceImpl` everywhere | Abstract at true frontiers only             |

The LSP row reads differently on purpose: a constraint isn't under-applied, it's _broken_. The
`is`-check patches are the symptom you see in callers, not a sign you used too little LSP.

Cohesion and coupling are not SOLID's children; they're its grandparents. Stevens, Myers, and
Constantine named the pair in "Structured Design" (_IBM Systems Journal_, 1974); Parnas nailed the
underlying idea as _information hiding_ in 1972; the acronym arrived three decades later. The
letters are the most successful marketing campaign those two ideas ever had, though LSP also carries
a correctness rule that neither force gives you on its own. Genuinely useful as mnemonics, dangerous
as a checklist. A checklist tells you to add an interface. The forces tell you whether the interface
bought you anything.

---

_Everything in this article lives in
[the companion repo](https://github.com/dionisioC/blog/tree/main/posts/2026-08-solid-cohesion-coupling/code):
one Gradle project holding the clean slices and the `smells` package side by side, with a test
pinning each claim (the VIP balance really goes negative, the same cart really charges the PSP once,
a gift-card order really can't take a refund, the decorator order really changes the metric) and a
`main()` you can run._
