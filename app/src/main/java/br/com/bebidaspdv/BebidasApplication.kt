package br.com.bebidaspdv

import android.app.Application
import br.com.bebidaspdv.core.PosRepository
import br.com.bebidaspdv.core.ProfileRepository
import br.com.bebidaspdv.core.CashControlRepository
import br.com.bebidaspdv.data.FirestorePosRepository
import br.com.bebidaspdv.data.FirestoreProfileRepository
import br.com.bebidaspdv.data.FirestoreCashControlRepository
import br.com.bebidaspdv.updates.AppUpdates
import br.com.bebidaspdv.updates.createAppUpdates
import br.com.bebidaspdv.updates.LoggingAppUpdates
import br.com.bebidaspdv.diagnostics.AndroidDiagnostics
import br.com.bebidaspdv.core.logging.AppLogger
import br.com.bebidaspdv.core.logging.LogCategory
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

class BebidasApplication : Application() {
    lateinit var diagnostics: AndroidDiagnostics
        private set
    override fun onCreate() {
        super.onCreate()
        diagnostics = AndroidDiagnostics(this)
        diagnostics.start()
        if (!BuildConfig.FIREBASE_CONFIGURED) return
        FirebaseApp.initializeApp(this)
        FirebaseAuth.getInstance().addAuthStateListener { diagnostics.setUser(it.currentUser?.uid) }
        diagnostics.logger.info(LogCategory.FIREBASE, "FIREBASE_INITIALIZED", "SDK inicializado; não representa confirmação de conexão")
        startKoin {
            androidContext(this@BebidasApplication)
            modules(module {
                single<AppLogger> { diagnostics.logger }
                single { FirebaseAuth.getInstance() }
                single {
                    FirebaseFirestore.getInstance().apply {
                        firestoreSettings = FirebaseFirestoreSettings.Builder()
                            .setLocalCacheSettings(PersistentCacheSettings.newBuilder()
                                .setSizeBytes(100L * 1024 * 1024).build())
                            .build()
                    }
                }
                single<PosRepository> { FirestorePosRepository(get(), get(), get()) }
                single<ProfileRepository> { FirestoreProfileRepository(androidContext(), get(), get(), get()) }
                single<CashControlRepository> { FirestoreCashControlRepository(get(), androidContext(), get()) }
                single<AppUpdates> { LoggingAppUpdates(createAppUpdates(androidContext(), get()), get()) }
            })
        }
    }
}
