package fi.bundo
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
@RunWith(AndroidJUnit4::class)
class ArtworkCacheTest {
    @Test fun cache_is_private_bounded_and_unavailable_after_account_revocation() = runBlocking {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "art-${UUID.randomUUID()}")
        val lease = DataLease(); val cache = ArtworkCache(directory, lease)
        try {
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray(); bitmap.recycle()
            assertEquals(32, cache.save("dojo-v1-home", bytes).width)
            assertEquals(24, ArtworkCache(directory, lease).read("dojo-v1-home")!!.height)
            assertTrue(runCatching { cache.save("../../other", bytes) }.isFailure)
            assertTrue(runCatching { cache.save("dojo-v1-garden", byteArrayOf(1,2,3)) }.isFailure)
            assertNull(cache.read("dojo-v1-garden"))
            lease.revoke(); assertTrue(runCatching { cache.read("dojo-v1-home") }.isFailure)
        } finally { directory.deleteRecursively() }
    }
}
