package com.android.cts.net

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test
import com.android.tradefed.util.FileUtil
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.SequenceInputStream
import java.nio.file.Path
import javax.xml.parsers.SAXParserFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

@RunWith(DeviceJUnit4ClassRunner::class)
class HiddenNetApiUsageTest : BaseHostJUnit4Test() {


    fun listFiles(f: File, prefix: String = ""): List<String> {
        return if (!f.isDirectory) {
            listOf(prefix + f.absolutePath)
        } else {
            f.listFiles()?.flatMap { listFiles(it, it.absolutePath + "/") } ?: emptyList()
        }
    }

    class DexParserHandler : DefaultHandler() {
        private var pkg = ""
        private var clazz = ""

        private var currentElementName = ""
        private var currentElementType = ""
        private val currentParameters = mutableListOf<String>()

        val parsedSymbols = mutableSetOf<String>()

        override fun startElement(
            uri: String,
            localName: String,
            qName: String,
            attributes: Attributes
        ) {
            when (qName) {
                "package" -> pkg = attributes.getValue("name")
                "class" -> clazz = attributes.getValue("name")
                "field" -> {
                    currentElementName = attributes.getValue("name")
                    currentElementType = toInternalType(attributes.getValue("type"))
                }
                "method" -> {
                    currentElementName = attributes.getValue("name")
                    currentElementType = toInternalType(attributes.getValue("return"))
                    currentParameters.clear()
                }
                "constructor" -> {
                    currentElementName = "<init>"
                    currentElementType = toInternalType("void")
                    currentParameters.clear()
                }
                "parameter" -> {
                    currentParameters.add(toInternalType(attributes.getValue("type")))
                }
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            when (qName) {
                "field" -> {
                    parsedSymbols.add(toInternalType("$pkg.$clazz")
                            + ".$currentElementName:$currentElementType")
                }
                "method", "constructor" -> {
                    val parameters = currentParameters.joinToString(separator="")
                    parsedSymbols.add(toInternalType("$pkg.$clazz")
                            + ".$currentElementName:($parameters)$currentElementType")
                }
            }
        }

        /**
         * Convert from dexdump XML output type (java.lang.String or boolean[]) to internal type
         * (Ljava/lang/String; or [Z)
         */
        private fun toInternalType(type: String): String {
            if (type.endsWith("[]")) {
                return "[" + toInternalType(type.substring(0, type.length - 2))
            }
            return when (type) {
                "void" -> "V"
                "byte" -> "B"
                "char" -> "C"
                "double" -> "D"
                "float" -> "F"
                "int" -> "I"
                "long" -> "J"
                "short" -> "S"
                "boolean" -> "Z"
                else ->  'L' + type.replace('.', '/') + ';'
            }
        }
    }

    private fun makeHiddenApiSet(): Set<String> {
        val flags = build.files.firstNotNullOfOrNull {
            FileUtil.findFile(it.file, "connectivity-hiddenapi-flags.csv")
        }
        assertTrue(flags?.exists() == true, "Could not find flags file")

        // Example method:
        // Landroid/net/NetworkTemplate;->getBackwardsCompatibleMatchRule(I)I,blocked
        // Example constant:
        // Landroid/net/OemNetworkPreferences;->OEM_NETWORK_PREFERENCE_MAX:I,blocked
        val hiddenApiRegex = Regex("""(L.+?;)->(.+)(?:(\(.*\))|:)(.+),.+""")
        val hiddenApis = mutableSetOf<String>()
        BufferedReader(FileReader(flags)).use { reader ->
            reader.forEachLine {
                if (it.endsWith("blocked")) {
                    val match = hiddenApiRegex.matchEntire(it)
                    assertNotNull(match, "$it did not match regex: $hiddenApiRegex")
                    val symbolClass = match.groups[1]?.value
                    assertNotNull(symbolClass)
                    val symbolName = match.groups[2]?.value
                    assertNotNull(symbolName)
                    // Constants do not have arguments so arguments may be null
                    val arguments = match.groups[3]?.value
                    val returnType = match.groups[4]?.value
                    assertNotNull(returnType)

                    // dexdump format is, example method:
                    // Landroid/net/IpPrefix;.contains:(Ljava/net/InetAddress;)Z
                    // Example constant:
                    // Landroid/net/LinkAddress;.CREATOR:Landroid/os/Parcelable$Creator;
                    hiddenApis.add(
                            "$symbolClass.$symbolName:${arguments ?: ""}$returnType")
                }
            }
        }

        // Module API is also considered "blocked". Remove them from the list based on the stubs.
        val moduleApiStubs = build.files.firstNotNullOfOrNull {
            FileUtil.findFile(it.file, "framework-connectivity-module-lib-stubs-dexed-for-test.jar")
        }
        assertNotNull(moduleApiStubs)
        assertTrue(moduleApiStubs.exists(), "Could not find API stubs file")

        val moduleApiDump = ProcessBuilder("dexdump", "-l", "xml", "-e", "-n",
                moduleApiStubs.absolutePath.toString()).start()

        // Dexdump does not output a header so add one
        val headerStream = ByteArrayInputStream(
                // "<?xml version='1.0' encoding='utf-8'?>\n".encodeToByteArray())
                "".encodeToByteArray())
        val parsedHandler = SequenceInputStream(headerStream, moduleApiDump.inputStream)
                .use { stream ->
                    DexParserHandler().also {
                        SAXParserFactory.newInstance().newSAXParser().parse(stream, it)
                    }
                }
        hiddenApis.removeAll(parsedHandler.parsedSymbols)

        return hiddenApis
    }

