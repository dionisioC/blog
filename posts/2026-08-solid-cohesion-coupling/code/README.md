# Code for "SOLID Without the Acronym"

One cohesive **checkout / payments** domain that exercises every principle in the article — the same
system the prose walks through, made runnable. Each test **is** an assertion the article makes in
words, so the samples can't rot.

## Layout

```text
src/main/kotlin/dev/dionisioc/checkout/
  domain/            # zero infra imports, enforced by ArchitectureTest — Money, Cart, Order,
                     #   PaymentResult (sealed),
                     #   ports (PaymentGateway, PaymentReader, OrderRepository, Clock),
                     #   PaymentMethod/RefundableMethod + RefundFlow + registry, PriceCalculator,
                     #   ReceiptFormatter, CheckoutService, StoreCredit + SupportCreditFlow
                     #   (where gift-card refunds land)
  infrastructure/    # StripePaymentGateway (+PaymentReader), InMemoryOrderRepository,
                     #   Meter, KeyStore, and the by-delegation decorators
  clients/           # the driving side — StatementsScreen and RefundHandler, ISP's two
                     #   role-views; imports domain, never the reverse
  smells/            # the anti-examples the article dissects (CheckoutManager's two actors,
                     #   VipStoreCredit's broken promise)
  app/Main.kt        # the composition root — the whole article on one screen
```

## Run

Toolchain: Kotlin 2.4.20, JDK 25, Gradle 9.7.1 via the committed wrapper.

```bash
./gradlew test
./gradlew run        # runs Main.kt (a single checkout end to end)
```

## What's proven

| Article claim                                                                                                                                                                       | Test                   |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------- |
| **SRP** — changing the reward rule shifts the discount and the receipt, but every item stays billed either way (the bill can't be silently undercharged)                            | `SrpTest`              |
| **OCP** — the registry dispatches by name with no type switch; an unknown method fails fast                                                                                         | `OcpTest`              |
| **LSP** — `StoreCredit` holds `balance() >= 0`; `VipStoreCredit` drives it **negative** through the base reference (Form 1, the silent wrong answer)                                | `LspTest`              |
| **LSP** — the base `StoreCredit` keeps its own promise first: `topUp` and `redeem` reject negative amounts, and `Money` arithmetic throws on overflow instead of wrapping           | `LspTest`, `MoneyTest` |
| **LSP** — `GiftCardPayment` is **not** a `RefundableMethod`: `RefundFlow` reverses a card charge but can't even be handed a gift card (segregation = compile error, not a crash)    | `LspTest`              |
| **LSP** — the refund a gift card can't take comes back as store credit through `SupportCreditFlow`, the support path the segregation forces into existence                          | `LspTest`              |
| **ISP** — one Stripe adapter is seen as a read-only `PaymentReader` by one client and a money-moving `PaymentGateway` by another; both are views of the same object                 | `IspTest`              |
| **ISP** — the reader answers for the range it was asked and no wider; adjacent half-open ranges never double-count a transaction (the range is contract, not decoration)            | `IspTest`              |
| **DIP** — `CheckoutService` runs against fakes with no PSP and no database; the injected `Clock` port is what stamps the order                                                      | `DipTest`              |
| **DIP** — `domain/` depends on nothing but itself, Kotlin and the JDK; one Gradle project can't enforce that arrow, so a test does                                                  | `ArchitectureTest`     |
| **Composition** — decorator **order** is the metric: metered-outside-retry counts **1** logical charge; metered-inside counts **every** attempt                                     | `CompositionTest`      |
| **Composition** — metered-inside-retry counts **attempts**, not PSP calls: with idempotency inside the meter, a replay answered from the cache is metered but never reaches the PSP | `CompositionTest`      |
| **Composition** — `IdempotentGateway` charges the PSP **once** for a replayed key                                                                                                   | `CompositionTest`      |
| **Composition** — checking out the **same cart twice** charges the PSP once: the key is the _order_, not the attempt, so the layer can actually fire in the wired flow              | `CompositionTest`      |
| **Composition** — idempotency caches only **approved** results, so a retry layer above it still recovers from a transient failure (cache failures and retries go inert)             | `CompositionTest`      |
| **Composition** — a retry after a **lost response** is deduped by the PSP, not the local cache: the adapter sends the same key to the PSP, which returns the original charge        | `CompositionTest`      |
| **Sealed** — an exhaustive `when` over `PaymentResult` maps every variant with no `else`                                                                                            | `SealedTest`           |

> Looking for the Dynamo and Stripe SDKs the article names? Absent on purpose: the article's DIP
> section shows `DynamoOrderRepository` as the production adapter, but the runnable repo ships
> `InMemoryOrderRepository` and a no-network `StripeClient` stand-in, so the sample runs anywhere
> with zero credentials — the same swap the article discloses. That a database can become a map in
> one line of `Main.kt` is DIP's whole claim.
