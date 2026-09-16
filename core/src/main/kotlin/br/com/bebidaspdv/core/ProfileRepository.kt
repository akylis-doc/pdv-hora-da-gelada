package br.com.bebidaspdv.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ProfileRepository {
    val session: StateFlow<ProfileSession>
    val pendingProfiles: StateFlow<List<PendingOperatorProfile>>
    suspend fun signIn(email: String, password: String)
    fun signOut()
    suspend fun refreshSession()
    fun observeProfiles(): Flow<Feed<List<OperatorProfile>>>
    /** Creates Auth credentials in a secondary FirebaseApp, keeping the cashier's session intact. */
    suspend fun createOperator(name: String, email: String, password: String, permissions: OperatorPermissions): ProfileCreationResult
    /** An existing UID can be resumed without creating a second Auth account. */
    suspend fun retryPendingProfile(uid: String)
    /** Password/email/auth deletion are intentionally absent; history continues referencing the original UID. */
    suspend fun saveProfile(profile: OperatorProfile)
}