    private fun verifyJar(hiddenApis: Set<String>, tmpDir: Path, jarPath: File): Set<String> {
        device.executeAdbCommand("pull", jarPath.absolutePath, tmpDir.toString())
        val servicesJar = File(tmpDir.toString(), jarPath.name)
        assertTrue(servicesJar.exists(), "Jar not found after pull")

        val invocationDump = ProcessBuilder("dexdump", "-d", "-n",
                servicesJar.absolutePath.toString()).start()
        val invocationRegex = Regex(
                // Method invocations
                ": (?:invoke-(?:direct|interface|static|super|virtual) \\{.*}," +
                        // Reading fields
                        "|sget(?:-[a-z]+)? .+,) ([^ ]+)")
        val violations = mutableSetOf<String>()
        BufferedReader(InputStreamReader(invocationDump.inputStream)).use { reader ->
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val invocation = invocationRegex.find(line)?.groups?.get(1)?.value ?: continue
                    if (hiddenApis.contains(invocation)) {
                        violations.add(invocation)
                    }
                }
            } catch (e: IOException) {
                throw IOException("Error reading dexdump output", e)
            }
        }

        servicesJar.delete()
        return violations
    }

    @Test
    fun testHiddenApiUsage() {
        val hiddenApis = makeHiddenApiSet()

        val jarDir = createTempDirectory("hiddenapi-jars")
        assertTrue(jarDir.isAbsolute)

        val violations = mutableMapOf<String, Set<String>>()

        val jarsFind = device.executeShellV2Command(
                "find system system_ext vendor product -name *.jar")
        jarsFind.stdout.split('\n').forEach {
            if (it.isEmpty()) return@forEach
            System.out.println("Verifying $it")
            val jarViolations = verifyJar(hiddenApis, jarDir, File("/$it"))
            if (jarViolations.isNotEmpty()) {
                violations[it] = jarViolations
            }
        }

        val apksFind = device.executeShellV2Command("pm list packages")
        apksFind.stdout.split('\n').forEach { pkgEntry ->
            if (pkgEntry.isEmpty()) return@forEach
            val prefix = "package:"
            assertTrue(pkgEntry.startsWith(prefix))
            val pkgName = pkgEntry.substring(prefix.length)
            when (pkgName) {
                "com.android.networkstack.tethering" -> return@forEach
                "com.android.networkstack" -> return@forEach
                "com.android.nearby.halfsheet" -> return@forEach
            }

            val path = device.executeShellV2Command("pm path $pkgName").stdout.trim()
            assertTrue(path.startsWith(prefix))
            val apk = File(path.substring(prefix.length))

            System.out.println("Verifying $pkgName")
            val jarViolations = verifyJar(hiddenApis, jarDir, apk)
            if (jarViolations.isNotEmpty()) {
                violations[pkgName] = jarViolations
            }
        }

        assertEquals(emptyMap(), violations)
    }
}