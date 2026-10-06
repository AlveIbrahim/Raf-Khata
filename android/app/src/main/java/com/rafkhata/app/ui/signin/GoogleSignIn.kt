package com.rafkhata.app.ui.signin

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/** "Sign in with Google" through Credential Manager; the ID token is verified by our backend. */
object GoogleSignIn {
    sealed interface Result {
        data class Token(val idToken: String) : Result

        data object Cancelled : Result

        data class Failed(val message: String?) : Result
    }

    /** [activityContext] must be an Activity: Credential Manager shows its sheet over it. */
    suspend fun requestIdToken(activityContext: Context, serverClientId: String): Result {
        val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                Result.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                Result.Failed("unexpected credential type ${credential.type}")
            }
        } catch (_: GetCredentialCancellationException) {
            Result.Cancelled
        } catch (e: GetCredentialException) {
            Result.Failed(e.message)
        } catch (e: GoogleIdTokenParsingException) {
            Result.Failed(e.message)
        }
    }
}
