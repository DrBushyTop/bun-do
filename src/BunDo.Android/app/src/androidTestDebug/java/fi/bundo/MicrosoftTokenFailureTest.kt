package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import fi.bundo.identity.microsoftTokenFailure
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MicrosoftTokenFailureTest {
    @Test fun microsoftFailuresDistinguishSignInFromRetryableNetworkErrors() {
        assertFalse(microsoftTokenFailure(MsalUiRequiredException(MsalUiRequiredException.INVALID_GRANT)).retryable)
        assertFalse(microsoftTokenFailure(MsalServiceException(MsalServiceException.ACCESS_DENIED, "private", 403, null)).retryable)
        assertTrue(microsoftTokenFailure(MsalClientException(MsalClientException.DEVICE_NETWORK_NOT_AVAILABLE)).retryable)
        assertTrue(microsoftTokenFailure(MsalServiceException(MsalServiceException.SERVICE_NOT_AVAILABLE, "private", 503, null)).retryable)
    }
}
