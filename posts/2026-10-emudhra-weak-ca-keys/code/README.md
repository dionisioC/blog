# Code for "Eight CA Keys Between 1 and 255"

One script that answers the article's question for the machine it runs on: does any TLS client here
still trust the compromised eMudhra intermediates?

```bash
./check.sh
```

It sends one HTTPS request to each of the incident's test hosts through every client it finds: curl,
Python's `ssl` module (the OS store), Python with `certifi`, Node, Java (`Probe.java`) and Go
(`check.go`). Each line reports `TRUSTED` (the bad outcome), `REJECTED` (protected) or `ERROR` (no
answer, usually no network access). The script exits with 1 if any client trusts the chains, and
with 2 if no client reached the test hosts at all, because that proves nothing either way.

| Host                                            | Chain                                        |
| ----------------------------------------------- | -------------------------------------------- |
| `dv-g3b-emudhra.we-have-a-problem.com`          | Compromised intermediate, eMudhra root       |
| `dv-g3b-aaa-emudhra.we-have-a-problem.com`      | Same key, Sectigo `AAA Certificate Services` |
| `dv-g3b-noct-emudhra.we-have-a-problem.com`     | As the first, leaf never logged to CT        |
| `dv-g3b-aaa-noct-emudhra.we-have-a-problem.com` | As the second, leaf never logged to CT       |

Run it **where your code runs**: inside the container image
(`docker run --rm --entrypoint bash -v "$PWD:/c" my-image /c/check.sh`), in a CI job, on the host. A
result on your laptop says nothing about production.

A host with no outbound internet reports `ERROR` for every client: for this incident that's good
news, since the attacker needs to sit on a path your service actually uses.

## Java images

`check.sh` runs `Probe.java` directly, which needs a JDK 11 or later. Most images ship a JRE, so
compile it once on your machine, for any Java from 8 up:

```bash
javac --release 8 Probe.java
```

With `Probe.class` next to it, `check.sh` uses the class instead. To check only the Java in an
image, which works even when the image has no bash or curl:

```bash
docker run --rm --entrypoint java -v "$PWD:/probe:ro" my-image -cp /probe Probe          # test hosts
docker run --rm --entrypoint java -v "$PWD:/probe:ro" my-image -cp /probe Probe --roots  # trusted roots
```

Probe asks the JVM's own TLS stack, so it sees the image's `cacerts`, the `blocked.certs` fix and
any trust store set through `ENV` (such as `JAVA_TOOL_OPTIONS`). If the image's start script passes
`-Djavax.net.ssl.trustStore=…` instead, add the same flags after `my-image`, before `-cp`.

`--roots` marks `emSign ECC Root CA - G3` and `AAA Certificate Services` with `BAD`: they're the
roots behind the compromised chains. It marks `emSign Root CA - G1` and `emSign Root CA - G2` with
`DROP`: they aren't compromised, but the 24 October JDK fix (JDK-8395690) removes them too, as does
OpenJDK's [`CheckCerts.java`](https://cr.openjdk.org/~mr/CheckCerts.java) check. It exits with 1 if
any root is marked. A company image that trusts only its own CAs lists just those; one built on the
JDK's `cacerts` lists over a hundred public roots.

## Offline check

The incident site publishes every chain as PEM in
[its repository](https://github.com/galadran/we-have-a-problem.com/tree/main/chains). To test a
trust store file without any network:

```bash
openssl verify -CAfile /path/to/bundle.pem -untrusted chain.pem chain.pem
```

`OK` means the bundle still trusts the compromised intermediate.
