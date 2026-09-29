package com.bigeyes.tv

import com.bigeyes.tv.player.contract.PlaybackIntentContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the AndroidManifest against the exact failure the audit found: a component or permission
 * renamed on one side of the phone <-> TV contract without the other side following.
 *
 * The manifest is parsed from source rather than the merged manifest so the check works in plain
 * JVM unit tests without Robolectric.
 */
class AndroidManifestContractTest {

    private val manifest: File by lazy { locateManifest() }

    private val document by lazy {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.newDocumentBuilder().parse(manifest)
    }

    private fun resolve(name: String): String =
        if (name.startsWith(".")) APPLICATION_ID + name else name

    private fun attribute(element: Element, name: String): String? =
        element.getAttributeNS(ANDROID_NS, name).takeIf { it.isNotEmpty() }

    private fun componentName(element: Element): String =
        resolve(attribute(element, "name").orEmpty())

    private fun <T> elements(tag: String, transform: (Element) -> T): List<T> =
        document.getElementsByTagName(tag).asList().map(transform)

    private fun NodeList.asList(): List<Element> =
        (0 until length).map { item(it) as Element }

    // ---------------------------------------------------------------- components

    @Test
    fun testMainactivityMatchesThePhoneSideIntentContract() {
        val activity = elements("activity") { it }
            .single { componentName(it) == MainActivityClass }
        assertEquals(MainActivityClass, componentName(activity))
        assertEquals(
            "MainActivity must be exported so the phone app can hand playback over",
            "true",
            attribute(activity, "exported")
        )

        val actions = activity.getElementsByTagName("action").asList()
            .mapNotNull { attribute(it, "name") }
        val expected = listOf(
            PlaybackIntentContract.ACTION_PLAY,
            PlaybackIntentContract.ACTION_PLAY_QUEUE,
            PlaybackIntentContract.ACTION_NEXT,
            PlaybackIntentContract.ACTION_PREVIOUS,
            PlaybackIntentContract.ACTION_PAUSE,
            PlaybackIntentContract.ACTION_RESUME,
            PlaybackIntentContract.ACTION_STOP,
            PlaybackIntentContract.ACTION_SEEK
        )
        expected.forEach { action ->
            assertTrue("Manifest is missing intent action $action", action in actions)
        }
    }

    @Test
    fun testReceiverIsPermissionGuarded() {
        val receiver = elements("receiver") { it }.single { componentName(it) == ReceiverClass }
        assertEquals(ReceiverClass, componentName(receiver))
        assertEquals("true", attribute(receiver, "exported"))
        assertEquals(PERMISSION, attribute(receiver, "permission"))
    }

    @Test
    fun testPermissionIsDeclaredAndNormal() {
        val permission = elements("permission") { it }.single { attribute(it, "name") == PERMISSION }
        assertEquals("normal", attribute(permission, "protectionLevel"))
    }

    @Test
    fun testServiceAndOtherComponentsStillResolve() {
        val service = elements("service") { it }
            .single { componentName(it) == "com.bigeyes.tv.service.TvReceiverService" }
        assertEquals("false", attribute(service, "exported"))
        assertEquals("mediaPlayback", attribute(service, "foregroundServiceType"))
    }

    // ------------------------------------------------------------ visibility

    @Test
    fun testQueriesDeclaresThePhoneAppPackage() {
        val packages = elements("package") { it }.mapNotNull { attribute(it, "name") }
        assertTrue(
            "<queries> must declare ${PlaybackIntentContract.PACKAGE_BIGEYES} or the TV cannot " +
                "query the companion app on Android 11+",
            PlaybackIntentContract.PACKAGE_BIGEYES in packages
        )
        assertEquals("com.bigeyes.app", PlaybackIntentContract.PACKAGE_BIGEYES)
    }

    @Test
    fun testPostNotificationsPermissionIsDeclared() {
        val permissions = elements("uses-permission") { it }.mapNotNull { attribute(it, "name") }
        assertTrue(
            "android.permission.POST_NOTIFICATIONS must be declared for Android 13+",
            "android.permission.POST_NOTIFICATIONS" in permissions
        )
        assertTrue("android.permission.INTERNET must be declared", "android.permission.INTERNET" in permissions)
    }

    @Test
    fun testManifestIsParsedFromTheModuleSourceSet() {
        assertEquals("src", manifest.parentFile?.parentFile?.name)
        assertEquals("AndroidManifest.xml", manifest.name)
        assertTrue("Manifest was not read from the expected module", manifest.path.replace('\\', '/').endsWith("app/src/main/AndroidManifest.xml"))
    }

    private fun locateManifest(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/AndroidManifest.xml")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Could not locate src/main/AndroidManifest.xml from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val APPLICATION_ID = "com.bigeyes.tv"
        const val PERMISSION = "com.bigeyes.tv.permission.RECEIVE_PLAYBACK_COMMAND"
        const val MainActivityClass = "com.bigeyes.tv.ui.MainActivity"
        const val ReceiverClass = "com.bigeyes.tv.playback.PlaybackCommandReceiver"
    }
}
