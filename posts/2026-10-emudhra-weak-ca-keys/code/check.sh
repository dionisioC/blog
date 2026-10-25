#!/usr/bin/env bash
# Sends one HTTPS request to each eMudhra test host through every TLS client
# found on this machine and reports whether it still trusts the compromised
# intermediates. TRUSTED is the bad outcome. Run it where your code runs.
set -uo pipefail

HOSTS=(
  dv-g3b-emudhra.we-have-a-problem.com        # eMudhra root
  dv-g3b-aaa-emudhra.we-have-a-problem.com    # Sectigo AAA cross-sign
  dv-g3b-noct-emudhra.we-have-a-problem.com   # never logged to CT
  dv-g3b-aaa-noct-emudhra.we-have-a-problem.com
)
HERE="$(cd "$(dirname "$0")" && pwd)"
trusted=0 rejected=0 errored=0

report() { # client host result
  printf '%-16s %-46s %s\n' "$1" "$2" "$3"
  case $3 in
    TRUSTED*) trusted=$((trusted + 1)) ;;
    REJECTED*) rejected=$((rejected + 1)) ;;
    *) errored=$((errored + 1)) ;;
  esac
}

curl_check() { # exit 60 is curl's "peer certificate cannot be authenticated"
  curl -sS -o /dev/null --max-time 15 "https://$1/" 2>/dev/null
  local rc=$?
  case $rc in 0) echo TRUSTED ;; 60) echo REJECTED ;; *) echo "ERROR curl exit $rc" ;; esac
}

python_ssl_check() { # urllib uses the ssl module, so the OS trust store
  python3 - "$1" <<'EOF'
import ssl, sys, urllib.request
try:
    urllib.request.urlopen(f"https://{sys.argv[1]}/", timeout=15)
    print("TRUSTED")
except Exception as e:
    print("REJECTED" if isinstance(getattr(e, "reason", e), ssl.SSLError) else f"ERROR {e}")
EOF
}

python_certifi_check() { # what requests, pip and most libraries use
  python3 - "$1" <<'EOF'
import ssl, sys, urllib.request, certifi
ctx = ssl.create_default_context(cafile=certifi.where())
try:
    urllib.request.urlopen(f"https://{sys.argv[1]}/", timeout=15, context=ctx)
    print(f"TRUSTED (certifi {certifi.__version__})")
except Exception as e:
    ok = isinstance(getattr(e, "reason", e), ssl.SSLError)
    print(f"REJECTED (certifi {certifi.__version__})" if ok else f"ERROR {e}")
EOF
}

node_check() { # Node's bundled roots, or the OS store with --use-system-ca
  node -e '
    fetch(`https://${process.argv[1]}/`, { signal: AbortSignal.timeout(15000) })
      .then(() => console.log("TRUSTED"),
            e => console.log(/CERT|ISSUER|SIGNATURE|certificate/i.test(`${e.cause?.code} ${e.cause?.message}`)
                         ? "REJECTED" : `ERROR ${e.cause?.code ?? e}`))
  ' "$1"
}

java_check() { # Probe.class runs on any Java 8+, a JRE too; Probe.java needs a JDK 11+
  local out
  if [ -f "$HERE/Probe.class" ]; then
    out=$(java -cp "$HERE" Probe "$1" 2>/dev/null)
  else
    out=$(java "$HERE/Probe.java" "$1" 2>/dev/null)
  fi
  out=$(awk -v h="$1" '$1 == h { $1 = ""; sub(/^ +/, ""); print }' <<<"$out")
  echo "${out:-ERROR java could not run Probe (on a JRE, run: javac --release 8 Probe.java)}"
}
go_check() { (cd "$HERE" && go run check.go "https://$1/"); }

for host in "${HOSTS[@]}"; do
  command -v curl    >/dev/null && report curl "$host" "$(curl_check "$host")"
  command -v python3 >/dev/null && report python-ssl "$host" "$(python_ssl_check "$host")"
  python3 -c 'import certifi' 2>/dev/null && report python-certifi "$host" "$(python_certifi_check "$host")"
  command -v node    >/dev/null && report "node $(node --version)" "$host" "$(node_check "$host")"
  command -v java    >/dev/null && report java "$host" "$(java_check "$host")"
  command -v go      >/dev/null && report "$(go env GOVERSION)" "$host" "$(go_check "$host")"
done

if ((trusted)); then
  echo "EXPOSED: $trusted check(s) still trust the compromised intermediates."
  exit 1
elif ((rejected == 0)); then
  echo "INCONCLUSIVE: no check reached the test hosts (no outbound access?)."
  exit 2
fi
echo "OK: $rejected check(s) rejected the chains, $errored could not connect."
