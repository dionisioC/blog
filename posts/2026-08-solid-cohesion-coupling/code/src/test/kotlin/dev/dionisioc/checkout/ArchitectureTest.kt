package dev.dionisioc.checkout

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureTest {

    // One Gradle project, so the compiler would happily let domain/ import an adapter. This test
    // won't: the domain may reference only itself, Kotlin, and the JDK's java.time and java.util —
    // no infrastructure, clients, app or smells, and no vendor SDK. The JDK allowance is narrow on
    // purpose: java.sql and java.net.http ship with the JDK too, and they're infrastructure. Split
    // the packages into Gradle subprojects and the build enforces the project arrow for free.
    @Test
    fun `the domain depends on nothing but itself, Kotlin, java time and java util`() {
        val files = File("src/main/kotlin/dev/dionisioc/checkout/domain")
            .walk()
            .filter { it.extension == "kt" }
            .toList()
        assertTrue(files.isNotEmpty(), "no domain sources found — is the working directory the project?")

        val allowedImports = listOf(
            "import dev.dionisioc.checkout.domain.",
            "import java.time.",
            "import java.util.",
            "import kotlin.",
        )
        // Fully qualified references skip the import line, so catch those in the code itself.
        val outerPackage = Regex("""dev\.dionisioc\.checkout\.(?!domain\b)""")
        val otherJdk = Regex("""\bjavax?\.(?!time\b|util\b)[a-z]""")
        val offenders = files.flatMap { file ->
            file.readLines()
                .filter { line ->
                    val badImport = line.startsWith("import ") && allowedImports.none { line.startsWith(it) }
                    badImport || outerPackage.containsMatchIn(line) || otherJdk.containsMatchIn(line)
                }
                .map { "${file.name}: $it" }
        }

        assertEquals(emptyList(), offenders)
    }
}
