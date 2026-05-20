package isim.ia2y.myapplication

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Wraps the two-step Credential Manager flow for Google Sign-In:
 *  1. Try previously-authorised accounts first (instant / auto-select UX).
 *  2. Fall back to the full account picker if no prior accounts are available.
 *
 * Throws [androidx.credentials.exceptions.GetCredentialCancellationException] if
 * the user dismisses the sheet; callers should catch that silently.
 * All other [androidx.credentials.exceptions.GetCredentialException] subclasses
 * bubble up and should be shown as error snackbars.
 *
 * Returns null if the returned credential is not a Google ID-token credential
 * (should not happen in practice, but guards against future credential types).
 */
object GoogleCredentialHelper {

    suspend fun fetchIdToken(context: Context, webClientId: String): String? {
        val manager = CredentialManager.create(context)

        // Step 1 — previously-authorised accounts, auto-select if exactly one.
        val filteredOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(true)
            .build()
        try {
            val response = manager.getCredential(
                context,
                GetCredentialRequest.Builder().addCredentialOption(filteredOption).build()
            )
            extractIdToken(response.credential)?.let { return it }
        } catch (_: NoCredentialException) {
            // No previously-authorised accounts — fall through to full picker.
        }

        // Step 2 — full account picker (includes "use a different account").
        val fullOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .build()
        val response = manager.getCredential(
            context,
            GetCredentialRequest.Builder().addCredentialOption(fullOption).build()
        )
        return extractIdToken(response.credential)
    }

    private fun extractIdToken(credential: androidx.credentials.Credential): String? {
        if (credential !is CustomCredential) return null
        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) return null
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
}
