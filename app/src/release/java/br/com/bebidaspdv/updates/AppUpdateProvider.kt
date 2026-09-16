package br.com.bebidaspdv.updates

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore

@Suppress("UNUSED_PARAMETER")
fun createAppUpdates(context: Context, db: FirebaseFirestore): AppUpdates = UnavailableUpdates
