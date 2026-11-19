package com.antigravity.bvr.ui

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Window
import android.widget.Button
import com.antigravity.bvr.R

class ConsentDialog(
    context: Context,
    private val onAccepted: () -> Unit,
    private val onDeclined: () -> Unit
) : Dialog(context) {

    companion object {
        private const val PREFS_NAME = "bvr_prefs"
        private const val KEY_CONSENT_ACCEPTED = "consent_accepted"

        fun hasUserAcceptedConsent(context: Context): Boolean {
            val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_CONSENT_ACCEPTED, false)
        }

        fun setUserAcceptedConsent(context: Context, accepted: Boolean) {
            val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_CONSENT_ACCEPTED, accepted).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_consent)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCancelable(false)

        val btnAccept = findViewById<Button>(R.id.btn_accept_consent)
        val btnDecline = findViewById<Button>(R.id.btn_decline_consent)

        btnAccept.setOnClickListener {
            setUserAcceptedConsent(context, true)
            dismiss()
            onAccepted()
        }

        btnDecline.setOnClickListener {
            dismiss()
            onDeclined()
        }
    }
}
