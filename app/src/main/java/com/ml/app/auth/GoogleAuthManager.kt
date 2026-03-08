package com.ml.app.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class GoogleAuthManager(
    private val context: Context
) {
    private val credentialManager = CredentialManager.create(context)

    suspend fun signIn(): String? {
        val googleIdOption = GetSignInWithGoogleOption.Builder(
            serverClientId = "1049013487136-47q0n2q6s3s9itqq3qsf8l4c9dv0frn7.apps.googleusercontent.com"
        ).build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = credentialManager.getCredential(
            context = context,
            request = request
        )

        val credential = result.credential
        val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data)
        return googleIdToken.idToken
    }
}
