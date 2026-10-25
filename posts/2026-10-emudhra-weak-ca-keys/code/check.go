// go run check.go https://host/
// Uses crypto/x509 exactly as any net/http client does, including the
// distrusted-SPKI blocklist that Go 1.26.10 and 1.27.3 added.
package main

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net/http"
	"os"
	"runtime"
	"time"
)

func main() {
	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Get(os.Args[1])
	if err == nil {
		resp.Body.Close()
		fmt.Printf("TRUSTED (%s)\n", runtime.Version())
		return
	}
	var verr *tls.CertificateVerificationError
	var uerr x509.UnknownAuthorityError
	if errors.As(err, &verr) || errors.As(err, &uerr) {
		fmt.Printf("REJECTED (%s)\n", runtime.Version())
		return
	}
	fmt.Printf("ERROR %v\n", err)
}
