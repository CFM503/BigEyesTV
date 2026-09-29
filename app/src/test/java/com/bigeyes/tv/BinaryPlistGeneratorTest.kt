package com.bigeyes.tv

import com.bigeyes.tv.utils.PlistHelper
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BinaryPlistGeneratorTest {

    @Test
    fun generateBinaryPlistFile() {
        val dict = NSDictionary()
        dict.put("Content-Location", NSString("http://192.168.1.188:8765/stream/cctv1.m3u8"))
        dict.put("Start-Position", NSNumber(0.0))

        val binaryBytes = com.dd.plist.BinaryPropertyListWriter.writeToArray(dict)
        val file = File("build/tmp/sample_play.bplist")
        file.parentFile?.mkdirs()
        file.writeBytes(binaryBytes)
        assertTrue(file.length() > 0)
        assertEquals("bplist00", String(binaryBytes, 0, 8, Charsets.US_ASCII))
        println("Generated binary plist file: ${file.absolutePath}, size: ${file.length()} bytes")
    }
}
