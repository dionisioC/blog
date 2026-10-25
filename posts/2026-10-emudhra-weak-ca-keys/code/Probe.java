import java.net.URI;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

// Asks this JVM's own TLS stack, so the answer covers its cacerts, blocked.certs and any
// -Djavax.net.ssl.trustStore the image sets.
//   javac --release 8 Probe.java    compile once, on any JDK
//   java -cp . Probe [host ...]     connect to the test hosts (Java 8+, a JRE is enough)
//   java -cp . Probe --roots        list every root this JVM trusts
public class Probe {
    private static final String[] TEST_HOSTS = {
        "dv-g3b-emudhra.we-have-a-problem.com",          // eMudhra root
        "dv-g3b-aaa-emudhra.we-have-a-problem.com",      // Sectigo AAA cross-sign
        "dv-g3b-noct-emudhra.we-have-a-problem.com",     // never logged to CT
        "dv-g3b-aaa-noct-emudhra.we-have-a-problem.com",
    };
    private static final String[] BAD_ROOTS = {"CN=emSign ECC Root CA - G3", "CN=AAA Certificate Services"};
    // Not behind the compromised chains, but the 24 October JDK fix (JDK-8395690) removes them too.
    private static final String[] DROPPED_ROOTS = {"CN=emSign Root CA - G1", "CN=emSign Root CA - G2"};

    public static void main(String[] args) throws Exception {
        System.out.println(System.getProperty("java.home") + " " + System.getProperty("java.runtime.version")
                + ", trustStore=" + System.getProperty("javax.net.ssl.trustStore", "default cacerts"));
        if (args.length == 1 && args[0].equals("--roots")) {
            System.exit(listRoots());
        }
        System.exit(connect(args.length > 0 ? args : TEST_HOSTS));
    }

    // Exit 1 if any host is trusted, 2 if none answered, 0 if all rejected.
    private static int connect(String[] hosts) {
        int trusted = 0, rejected = 0;
        for (String host : hosts) {
            String result;
            try {
                HttpsURLConnection c = (HttpsURLConnection) URI.create("https://" + host + "/").toURL().openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                c.connect();
                c.disconnect();
                result = "TRUSTED";
                trusted++;
            } catch (SSLHandshakeException e) {
                result = "REJECTED";
                rejected++;
            } catch (Exception e) {
                result = "ERROR " + e;
            }
            System.out.printf("%-46s %s%n", host, result);
        }
        return trusted > 0 ? 1 : rejected == 0 ? 2 : 0;
    }

    // Exit 1 if any root the 24 October fix removes is still trusted.
    private static int listRoots() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        List<String> roots = new ArrayList<>();
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                for (X509Certificate root : ((X509TrustManager) tm).getAcceptedIssuers()) {
                    roots.add(root.getSubjectX500Principal().getName());
                }
            }
        }
        Collections.sort(roots);
        int bad = 0, dropped = 0;
        for (String root : roots) {
            String label = "     ";
            if (matches(root, BAD_ROOTS)) {
                label = "BAD  ";
                bad++;
            } else if (matches(root, DROPPED_ROOTS)) {
                label = "DROP ";
                dropped++;
            }
            System.out.println(label + root);
        }
        System.out.println(roots.size() + " trusted roots: " + bad + " BAD (behind the compromised chains), "
                + dropped + " DROP (also removed by the 24 October fix)");
        return bad + dropped > 0 ? 1 : 0;
    }

    private static boolean matches(String root, String[] names) {
        for (String name : names) {
            if (root.contains(name)) {
                return true;
            }
        }
        return false;
    }
}
