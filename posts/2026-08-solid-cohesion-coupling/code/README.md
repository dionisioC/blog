# Code for "SOLID Without the Acronym"

One cohesive **checkout / payments** domain that exercises every principle in the article: the same
system the prose walks through, made runnable. Each test **is** an assertion the article makes in
words, so the claims can't rot. The article's code blocks are trimmed excerpts of these files; if
the two ever disagree, the repo is right.

## Layout

```text
src/main/kotlin/dev/dionisioc/checkout/
  domain/            # no infra imports, enforced by ArchitectureTest — Money, Cart, Order,
                     #   Receipt, PaymentResult and RefundResult (sealed),
                     #   ports (PaymentGateway, PaymentReader, OrderRepository, Clock),
                     #   PaymentMethod/RefundableMethod + RefundFlow + registry, PriceCalculator,
                     #   ReceiptFormatter, CheckoutService, StoreCredit + SupportCreditFlow
                     #   (where gift-card refunds land)
  infrastructure/    # StripePaymentGateway (+PaymentReader) and its StripeClient stand-in,
                     #   InMemoryOrderRepository, Meter, KeyStore, and the by-delegation decorators
  clients/           # the driving side — StatementsScreen (ISP's read-only role view) and
                     #   RefundHandler (goes through RefundFlow, never the gateway port);
                     #   imports domain, never the reverse
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

| Article claim                                                                                                                                                                                                           | Test                   |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------- |
| **SRP** — in `CheckoutManager`, one edit to the shared reward rule moves Finance's discount **and** Marketing's receipt, while every item stays billed either way                                                       | `SrpTest`              |
| **SRP** — in the split, Finance's rule skips gift wrap while Marketing's receipt still awards points on it: two copies of a rule that change for different reasons                                                      | `SrpTest`              |
| **OCP** — the registry dispatches by name with no type switch; an unknown method fails fast, and so does registering a name twice                                                                                       | `OcpTest`              |
| **LSP** — `StoreCredit` holds `balance() >= 0`; `VipStoreCredit` drives it **negative** through the base reference (Form 1, the silent wrong answer) and keeps the parent's guard, so that's the only promise it breaks | `LspTest`              |
| **LSP** — the base `StoreCredit` keeps its own promise first: `topUp` and `redeem` reject negative amounts, and `Money` arithmetic throws on overflow instead of wrapping                                               | `LspTest`, `MoneyTest` |
| **LSP** — `GiftCardPayment` is **not** a `RefundableMethod`: `RefundFlow` refunds by order, reverses a card charge, and answers a gift-card order `NotRefundable` (no `refund` to call, nothing to throw)               | `LspTest`              |
| **LSP** — the refund a gift card can't take comes back as store credit through `SupportCreditFlow`; `RefundHandler`, the client, goes through the same gate and never holds the port                                    | `LspTest`              |
| **ISP** — one Stripe adapter is seen as a read-only `PaymentReader` by `StatementsScreen` and a money-moving `PaymentGateway` by `CardPayment`; both are views of the same object                                       | `IspTest`              |
| **ISP** — the reader answers for the range it was asked and no wider; adjacent half-open ranges never double-count a transaction (the range is contract, not decoration)                                                | `IspTest`              |
| **DIP** — `CheckoutService` runs against fakes with no PSP and no database; the injected `Clock` port is what stamps the order                                                                                          | `DipTest`              |
| **DIP** — `domain/` depends on nothing but itself, Kotlin, and the JDK's `java.time` and `java.util`; one Gradle project can't enforce that arrow, so a test does                                                       | `ArchitectureTest`     |
| **Composition** — decorator **order** is the metric: metered-outside-retry counts **1** logical charge; metered-inside counts **every** attempt                                                                         | `CompositionTest`      |
| **Composition** — metered charges aren't PSP calls: with idempotency inside the meter, a replay answered from the cache is metered but never reaches the PSP                                                            | `CompositionTest`      |
| **Composition** — `IdempotentGateway` charges the PSP **once** for a replayed key, and refuses a key reused for a different amount or method                                                                            | `CompositionTest`      |
| **Composition** — checking out the **same cart twice** charges the PSP once (the key is the _order_, not the attempt), and the replay keeps the order it first recorded                                                 | `CompositionTest`      |
| **Composition** — a cart **edited after payment** is refused, so the recorded order always matches what the PSP charged                                                                                                 | `CompositionTest`      |
| **Composition** — only a `Timeout` is retried: idempotency caches only **approved** results, so a retry layer above it still recovers, and a hard decline is never retried                                              | `CompositionTest`      |
| **Composition** — a retry after a **lost response** is deduped by the PSP, not the local cache, and the PSP refuses the key if the amount changed                                                                       | `CompositionTest`      |
| **Sealed** — an exhaustive `when` over `PaymentResult` maps every variant with no `else`                                                                                                                                | `SealedTest`           |

> Looking for the Dynamo and Stripe SDKs the article names? Absent on purpose: the article's DIP
> section shows `DynamoOrderRepository` as the production adapter, but the runnable repo ships
> `InMemoryOrderRepository` and a no-network `StripeClient` stand-in, so the sample runs anywhere
> with zero credentials: the same swap the article discloses. That a database can become a map in
> one line of `Main.kt` is DIP's whole claim.
