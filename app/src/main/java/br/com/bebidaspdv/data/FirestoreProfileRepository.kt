package br.com.bebidaspdv.data

import android.content.Context
import br.com.bebidaspdv.core.*
import br.com.bebidaspdv.core.logging.*
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/** Profile writes use online transactions. A local, unconfirmed write never grants access. */
class FirestoreProfileRepository(
    private val context: Context,
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val logger: AppLogger = NoOpLogger,
) : ProfileRepository {
    private val preferences = context.getSharedPreferences("verified_operator_profiles", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val creationMutex = Mutex()
    private val _session = MutableStateFlow<ProfileSession>(ProfileSession.Loading)
    override val session: StateFlow<ProfileSession> = _session.asStateFlow()
    private val _pendingProfiles = MutableStateFlow(readPendingProfiles())
    override val pendingProfiles: StateFlow<List<PendingOperatorProfile>> = _pendingProfiles.asStateFlow()
    private var profileListener: ListenerRegistration? = null
    private var sessionJob: Job? = null
    private var signingIn = false
    private var profileRevision = 0L

    init {
        auth.addAuthStateListener { firebase ->
            if (!signingIn) {
                val user = firebase.currentUser
                sessionJob?.cancel()
                profileListener?.remove()
                profileListener = null
                if (user == null) _session.value = ProfileSession.SignedOut
                else sessionJob = scope.launch { restoreSession(user) }
            }
        }
    }

    override suspend fun signIn(email: String, password: String) {
        val trace = logger.repositoryTrace(LogCategory.AUTH, "AUTH_SIGN_IN", "FirestoreProfileRepository", "signIn")
        val step = logger.startStep(trace, LogCategory.AUTH, "AUTH_SIGN_IN", "FirestoreProfileRepository", "signIn", slowAfterMs = 2_000)
        signingIn = true
        sessionJob?.cancel()
        profileListener?.remove()
        _session.value = ProfileSession.Loading
        try {
            val user = auth.signInWithEmailAndPassword(email.trim(), password).await().user
                ?: error("Não foi possível entrar.")
            val access = withLogTrace(trace) { withTimeout(15_000) { serverAccess(user, forceToken = true) } }
            rememberAccess(user.uid, access)
            watchProfile(user, trace)
            _session.value = access
            step.finish("Autenticação e consulta de permissões concluídas.")
            logAccess(access, trace)
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) step.cancel()
            else step.fail(failure, "Falha original de autenticação ou autorização.", authenticationCode(failure))
            auth.signOut()
            _session.value = ProfileSession.SignedOut
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            throw IllegalStateException("Não foi possível entrar. Confira sua conexão, e-mail e senha. Se a conta é nova, o administrador precisa concluir seu perfil.", failure)
        } finally { signingIn = false }
    }

    override fun signOut() {
        logger.info(LogCategory.AUTH, "AUTH_SIGN_OUT", "Sessão encerrada neste aparelho.", TraceContext(component = "FirestoreProfileRepository", function = "signOut"))
        sessionJob?.cancel()
        profileListener?.remove()
        profileListener = null
        auth.signOut()
        _session.value = ProfileSession.SignedOut
    }

    private suspend fun restoreSession(user: FirebaseUser) {
        val trace = logger.repositoryTrace(LogCategory.AUTH, "AUTH_RESTORE", "FirestoreProfileRepository", "restoreSession")
        _session.value = ProfileSession.Loading
        val remembered = readRememberedAccess(user.uid)
        val cached = runCatching {
            val cached = db.collection("Perfis").document(user.uid).get(Source.CACHE).await()
            if (cached.exists() && !cached.metadata.hasPendingWrites()) {
                resolveProfileAccess(user.uid, user.email.orEmpty(), parseProfile(cached), false, false, fromCache = true)
            } else null
        }.getOrNull()
        // A known revocation wins over an older allowed snapshot from either durable source.
        val saved = restoreVerifiedProfileAccess(remembered, cached)
        if (auth.currentUser?.uid != user.uid) return
        if (saved != null) {
            _session.value = saved
            logger.log(LogLevel.INFO, LogCategory.AUTH, "AUTH_RESTORED_LOCAL", "Acesso previamente validado recuperado do armazenamento local.", trace,
                status = LogStatus.SUCCESS, metadata = mapOf("source" to "CACHE"))
        }
        watchProfile(user, trace)
        val revision = profileRevision
        try {
            val access = withLogTrace(trace) { withTimeout(12_000) { serverAccess(user) } }
            if (auth.currentUser?.uid == user.uid && profileRevision == revision) {
                rememberAccess(user.uid, access)
                _session.value = access
                logAccess(access, trace)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            logger.log(LogLevel.WARNING, LogCategory.AUTH, "AUTH_REMOTE_VALIDATION_UNAVAILABLE",
                "Não foi possível atualizar a validação remota; preservada a política de acesso local.", trace,
                error = failure, errorCode = authenticationCode(failure), metadata = mapOf("has_verified_cache" to (saved != null).toString()))
            if (saved == null && auth.currentUser?.uid == user.uid && _session.value is ProfileSession.Loading) {
                _session.value = ProfileSession.Blocked("Conecte à internet para validar o acesso desta conta neste aparelho. Depois da validação, o caixa poderá abrir offline.")
            }
        }
    }

    override suspend fun refreshSession() {
        val trace = logger.repositoryTrace(LogCategory.AUTH, "AUTH_REFRESH", "FirestoreProfileRepository", "refreshSession")
        val user = auth.currentUser ?: return
        val revision = profileRevision
        try {
            val access = withLogTrace(trace) { withTimeout(12_000) { serverAccess(user, forceToken = true) } }
            if (auth.currentUser?.uid == user.uid && profileRevision == revision) {
                rememberAccess(user.uid, access)
                _session.value = access
                watchProfile(user, trace)
                logAccess(access, trace)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            logger.log(LogLevel.ERROR, LogCategory.AUTH, "AUTH_REFRESH_FAILED", "Falha na atualização do acesso.", trace,
                error = failure, errorCode = authenticationCode(failure), status = LogStatus.FAILED)
            throw IllegalStateException("Não foi possível conferir o acesso. Verifique sua conexão e tente novamente.", failure)
        }
    }

    private suspend fun serverAccess(user: FirebaseUser, forceToken: Boolean = false): ProfileSession {
        val trace = logger.repositoryTrace(LogCategory.AUTH, "AUTH_SERVER_ACCESS", "FirestoreProfileRepository", "serverAccess")
        return logger.repositoryStep(trace, LogCategory.FIREBASE, "AUTH_SERVER_ACCESS", "FirestoreProfileRepository", "serverAccess", slowAfterMs = 2_000) {
        val document = db.collection("Perfis").document(user.uid).get(Source.SERVER).await()
        val legacy = if (document.exists()) false else user.getIdToken(forceToken).await().claims["pdv"] == true
        resolveProfileAccess(user.uid, user.email ?: user.displayName.orEmpty(),
            parseProfile(document), legacy, profileAbsenceConfirmed = !document.exists())
        }
    }

    private fun watchProfile(user: FirebaseUser, trace: TraceContext = TraceContext(component = "FirestoreProfileRepository", function = "watchProfile")) {
        profileListener?.remove()
        profileRevision++
        profileListener = db.collection("Perfis").document(user.uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { document, failure ->
                if (failure != null) logger.log(LogLevel.WARNING, LogCategory.AUTH, "AUTH_PROFILE_OBSERVATION_FAILED",
                    "Falha no acompanhamento remoto do perfil; acesso não é alterado por erro de leitura.", trace,
                    error = failure, errorCode = authenticationCode(failure))
                // Errors and cache snapshots cannot undo a known disabled state or authorize a fresh login.
                if (auth.currentUser?.uid != user.uid || document == null || document.metadata.isFromCache || document.metadata.hasPendingWrites()) return@addSnapshotListener
                val revision = ++profileRevision
                scope.launch {
                    val result = runCatching {
                        val legacy = !document.exists() && user.getIdToken(false).await().claims["pdv"] == true
                        resolveProfileAccess(user.uid, user.email.orEmpty(), parseProfile(document), legacy, !document.exists())
                    }.getOrElse {
                        logger.log(LogLevel.ERROR, LogCategory.AUTH, "AUTH_PROFILE_INVALID", "Falha ao interpretar ou validar o perfil.", trace,
                            error = it, errorCode = authenticationCode(it), status = LogStatus.FAILED)
                        ProfileSession.Blocked("Não foi possível validar o perfil desta conta. Peça ao administrador para conferir o cadastro.") }
                    if (auth.currentUser?.uid == user.uid && profileRevision == revision) {
                        rememberAccess(user.uid, result)
                        _session.value = result
                    }
                }
            }
    }

    override fun observeProfiles(): Flow<Feed<List<OperatorProfile>>> = callbackFlow {
        val diagnostic = QueryDiagnostics(logger, logger.repositoryTrace(LogCategory.AUTH, "PROFILE_QUERY", "FirestoreProfileRepository", "observeProfiles"))
        val registration = db.collection("Perfis").addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) { diagnostic.failed(error); close(IllegalStateException("Não foi possível carregar os operadores. Confira o acesso de administrador e a conexão.")); return@addSnapshotListener }
            if (snapshot != null) {
                diagnostic.received(snapshot.metadata.isFromCache, snapshot.metadata.hasPendingWrites())
                val profiles = snapshot.documents.mapNotNull { runCatching { parseProfile(it) }.getOrNull() }.sortedBy { it.name.lowercase() }
                trySend(Feed(profiles, snapshot.metadata.isFromCache, snapshot.metadata.hasPendingWrites()))
            }
        }
        awaitClose { registration.remove() }
    }

    override suspend fun createOperator(name: String, email: String, password: String, permissions: OperatorPermissions): ProfileCreationResult = creationMutex.withLock {
        val trace = logger.repositoryTrace(LogCategory.AUTH, "OPERATOR_CREATE", "FirestoreProfileRepository", "createOperator")
        logger.info(LogCategory.AUTH, "OPERATOR_CREATE_STARTED", "Criação de operador solicitada.", trace)
        require(name.trim().isNotEmpty() && name.trim().length <= 120) { "Informe um nome com até 120 caracteres." }
        require(email.trim().length <= 254 && email.trim().contains('@')) { "Informe um e-mail válido." }
        require(password.length >= 6) { "Use uma senha com pelo menos 6 caracteres." }
        val administrator = withLogTrace(trace) { requireServerAdmin() }
        val appName = "pdv-operator-provisioning"
        val secondaryApp = FirebaseApp.getApps(context).firstOrNull { it.name == appName }
            ?: FirebaseApp.initializeApp(context, auth.app.options, appName)
        val secondaryAuth = FirebaseAuth.getInstance(secondaryApp)
        secondaryAuth.signOut()
        val user = try {
            withTimeout(30_000) {
                try {
                    secondaryAuth.createUserWithEmailAndPassword(email.trim(), password).await().user
                } catch (_: FirebaseAuthUserCollisionException) {
                    // Retry after process death/network uncertainty: prove possession, recover the same UID.
                    secondaryAuth.signInWithEmailAndPassword(email.trim(), password).await().user
                }
            } ?: error("Não foi possível criar a conta.")
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            logger.log(LogLevel.ERROR, LogCategory.AUTH, "OPERATOR_CREATE_FAILED", "Falha original na criação da conta de operador.", trace,
                error = failure, errorCode = authenticationCode(failure), status = LogStatus.FAILED)
            throw IllegalStateException("Não foi possível criar a conta. Confira a conexão e a senha (mínimo de 6 caracteres). Se o e-mail já existe, informe sua senha atual para concluir o perfil.", failure)
        } finally {
            secondaryAuth.signOut()
            secondaryApp.delete()
        }
        check(auth.currentUser?.uid == administrator.uid) { "O administrador mudou. Entre novamente e conclua o cadastro." }
        val profile = OperatorProfile(user.uid, name.trim(), user.email ?: email.trim(), permissions = permissions)
        // Durable journal before Firestore, independent of the screen lifecycle; contains no credentials.
        addPending(PendingOperatorProfile(profile, administrator.uid))
        try {
            withLogTrace(trace) { completeProfile(profile) }
            removePending(profile.uid)
            logger.log(LogLevel.INFO, LogCategory.AUTH, "OPERATOR_CREATED", "Conta e perfil confirmados.", trace,
                status = LogStatus.SUCCESS, syncStatus = LogSyncStatus.SYNCED)
            ProfileCreationResult.Created(profile)
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            logger.log(LogLevel.WARNING, LogCategory.AUTH, "OPERATOR_PROFILE_PENDING", "Conta criada; conclusão do perfil segue pendente.", trace,
                error = failure, errorCode = authenticationCode(failure), status = LogStatus.PENDING, syncStatus = LogSyncStatus.PENDING)
            ProfileCreationResult.Pending(profile)
        }
    }

    override suspend fun retryPendingProfile(uid: String) {
        val trace = logger.repositoryTrace(LogCategory.AUTH, "OPERATOR_PROFILE_RETRY", "FirestoreProfileRepository", "retryPendingProfile", entityId = uid)
        withLogTrace(trace) {
        logger.log(LogLevel.INFO, LogCategory.SYNC, "SYNC_RETRY", "Conferência do perfil pendente solicitada pelo administrador.", trace, status = LogStatus.RETRY)
        requireServerAdmin()
        val pending = _pendingProfiles.value.firstOrNull { it.profile.uid == uid }
            ?: error("Cadastro pendente não encontrado neste aparelho.")
        completeProfile(pending.profile)
        removePending(uid)
        }
    }

    private suspend fun completeProfile(profile: OperatorProfile) {
        val trace = logger.repositoryTrace(LogCategory.AUTH, "OPERATOR_PROFILE_WRITE", "FirestoreProfileRepository", "completeProfile", entityId = profile.uid)
        logger.repositoryStep(trace, LogCategory.DATABASE, "OPERATOR_PROFILE_TRANSACTION", "FirestoreProfileRepository", "completeProfile", slowAfterMs = 2_000,
            syncStatus = LogSyncStatus.SYNCED) {
        val administrator = requireServerAdmin()
        withTimeout(20_000) {
            db.runTransaction { transaction ->
                val reference = db.collection("Perfis").document(profile.uid)
                val existing = transaction.get(reference)
                if (existing.exists()) {
                    check(existing.getString("email")?.equals(profile.email, ignoreCase = true) == true) { "O perfil existente tem outro e-mail. Confira o cadastro." }
                    // A successful retry never resets permissions or reactivates an existing profile.
                } else {
                    transaction.set(reference, profileFields(profile) + mapOf(
                        "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
                        "createdBy" to administrator.uid, "updatedBy" to administrator.uid,
                    ))
                }
                Unit
            }.await()
        }
        }
    }

    override suspend fun saveProfile(profile: OperatorProfile) {
        val trace = logger.repositoryTrace(LogCategory.AUTH, "OPERATOR_PROFILE_UPDATE", "FirestoreProfileRepository", "saveProfile", entityId = profile.uid)
        logger.repositoryStep(trace, LogCategory.DATABASE, "OPERATOR_PROFILE_UPDATE", "FirestoreProfileRepository", "saveProfile", slowAfterMs = 2_000,
            syncStatus = LogSyncStatus.SYNCED) {
        require(profile.name.trim().isNotEmpty() && profile.name.trim().length <= 120) { "Informe um nome com até 120 caracteres." }
        val administrator = requireServerAdmin()
        require(profile.uid != administrator.uid || (profile.active && profile.role == OperatorRole.ADMIN)) { "Você não pode desativar ou rebaixar a própria conta." }
        withTimeout(20_000) {
            db.runTransaction { transaction ->
                val reference = db.collection("Perfis").document(profile.uid)
                check(transaction.get(reference).exists()) { "Este perfil não existe mais. Atualize a lista." }
                transaction.update(reference, mapOf(
                    "name" to profile.name.trim(), "role" to profile.role.storedValue, "active" to profile.active,
                    "permissions" to permissionFields(profile.permissions),
                    "updatedBy" to administrator.uid, "updatedAt" to FieldValue.serverTimestamp(),
                ))
                Unit
            }.await()
        }
        }
    }

    private suspend fun requireServerAdmin(): OperatorProfile {
        val trace = logger.repositoryTrace(LogCategory.SECURITY, "ADMIN_PERMISSION_CHECK", "FirestoreProfileRepository", "requireServerAdmin")
        return logger.repositoryStep(trace, LogCategory.FIREBASE, "ADMIN_PERMISSION_CHECK", "FirestoreProfileRepository", "requireServerAdmin", slowAfterMs = 2_000) {
        val user = auth.currentUser ?: error("Entre com uma conta de administrador.")
        val profile = withTimeout(12_000) { parseProfile(db.collection("Perfis").document(user.uid).get(Source.SERVER).await()) }
        check(profile?.active == true && profile.role == OperatorRole.ADMIN) { "Somente um administrador ativo pode gerenciar operadores. Esta operação precisa de internet." }
        requireNotNull(profile)
        }
    }

    private fun parseProfile(document: DocumentSnapshot): OperatorProfile? {
        if (!document.exists()) return null
        val uid = document.getString("uid")
        val name = document.getString("name")
        val email = document.getString("email")
        val role = OperatorRole.entries.firstOrNull { it.storedValue == document.getString("role") }
        val active = document.getBoolean("active")
        check(uid == document.id && !name.isNullOrBlank() && email != null && role != null && active != null) { "Perfil inválido." }
        fun permission(key: String) = document.getBoolean("permissions.$key") == true
        return OperatorProfile(requireNotNull(uid), requireNotNull(name), requireNotNull(email), requireNotNull(role), requireNotNull(active),
            OperatorPermissions(permission("editPrices"), permission("deleteProducts"), permission("viewReports"), permission("reverseSales"), permission("exportData")))
    }

    private fun permissionFields(p: OperatorPermissions) = mapOf(
        "editPrices" to p.editPrices, "deleteProducts" to p.deleteProducts, "viewReports" to p.viewReports,
        "reverseSales" to p.reverseSales, "exportData" to p.exportData,
    )

    private fun profileFields(profile: OperatorProfile): Map<String, Any> = mapOf(
        "uid" to profile.uid, "name" to profile.name, "email" to profile.email,
        "role" to profile.role.storedValue, "active" to profile.active, "permissions" to permissionFields(profile.permissions),
    )

    private fun profileFromJson(json: JSONObject): OperatorProfile {
        val p = json.getJSONObject("permissions")
        return OperatorProfile(json.getString("uid"), json.getString("name"), json.getString("email"),
            OperatorRole.entries.first { it.storedValue == json.getString("role") }, json.getBoolean("active"),
            OperatorPermissions(p.getBoolean("editPrices"), p.getBoolean("deleteProducts"), p.getBoolean("viewReports"), p.getBoolean("reverseSales"), p.getBoolean("exportData")))
    }

    private fun rememberAccess(uid: String, access: ProfileSession) {
        val json = JSONObject().put("uid", uid)
        when (access) {
            is ProfileSession.Authorized -> {
                json.put("status", "authorized").put("name", access.name).put("legacy", access.legacy)
                access.profile?.let { json.put("profile", JSONObject(profileFields(it))) }
            }
            is ProfileSession.Blocked -> json.put("status", "blocked").put("message", access.message).put("disabled", access.disabled)
            else -> return
        }
        preferences.edit().putString("access_$uid", json.toString()).commit()
    }

    private fun readRememberedAccess(uid: String): ProfileSession? = runCatching {
        val json = JSONObject(preferences.getString("access_$uid", null) ?: return null)
        check(json.getString("uid") == uid)
        if (json.getString("status") == "blocked") ProfileSession.Blocked(json.getString("message"), json.optBoolean("disabled"))
        else {
            val profile = if (json.has("profile")) profileFromJson(json.getJSONObject("profile")) else null
            resolveProfileAccess(uid, json.getString("name"), profile, json.getBoolean("legacy"), json.getBoolean("legacy"), fromCache = true)
        }
    }.getOrNull()

    private fun readPendingProfiles(): List<PendingOperatorProfile> = runCatching {
        val json = JSONArray(preferences.getString("pending", "[]"))
        List(json.length()) { index ->
            val item = json.getJSONObject(index)
            PendingOperatorProfile(profileFromJson(item.getJSONObject("profile")), item.getString("requestedBy"))
        }
    }.getOrDefault(emptyList())

    private fun addPending(pending: PendingOperatorProfile) = persistPending(_pendingProfiles.value.filterNot { it.profile.uid == pending.profile.uid } + pending)
    private fun removePending(uid: String) = persistPending(_pendingProfiles.value.filterNot { it.profile.uid == uid })
    private fun persistPending(pending: List<PendingOperatorProfile>) {
        val json = JSONArray()
        pending.forEach { json.put(JSONObject().put("profile", JSONObject(profileFields(it.profile))).put("requestedBy", it.requestedBy)) }
        check(preferences.edit().putString("pending", json.toString()).commit()) { "Não foi possível guardar a recuperação do cadastro. Anote o UID e tente novamente com o mesmo e-mail e senha." }
        _pendingProfiles.value = pending
    }

    private fun authenticationCode(error: Throwable): String? =
        (error as? com.google.firebase.auth.FirebaseAuthException)?.errorCode ?: error.diagnosticCode()

    private fun logAccess(access: ProfileSession, trace: TraceContext) {
        logger.log(if (access is ProfileSession.Blocked) LogLevel.WARNING else LogLevel.INFO, LogCategory.AUTH,
            "AUTH_ACCESS_RESOLVED", "Validação de acesso concluída.", trace,
            status = if (access is ProfileSession.Authorized) LogStatus.SUCCESS else LogStatus.FAILED,
            metadata = mapOf("authorized" to (access is ProfileSession.Authorized).toString()))
    }
}
