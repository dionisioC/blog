# Eight CA Keys Between 1 and 255: What the eMudhra Incident Means for Your Code

_Up to date as of 25 October 2026. The fixes are still changing: on 24 October Oracle shipped a
second Java fix (CVE-2026-114820), so guidance written before then calls Java builds fixed that
aren't. For anything newer, check
[the incident site's list of patches](https://emudhra.we-have-a-problem.com/#developers)._

On 17 October 2026 someone signed the sentence _"We have a problem here - Wayne"_ with the private
keys of eight intermediate certificate authorities: six trusted for TLS by Chrome, Firefox, Safari
and Windows, and two trusted by Windows for code signing. Nobody stole those keys. Nobody had to.
Each one was an integer between 1 and 255, so anyone could compute it from the public certificate in
less time than it takes to read this sentence.

Chrome and Firefox were protected within hours, Windows and Apple devices within days. Your backend
probably wasn't, and the reason says something uncomfortable about TLS outside the browser: **trust
doesn't live in the protocol, it lives in a file of root certificates that every runtime picks for
itself.** Patch the OS and you've fixed curl. You haven't fixed Node, a Go binary built last month,
or the `certifi` inside a virtualenv.

| Question                              | Short answer                                                                                                               |
| ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| **What was compromised?**             | 8 eMudhra intermediate CAs (6 TLS, 2 code signing) and their 8 Sectigo cross-signs (the same keys, also signed by Sectigo) |
| **What can an attacker do?**          | Issue a valid certificate for _any_ hostname                                                                               |
| **What else does the attacker need?** | A position on the network path between your client and the server                                                          |
| **Who was exposed?**                  | Clients that trust the roots and check neither revocation nor Certificate Transparency: most non-browser TLS               |
| **What's the fix?**                   | Update the runtime and its trust store, then rebuild everything that embeds them                                           |

One chain runs through the whole article, the real one served by the incident's test host
`dv-g3b-emudhra.we-have-a-problem.com`. We'll look at what a certificate proves, what went wrong
with these, what an attacker needs to use them, the before and after on that chain, and how to fix
the four runtimes you're most likely to ship: Java, Go, Node.js and Python.

---

## What a Certificate Actually Proves

**Definition.** A certificate binds a public key to a name, and a signature from someone else backs
that binding. That "someone else" is a certificate authority, whose own certificate is signed by
another CA, until you reach a **root**: a certificate nobody signs, which your client trusts because
it's in a file called a **trust store**. The server's own certificate, the one with the hostname in
it, is the **leaf**. The CAs between the leaf and the root are **intermediates**.

You can print the chain the test host serves:

```bash
openssl s_client -connect dv-g3b-emudhra.we-have-a-problem.com:443 -showcerts </dev/null 2>/dev/null | grep -E ' s:| i:'
```

```text
 0 s:CN=dv-g3b-emudhra.we-have-a-problem.com
   i:C=NL, O=eMudhra BV, CN=EM DV TLS CA - G3B
 1 s:C=NL, O=eMudhra BV, CN=EM DV TLS CA - G3B
   i:C=IN, O=eMudhra Technologies Limited, CN=emSign Root TLS CA - G3
 2 s:C=IN, O=eMudhra Technologies Limited, CN=emSign Root TLS CA - G3
   i:C=IN, OU=emSign PKI, O=eMudhra Technologies Limited, CN=emSign ECC Root CA - G3
```

`s:` is who each certificate speaks for and `i:` is who signed it. The server doesn't send the root;
the client has to find it in its own trust store. Drawn as a tree:

```text
dv-g3b-emudhra.we-have-a-problem.com           leaf: "this key speaks for this hostname"
└─ signed by EM DV TLS CA - G3B                intermediate: one of the eight weak keys
   └─ signed by emSign Root TLS CA - G3        intermediate
      └─ signed by emSign ECC Root CA - G3     root: the one in your trust store
```

During the handshake the server sends the chain, and the client checks three things: every signature
verifies with the issuer's public key, every certificate is in date and allowed to do what it's
doing, and the chain ends at a root **in the client's own trust store**. The last check is the only
place where trust enters the system. Everything else is arithmetic.

An intermediate's certificate shows how much power it holds. The incident's
[proof folder](https://github.com/galadran/we-have-a-problem.com/tree/main/proof) has all eight;
download one and print its limits:

```bash
curl -sSLO https://raw.githubusercontent.com/galadran/we-have-a-problem.com/main/proof/EM-OV-TLS-CA-G3C.crt.pem
openssl x509 -in EM-OV-TLS-CA-G3C.crt.pem -noout -subject -ext basicConstraints,extendedKeyUsage
```

```text
subject=C=US, O=eMudhra Inc, CN=EM OV TLS CA - G3C
X509v3 Extended Key Usage:
    TLS Web Server Authentication, TLS Web Client Authentication
X509v3 Basic Constraints: critical
    CA:TRUE, pathlen:0
```

`CA:TRUE, pathlen:0` means it may sign leaves but not further CAs. There's no `nameConstraints`
extension, so nothing limits which hostnames it can back. Whoever holds its private key can sign a
certificate for `api.your-bank.com`, and your client has no way to tell it from a real one. That's
why CA keys live in hardware security modules (HSMs: tamper-resistant devices that never let the key
out) and why key ceremonies have witnesses.

After the fact, two mechanisms can still save a client:

- **Revocation.** The CA publishes "stop trusting this one" through CRLs (lists of revoked
  certificates the CA publishes) or OCSP (a service the client asks about one certificate). It works
  only if the client checks, and most non-browser clients don't by default.
- **Certificate Transparency (CT).** Public, append-only logs where CAs record every certificate
  they issue, so anyone can see what was issued for a domain. Browsers reject a leaf that hasn't
  been logged, so a forged certificate either becomes public or gets rejected. Almost nothing
  outside the browser checks this.

> If you remember one thing: a certificate is only as trustworthy as the private key of every CA
> above it, and the trust store is a file _your client chooses_, not a property of the internet.

---

## What Went Wrong at eMudhra

A P-384 private key is a random 384-bit number `k`. The public key is `k·G`, the curve's base point
added to itself `k` times. Going forward is cheap; going back is the problem P-384's security rests
on. Mozilla's analysis in the incident report found that the eight keys
[were all integers less than 256](https://bugzilla.mozilla.org/show_bug.cgi?id=2083449): 48 bytes,
all zero except the last. With 255 candidates, "going back" means computing 255 points and comparing
them with the certificate. One write-up
[measured 28.7 milliseconds](https://lilting.ch/en/articles/emudhra-weak-intermediate-ca-keys) for
the full table.

You don't need to brute-force anything to check it. One of the keys is already public: the key of
`EM OV TLS CA - G3C` is **4**. Using the certificate you downloaded above:

```python
from cryptography import x509
from cryptography.hazmat.primitives.asymmetric import ec

cert = x509.load_pem_x509_certificate(open("EM-OV-TLS-CA-G3C.crt.pem", "rb").read())
four = ec.derive_private_key(4, ec.SECP384R1())   # the private key "4"

print(cert.public_key().public_numbers() == four.public_key().public_numbers())
# True
```

P-384 isn't broken. A key of 4 is a _valid_ key: it parses, it signs, signatures verify. Every
format check and every sign-and-verify test passes, which is exactly why nothing caught it for
almost eighteen months.

**How it happened**, according to eMudhra's preliminary explanation in the report: the keys were
generated on Utimaco HSMs during a ceremony on 30 April 2025. An approved deviation added a test of
key wrap/unwrap (exporting a key encrypted and importing it back), a flaw in that test code created
a second, weaker key pair inside the HSM, and because the labels were similar the weak pair was used
for the CSRs (certificate signing requests: the public key and name a CA is asked to sign). The root
signed them and Sectigo cross-signed them. Other participants in the report point out that an HSM's
certified key generation doesn't produce a scalar below 256 on its own, so how that material got
there is still an open question. eMudhra hasn't answered yet; it says its full report will come
within the 14 days the rules allow.

**What was affected:** eight intermediates from that ceremony.

- **Six sign HTTPS certificates:** `EM DV TLS CA`, `EM OV TLS CA` and `EM EV TLS CA`, each in a G3B
  and a G3C version. All six chain to `emSign ECC Root CA - G3`.
- **Two sign code:** `EM OV CS CA - G3B` and `EM OV CS CA - G3C`. Only Microsoft trusted them.

The names follow a pattern:

- **DV, OV and EV** say how much the CA checks about a customer before issuing. Domain Validated
  checks only that they control the domain, Organization Validated also checks that their company
  exists, and Extended Validation adds stricter legal checks.
- **G3B and G3C** are two copies of each CA, issued to two eMudhra companies: every G3B to eMudhra
  BV in the Netherlands, every G3C to eMudhra Inc in the US.

None of it matters for this incident. The checks are a promise the CA makes, not something the key
enforces, so whoever holds any of the six keys can sign for any hostname.

Sectigo's `AAA Certificate Services` root had cross-signed the same eight public keys (signed them
too, so they also chain to Sectigo's root), and a cross-sign with the same key is the same
compromise. eMudhra itself issued no end-entity certificates (leaves) from them. Whether anyone else
did in the eighteen months before the report, nobody can prove either way for clients that don't
check CT.

eMudhra has since
[announced that it is leaving the public TLS business](https://emudhra.com/en/press-release/emudhra-to-exit-global-ssl-tls-certificate-business),
and some trust stores now drop more than the one root behind these keys. Java's second update
removes all three eMudhra roots the JDK carried, and certifi 2026.10.23 drops two more (eMudhra
Inc's `emSign Root CA - C1` and `emSign ECC Root CA - C3`). If a server you call still uses an
eMudhra certificate, expect it to fail once you update.

The incident site keeps
[the authoritative list of who has patched](https://emudhra.we-have-a-problem.com/#developers).

> If you remember one thing: the keys weren't stolen, they were _computable_, from public data, by
> anyone, for almost eighteen months. Treat every client that trusted these roots as if the keys had
> been public all along, because they effectively were.

---

## Why It's Exploitable, and What an Attacker Still Needs

With an intermediate key, an attacker signs a leaf for whatever hostname your service calls. The
chain validates. But a certificate alone does nothing: the attacker has to **receive your
connection** to present it. That means a position on the path:

- DNS answers they control (poisoning, a compromised resolver, a hijacked record).
- A network device in the middle: a proxy, a router, a hostile Wi-Fi, a compromised egress gateway.
- A routing attack (BGP hijacking: announcing the destination's IP addresses from a network the
  attacker controls, so traffic goes there).

This isn't remote code execution, and nobody can scan the internet with it. It turns a network
position, which TLS normally makes harmless, into full read-and-write access to the traffic.

So why were browsers safe and your backend not? Because of the two mechanisms above:

- **Revocation.** eMudhra revoked the intermediates on 18 October. Python, Node, curl and Go on
  Linux don't check revocation by default, and neither does Java's TLS stack unless you set
  `com.sun.net.ssl.checkRevocation=true`. (Go on macOS and Windows asks the OS verifier, so it
  picked up Apple's and Microsoft's blocks.)
- **Certificate Transparency.** No certificate signed by these intermediates appeared in the public
  CT logs before the block. A browser rejects unlogged certificates, so a forgery would have had to
  show up in public first. Non-browser clients don't check CT, so for them a forged certificate
  needs no log entry at all.

Browsers also receive blocklists pushed by the vendor (Chrome's CRLSet, Firefox's OneCRL) without a
software update. Your runtime gets nothing until you upgrade it.

Forged certificates stopped being hypothetical on 20 October. Over the next day and a half, people
logged [107,107 of them](https://bugzilla.mozilla.org/show_bug.cgi?id=2083449#c44), signed with six
of the keys, to public CT logs: 106,050 are proof-of-concept certificates for `google.com`, and
every signature verifies. All but two carry no revocation pointer (the CRL or OCSP address a client
would ask), so a client that checks revocation only for the leaf, a common setup, finds nothing to
check. Only a client that checks every CA in the chain, receives a pushed blocklist or has dropped
the roots rejects them.

What that means for a typical service:

| Your code talks to…                                                    | Exposure                        | Why                                                             |
| ---------------------------------------------------------------------- | ------------------------------- | --------------------------------------------------------------- |
| Internal services, with a trust store holding **only** your private CA | None                            | The eMudhra roots are never consulted                           |
| Internal services, with the default trust store                        | Low                             | The attacker must already be inside your network                |
| External APIs, SaaS, your cloud provider's endpoints                   | Real, needs a man-in-the-middle | The path crosses networks you don't control                     |
| Package registries from CI or laptops                                  | Unlikely, high impact           | A forged registry can serve a tampered package into every build |
| Browsers                                                               | None                            | Pushed blocklists and CT                                        |

Note the second row. "We only call internal services" protects you only if the client _trusts_ only
internal CAs. Most services keep the default trust store and append their private CA to it, so a
forged certificate for `orders.internal` still validates. It's just much harder to deliver.

> If you remember one thing: the key is the weapon and the network position is the trigger. Your
> risk is how many networks you don't control sit between your clients and the servers they call.

---

## Before and After

The incident site runs test hosts that serve the compromised chains. Run the check **from where your
code runs**, a pod, a container or a CI job, not from your laptop:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' https://dv-g3b-emudhra.we-have-a-problem.com/      # eMudhra root
curl -sS -o /dev/null -w '%{http_code}\n' https://dv-g3b-aaa-emudhra.we-have-a-problem.com/  # Sectigo cross-sign
```

```text
# Before: the client trusts the compromised intermediate
200

# After: trust removed (wording depends on curl's TLS backend; this is OpenSSL)
curl: (60) SSL certificate problem: unable to get local issuer certificate
```

Two more hosts serve leaves that were **never logged to CT**:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' https://dv-g3b-noct-emudhra.we-have-a-problem.com/
curl -sS -o /dev/null -w '%{http_code}\n' https://dv-g3b-aaa-noct-emudhra.we-have-a-problem.com/
```

A `200` here is the worst result: your client trusts the compromised intermediates _and_ doesn't
enforce CT, so it could be attacked without anything ever appearing in a public log.

curl only tests curl's trust store. Your service uses its runtime's, so the
[companion script](https://github.com/dionisioC/blog/tree/main/posts/2026-10-emudhra-weak-ca-keys/code)
runs the same request through curl, Python, Node, Java and Go, whichever are installed.

**Before you run it at work.** Each check opens one HTTPS connection to a test host. The
[incident site](https://emudhra.we-have-a-problem.com/) runs them; Oracle and OpenJDK link to it,
and its source is [on GitHub](https://github.com/galadran/we-have-a-problem.com). The hosts see what
any website sees: your public IP, the hostname and the time. curl, Python, Node and Go send a plain
`GET /`, and the Java probe stops after the TLS handshake. A host your client trusts answers with
one line of text, which the scripts throw away. On a company network, three things matter:

- A connection that presents a certificate from a revoked, compromised CA is exactly what a security
  proxy or intrusion detection system should flag. Tell your security team before you run it.
- If a proxy inspects TLS, your client checks the proxy's certificate instead of the test host's, so
  the result tells you nothing about your runtime.
- Proxies often block newly registered domains. That shows up as `ERROR`, which proves nothing
  either way.

If you can't make the call, the offline check below and `Probe --roots` in the Java section need no
connection to the test hosts.

**Offline, with no network at all.** The site also publishes
[every chain as PEM](https://github.com/galadran/we-have-a-problem.com/tree/main/chains) (the text
format that starts with `-----BEGIN CERTIFICATE-----`), so you can check a trust store file
directly. Here is the same chain against `certifi` before and after the fix:

```text
$ openssl verify -CAfile certifi-2026.7.22.pem -untrusted chain.pem chain.pem
chain.pem: OK

$ openssl verify -CAfile certifi-2026.10.19.pem -untrusted chain.pem chain.pem
C = IN, O = eMudhra Technologies Limited, CN = emSign Root TLS CA - G3
error 20 at 2 depth lookup: unable to get local issuer certificate
error chain.pem: verification failed
```

`OK` means the bundle still trusts the compromised intermediate. Error 20 at depth 2 means the chain
now dead-ends one step before the root: `emSign ECC Root CA - G3` is gone from the bundle. The
Sectigo chain (`dv-g3b-aaa`) fails against _both_ versions, because `certifi` doesn't carry
`AAA Certificate Services` at all. That cross-sign path matters for stores that do: Windows, the
`/etc/ssl/cert.pem` macOS ships, and the Linux stores the incident site gives workarounds for.

> If you remember one thing: test with the runtime your service uses, from where it runs. A green
> curl on your laptop says nothing about a Node container in production.

---

## Why Every Runtime Needs Its Own Fix

Each runtime answers "which roots do I trust?" differently, so each one got a different fix:

| Runtime                                 | Where its roots come from                                                 | What the fix does                                                                           | Fixed in                                  | CVE                             |
| --------------------------------------- | ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- | ----------------------------------------- | ------------------------------- |
| **Java**                                | The JDK's `lib/security/cacerts`; distro packages link it to the OS store | Distrusts the intermediates through `blocked.certs`, then removes four roots from `cacerts` | 24 Oct updates                            | CVE-2026-91403, CVE-2026-114820 |
| **Go**                                  | The OS store on Linux; the OS verifier on macOS and Windows               | A hardcoded blocklist in `crypto/x509`, **compiled into your binary**                       | 1.26.10, 1.27.3                           | CVE-2026-111943                 |
| **Node.js**                             | Mozilla's bundle, **compiled into the `node` binary**                     | NSS 3.131.1 roots, and rejects chains with the blocked public keys                          | 22.23.4, 24.22.1, 26.12.1                 | none                            |
| **Python** (`ssl`, `urllib`)            | The OS store                                                              | Whatever your distro's `ca-certificates` does                                               | Your distro's package                     | none                            |
| **Python** (`requests`, pip, many libs) | `certifi`, a bundle **inside each virtualenv**                            | Drops `emSign ECC Root CA - G3`                                                             | certifi 2026.10.19, pip 26.2.2, uv 0.13.5 | none                            |
| **curl, git, OpenSSL**                  | The OS store                                                              | Drops the root                                                                              | e.g. Ubuntu `20260816ubuntu0.24.04.1`     | none                            |

Go's fix is a list of the eight compromised public keys (their SHA-256 hashes) in `crypto/x509`, and
`Verify` rejects any chain that contains one of them. Blocking by **public key** rather than by
certificate is the right call: the eMudhra certificate and the Sectigo cross-sign carry the same
key, so one entry covers both. And because the list is part of the standard library, it ends up
inside every binary you build. Upgrading the Go installed on a server changes nothing for a binary
that's already compiled: you have to rebuild it with 1.26.10 or 1.27.3.

Two consequences are easy to miss:

- **"Update the OS" is not a fix for Node or `certifi`.** Neither reads the OS store by default. For
  Go on Linux it helps, because it drops the root, but the blocklist only arrives with a rebuild.
- **Your CVE scanner won't flag Node or Python.** Neither fix has a CVE, so a scanner that matches
  CVEs to versions reports them clean. Check those two by version. For Java, make sure the scanner
  knows CVE-2026-114820: against the first CVE alone, the 21 October builds look fixed.

> If you remember one thing: ask of every process, "where does this get its roots, and who updates
> that file?" The answer is different for each runtime, and sometimes it's "a file compiled into a
> binary you built last month."

---

## Fixing It, Runtime by Runtime

### Java

Oracle shipped two fixes three days apart, and only the second one is enough. Both score 9.1 and
cover Java SE 8, 11, 17, 21, 25 and 27, GraalVM for JDK 17 and 21, and GraalVM Enterprise Edition
21.3.

- [CVE-2026-91403](https://www.oracle.com/security-alerts/alert-cve-2026-91403.html), 21 October:
  distrusts the eight intermediates and their eight Sectigo cross-signs through `blocked.certs`.
  Oracle's builds 11.0.33.0.2, 17.0.21.0.2, 21.0.13.0.2, 25.0.5.0.2, 27.0.1.0.2 and 8u511 b32.
- [CVE-2026-114820](https://www.oracle.com/security-alerts/alert-cve-2026-114820.html), 24 October:
  also removes four roots from `cacerts`: `emSign ECC Root CA - G3`, `emSign Root CA - G1`,
  `emSign Root CA - G2` and Sectigo's `AAA Certificate Services`. It lists the 21 October builds as
  affected. Oracle's fixed builds are 11.0.33.0.3, 17.0.21.0.3, 21.0.13.0.3, 25.0.5.0.3, 27.0.1.0.3
  and 8u511 b33. Java 8 keeps the update number and bumps the build, so `java -version` has to say
  `1.8.0_511-b33`.

Only the first and the last of those roots are behind the compromised chains; the JDK drops
eMudhra's other two as well.

- **Update the JDK** in every place it lives: Docker base images, `setup-java` in CI, self-hosted
  runners, laptops. For Docker, rebuild your own image with `docker build --pull` so it starts from
  the updated base. Redeploying an image you already built, or giving it a new tag, keeps the old
  JDK inside it. Other vendors (Temurin, Corretto, Zulu and the rest) number their builds
  differently. OpenJDK's [eMudhra page](https://openjdk.org/emudhra), last updated on 24 October,
  says to look for JDK-8395690 / CVE-2026-114820 in your vendor's release notes, "even if you have
  updated your runtime in the past few days". It also publishes
  [`CheckCerts.java`](https://cr.openjdk.org/~mr/CheckCerts.java), which prints `BAD ROOT` for each
  of the four roots a runtime still trusts.
- **Base images lag behind vendor releases.** On 25 October, three days after Temurin's October
  builds came out, the official `eclipse-temurin:21-jre`, `21-jre-alpine` and `25-jre-alpine` images
  still shipped 21.0.12.1 and 25.0.4.1, and `Probe` (below) still saw them trust the test hosts.
  Until the image you build on is updated, `--pull` changes nothing: check the result, don't assume
  it.
- **If your vendor hasn't shipped the fix yet, remove the four roots yourself**, then restart the
  JVM. OpenJDK's page deletes them by alias, but alias names differ between vendors (Temurin calls
  the first one
  `cn_emsign_ecc_root_ca__g3,o_emudhra_technologies_limited,ou_emsign_pki,c_in [jdk]`), so find them
  by subject instead:

  ```bash
  keytool -list -v -cacerts -storepass changeit \
    | awk '/^Alias name: /{a=substr($0,13)} /^Owner: .*CN=(emSign Root CA - G[12]|emSign ECC Root CA - G3|AAA Certificate Services)(,|$)/{print a}' \
    | while IFS= read -r a; do keytool -delete -cacerts -storepass changeit -alias "$a"; done
  ```

  It prints nothing when it works, and nothing when there's nothing to delete. To see what it will
  remove, run the first two lines on their own: they print the aliases that match. On
  `eclipse-temurin:21-jre`:

  ```text
  cn_emsign_ecc_root_ca__g3,o_emudhra_technologies_limited,ou_emsign_pki,c_in [jdk]
  cn_emsign_root_ca__g1,o_emudhra_technologies_limited,ou_emsign_pki,c_in [jdk]
  ```

  Run them again afterwards: no output means the roots are gone. Deleting needs write access to
  `cacerts`, so a system JDK needs `sudo`; without it, `keytool` stops with `Permission denied`.

  `changeit` is the default password of the JDK's `cacerts`. Java 8's `keytool` has no `-cacerts`
  option, so replace it with `-keystore` and the file: `$JAVA_HOME/jre/lib/security/cacerts` in a
  JDK, `$JAVA_HOME/lib/security/cacerts` in a JRE. A vendor's `cacerts` doesn't always hold all four
  (Temurin's has `emSign Root CA - G1` and `emSign ECC Root CA - G3` only), and the command deletes
  whichever it finds. Whether you update or remove them by hand, servers that chain to
  `AAA Certificate Services` or to eMudhra's other roots stop working, so test the hosts you call
  before rolling it out.

- **In a Docker image**, the keystore is baked into the image, so the removal goes in your
  Dockerfile, after the `FROM`:

  ```dockerfile
  FROM eclipse-temurin:21-jre
  RUN keytool -list -v -cacerts -storepass changeit \
      | awk '/^Alias name: /{a=substr($0,13)} /^Owner: .*CN=(emSign Root CA - G[12]|emSign ECC Root CA - G3|AAA Certificate Services)(,|$)/{print a}' \
      | while IFS= read -r a; do keytool -delete -cacerts -storepass changeit -alias "$a"; done
  ```

  To check an image on your machine, ask the Java inside it. The companion's
  [`Probe.java`](https://github.com/dionisioC/blog/tree/main/posts/2026-10-emudhra-weak-ca-keys/code)
  connects to the test hosts through the JVM's own TLS stack, so it sees what your application sees:
  the image's `cacerts`, the `blocked.certs` fix and any trust store the image configures. The
  version string can't tell you that, least of all for a company's own JDK build. Compile it once
  with `javac --release 8 Probe.java`, then run it with the image's Java (8 or later; a JRE is
  enough):

  ```bash
  docker run --rm --entrypoint java -v "$PWD:/probe:ro" my-image -cp /probe Probe
  ```

  Any `TRUSTED` line means the image is still exposed. `--entrypoint` makes this work even when the
  image starts your application by default, and settings in its `ENV`, such as `JAVA_TOOL_OPTIONS`,
  still apply. If the start script passes `-Djavax.net.ssl.trustStore=…` instead, add the same flags
  after `my-image`. On your laptop, `java -cp . Probe` does the same for your own JDK.

- **Company images: only your CAs, or the public roots too?** The answer tells you which row of the
  exposure table above you're in. `Probe --roots` lists every root the image's Java trusts, and it
  needs no network:

  ```bash
  docker run --rm --entrypoint java -v "$PWD:/probe:ro" my-image -cp /probe Probe --roots
  ```

  ```text
  /opt/java/openjdk 21.0.12.1+1-LTS, trustStore=/opt/corp/truststore.p12
       CN=ACME Corp Root CA,O=ACME
  1 trusted roots: 0 BAD (behind the compromised chains), 0 DROP (also removed by the 24 October fix)
  ```

  That image trusts one private root and nothing else. The stock `eclipse-temurin:21-jre` lists 118
  roots, including:

  ```text
  BAD  CN=emSign ECC Root CA - G3,O=eMudhra Technologies Limited,OU=emSign PKI,C=IN
  DROP CN=emSign Root CA - G1,O=eMudhra Technologies Limited,OU=emSign PKI,C=IN
  ```

  `BAD` marks a root behind the compromised chains. `DROP` marks eMudhra's other two roots: they
  aren't compromised, but the 24 October fix removes them too, and OpenJDK's `CheckCerts` flags them
  as well. Either label makes `Probe` exit with 1. On a fixed JDK no line is labelled, the summary
  reads `0 BAD …, 0 DROP …`, and `Probe` exits with 0; the number of roots varies by vendor and
  doesn't matter. Ask Java rather than reading the trust store file with `keytool`: a PKCS12 file
  (the standard keystore format, Java's default since 9) protected by a password lists no entries
  without that password, so it looks empty when it isn't. Java gets the password from the image's
  own settings.

  This covers Java only. curl, git, Python's `ssl` module and Go read the OS bundle
  (`/etc/ssl/certs/ca-certificates.crt` on Debian, Ubuntu and Alpine), and company images often add
  their CA to it rather than replace it. `docker create` lets you copy it out without starting
  anything:

  ```bash
  id=$(docker create my-image) && docker cp -L "$id":/etc/ssl/certs/ca-certificates.crt . ; docker rm "$id"
  openssl crl2pkcs7 -nocrl -certfile ca-certificates.crt | openssl pkcs7 -print_certs -noout | grep '^subject'
  ```

- **Know which `cacerts` you use.** `readlink -f "$JAVA_HOME/lib/security/cacerts"` tells you. JDK
  packages on Fedora, RHEL, Arch, Gentoo and Oracle Linux read the OS store, so updating
  `ca-certificates` is likely enough there. Homebrew's and FreeBSD's OpenJDK carry their own
  `cacerts`, so upgrade the JDK package itself: `brew upgrade openjdk`, not `brew update`, which
  only refreshes Homebrew's list of packages. On 25 October only Homebrew's `openjdk` (27.0.1.0.3)
  had the 24 October fix. `openjdk@21` (21.0.13.0.2) still trusted all four roots, and the other
  versioned packages were at the same or older builds, so remove the roots by hand there. On Debian
  and Ubuntu, `cacerts` points to `/etc/ssl/certs/java/cacerts`, and a
  [long-standing bug](https://bugs.debian.org/cgi-bin/bugreport.cgi?bug=1069251) means updating
  `ca-certificates` doesn't reach it. Regenerate it as root:

  ```bash
  rm -f /etc/ssl/certs/java/cacerts
  update-ca-certificates --fresh
  ```

- **Embedded JREs.** Jenkins, Kafka, Elasticsearch, IDEs and many vendor appliances ship their own
  Java. Updating the system JDK doesn't touch them, so update the product.
- **Revocation checking instead of removing the roots.** OpenJDK and Oracle both accept
  `-Dcom.sun.net.ssl.checkRevocation=true -Dcom.sun.security.enableCRLDP=true` as the alternative.
  Java checks every certificate in the chain, not just the leaf, so it also rejects the forged
  certificates that carry no revocation pointer: with those two flags after `my-image`, `Probe`
  rejects all four test hosts even on an unpatched `eclipse-temurin:21-jre`. It costs you a CRL
  fetch on new chains and can fail handshakes when a CRL endpoint is unreachable. eMudhra's CRL and
  OCSP servers have also been
  [refusing curl's, Wget's and Python's User-Agents](https://bugzilla.mozilla.org/show_bug.cgi?id=2085612)
  (Java's got through on 25 October), so test it before rolling it out. For signed JARs, JDK 17 and
  later can check revocation with `jarsigner -verify -revcheck -strict app.jar`.
- **If you can't update yet**, Oracle also publishes
  [a replacement `blocked.certs`](https://ops.java/emudhra) for `lib/security/` (on Java 8 the file
  is `blacklisted.certs`). It covers only the first CVE, so remove the roots as well.

### Go

- **See what a binary was built with**, not what's installed: `go version ./bin/my-service` prints
  the toolchain embedded in the file.
- **Rebuild with 1.26.10 or 1.27.3** and redeploy. Pin it where your build reads it: a
  `toolchain go1.26.10` line in `go.mod`, `go-version` in `setup-go`, the `golang:` builder image.
  Go 1.25 and older get no fix.
- **Rebuild the runtime image too.** On Linux, Go still reads the OS store, so a `distroless` or
  `alpine` base from last month keeps the old roots until you pull a new one.

### Node.js

- **Check what your `node` trusts**, from the container that runs it:

  ```bash
  node -e "const {X509Certificate}=require('crypto');
    const hit=require('tls').rootCertificates.some(p=>new X509Certificate(p).subject.includes('emSign ECC Root CA - G3'));
    console.log(process.version, hit ? 'STILL TRUSTS emSign ECC Root CA - G3' : 'root removed')"
  # v22.22.0 STILL TRUSTS emSign ECC Root CA - G3
  ```

- **Upgrade to 22.23.4, 24.22.1 or 26.12.1** everywhere you pin a version: `.nvmrc`, `engines`,
  `setup-node`, the `node:` base image, Lambda runtimes. These releases also fix other CVEs,
  including a Brotli out-of-bounds write (CVE-2026-111497), so they're worth taking anyway.
- **Node 20 and older get nothing.** Node 20 reached end of life in April 2026.
- **Mind the flags.** `NODE_EXTRA_CA_CERTS` only _adds_ roots, it never removes one.
  `--use-system-ca` and `--use-openssl-ca` switch Node to the OS store, which makes the OS package
  part of your fix.
- Electron, Bun and Deno bundle their own roots and released their own fixes.

### Python

- **Check every environment**, not the interpreter:
  `python -c "import certifi; print(certifi.__version__)"` inside each virtualenv, image and lambda.
- **Pin `certifi>=2026.10.19`** and regenerate your lockfile so the pin actually lands. Upgrade pip
  to 26.2.2 or later and uv to 0.13.5 or later. uv ships its own roots, so it needed its own fix:
  0.13.4 removed the root and 0.13.5 also rejects chains that carry the compromised keys.
- **New Python releases don't fix old virtualenvs.** 3.15.1, 3.14.9 and 3.13.17 bundle a newer pip
  for new installs. Existing environments keep whatever `certifi` they had.
- **Look for vendored bundles.** Some libraries ship a `.pem` inside the package and ignore both the
  OS and `certifi`: `find .venv -path '*site-packages*' -name '*.pem'` lists them.
- Code that uses `ssl` or `urllib` directly follows the OS store, so it's fixed by
  `ca-certificates`.

### The OS and Your Images

- **Update `ca-certificates`** on hosts and in images. Patched versions include Debian
  `20250419~deb12u2` / `20250419+deb13u1`, Ubuntu `20260816ubuntu0.24.04.1` (and its 22.04, 26.04
  and 26.10 equivalents), Alpine `20261020-r0` and Fedora `2026.2.92_v9.0.319`.
- **Rebuild, don't just redeploy.** An image keeps the trust store it was built with. Images pinned
  by digest or built from a cached layer never pick up the fix. `docker build --pull` and check:
  `docker run --rm my-image dpkg -l ca-certificates` (or `apk info -v ca-certificates` on Alpine).
- **macOS has no `ca-certificates` package.** Safari, Mail and apps built on Apple's frameworks
  already block these certificates: Apple pushes that change to devices without an OS update. The
  command-line tools that ship with macOS (curl, git, python3, ruby, perl, openssl) read
  `/etc/ssl/cert.pem` instead, which keeps the root until a future macOS update.
  [Apple's note](https://support.apple.com/en-us/149555) and
  [the incident site](https://emudhra.we-have-a-problem.com/#workarounds) give the commands to
  remove it yourself; a macOS update may put it back. The built-in `pip3` uses its own, older copy
  of the roots, and the incident site shows how to point it at the fixed file. Tools installed with
  Homebrew use Homebrew's own bundle: `brew upgrade ca-certificates`.

### CI/CD and Your Dependencies

The CI risk is a forged registry serving a tampered package. TLS was the only thing standing between
your build and that package, unless something else checks the content:

| Ecosystem | Content check that survives a broken TLS                                                               |
| --------- | ------------------------------------------------------------------------------------------------------ |
| Go        | `go.sum` plus the checksum database, whose signing key ships inside `go` itself                        |
| npm       | `integrity` hashes in `package-lock.json`, when installing with `npm ci`                               |
| Python    | `pip install --require-hashes`, or `uv.lock`                                                           |
| Gradle    | Dependency verification metadata (`--write-verification-metadata sha256`)                              |
| Maven     | Checksums travel over the same connection and PGP isn't checked by default, so nothing, out of the box |

A job that resolves dependencies from scratch, without a lockfile, trusts TLS and nothing else.

### Managed Runtimes

On AWS Lambda, a function on a managed runtime in **Auto** update mode gets the patched runtime once
AWS publishes one, rolled out in phases. **Function update** mode only updates when you deploy the
function, **container images** need a rebuild, and anything still on `nodejs20.x` won't be patched
at all. The `INIT_START` line in the function's logs shows the runtime version it started with.

---

## The Checklist

- [ ] **Inventory** every runtime and version per service: Dockerfiles, CI workflows, `go.mod`,
      `.nvmrc`, `requirements*.txt`, lockfiles, Lambda configs.
- [ ] **Java**: JDK at a 24 October build (release notes list JDK-8395690) in images, CI, runners,
      laptops and embedded JREs, or the four roots removed (or revocation checking on), and `Probe`
      rejecting all four test hosts.
- [ ] **Go**: every deployed binary rebuilt with 1.26.10 or 1.27.3 (`go version ./binary`).
- [ ] **Node.js**: 22.23.4, 24.22.1 or 26.12.1 everywhere; nothing left on Node 20.
- [ ] **Python**: `certifi>=2026.10.19` in every environment, pip ≥ 26.2.2, uv ≥ 0.13.5, lockfiles
      regenerated, vendored `.pem` files reviewed.
- [ ] **OS**: `ca-certificates` updated on hosts and in base images; images rebuilt with `--pull`;
      on macOS, `/etc/ssl/cert.pem` fixed by hand until Apple's update.
- [ ] **Custom trust stores**: any `-Djavax.net.ssl.trustStore`, `REQUESTS_CA_BUNDLE`,
      `SSL_CERT_FILE` or bundled `.pem` checked for `emSign ECC Root CA - G3`.
- [ ] **Third-party software**: agents, appliances, backup tools and anything else that makes its
      own TLS connections, checked against its vendor's notice.
- [ ] **CI**: dependency verification on (lockfiles with hashes, Gradle verification metadata).
- [ ] **Verify** from where the code runs: the test hosts reject, including the `noct` ones.
- [ ] **Don't stop at the scanner**: Node and Python have no CVE for this.

---

## Trust Is a File You Ship

Here's the whole article as two questions to ask of any process that opens a TLS connection:

1. **"Where does this get its roots?"** The OS store, a bundle inside the runtime, a package in the
   virtualenv, a list compiled into the binary. If the answer is "I don't know", that's the finding.
2. **"Who updates that file, and when do I pick it up?"** Your distro, your runtime's release, your
   lockfile, your next build. Anything that answers "the next build" is only fixed once you build.

None of this is new. DigiNotar in 2011 and the Symantec distrust in 2018 taught the same lesson, and
browsers answered it with pushed blocklists and Certificate Transparency. Most server-side clients
got neither. Until they do, the trust store is a dependency like any other: it has versions, it goes
stale, and it ships inside your artifacts whether you think about it or not.

---

_The check script lives in
[the companion folder](https://github.com/dionisioC/blog/tree/main/posts/2026-10-emudhra-weak-ca-keys/code):
it sends the same request to the four test hosts through curl, Python (`ssl` and `certifi`), Node,
Java and Go, whichever are installed, and prints which ones still trust the compromised chains. The
chains and the proof certificates used above come from
[the incident site's repository](https://github.com/galadran/we-have-a-problem.com)._
