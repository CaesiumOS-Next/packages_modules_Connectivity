@file:JvmName("HiddenNetApiList")

package com.android.connectivity.hiddenapi

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.SequenceInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

fun main(args: Array<String>) {
    assertTrue(args.size == 3, "Expected 3 arguments")
    val hiddenApi = makeHiddenApiSet(flagsFile = File(args[0]), moduleApiStubs = File(args[1]))

    val outFile = File(args[2])
    assertTrue(!outFile.exists(), "$outFile already exists")

    BufferedWriter(FileWriter(outFile)).use { writer ->
        hiddenApi.forEach {
            writer.appendLine(it)
        }
    }
}

private fun makeHiddenApiSet(flagsFile: File, moduleApiStubs: File): Set<String> {
    assertTrue(flagsFile.exists(), "Could not find flags file at $flagsFile")

    // Example method:
    // Landroid/net/NetworkTemplate;->getBackwardsCompatibleMatchRule(I)I,blocked
    // Example constant:
    // Landroid/net/OemNetworkPreferences;->OEM_NETWORK_PREFERENCE_MAX:I,blocked
    val hiddenApiRegex = Regex("""(L.+?;)->(.+)(?:(\(.*\))|:)(.+),.+""")
    val hiddenApis = mutableMapOf<String, String>()
    BufferedReader(FileReader(flagsFile)).use { reader ->
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
                hiddenApis["$symbolClass.$symbolName:${arguments ?: ""}$returnType"] = it
            }
        }
    }

    // Module API is also considered "blocked". Remove them from the list based on the stubs.
    assertTrue(moduleApiStubs.exists(), "Could not find API stubs file at $moduleApiStubs")

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
    hiddenApis.keys.removeAll(parsedHandler.parsedSymbols)

    return hiddenApis.values.toSet()
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
                parsedSymbols.add(toInternalType("$pkg.$clazz") +
                        ".$currentElementName:$currentElementType")
            }
            "method", "constructor" -> {
                val parameters = currentParameters.joinToString(separator = "")
                parsedSymbols.add(toInternalType("$pkg.$clazz") +
                        ".$currentElementName:($parameters)$currentElementType")
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
            else -> 'L' + type.replace('.', '/') + ';'
        }
    }
}
