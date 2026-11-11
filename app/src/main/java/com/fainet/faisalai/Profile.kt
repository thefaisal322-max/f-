package com.fainet.faisalai

import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import java.security.MessageDigest
import java.security.SecureRandom

/** The F+ PIN. It is stored only as a salted hash, on this phone. */
object Lock {
    @Volatile var unlocked = false
    @Volatile var leftAt = 0L

    private const val AFTER_MS = 30_000L

    fun hasPin(c: android.content.Context) = Prefs.sp(c).getString("pin_hash", "").orEmpty().isNotEmpty()

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    fun set(c: android.content.Context, pin: String) {
        val salt = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        Prefs.sp(c).edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }

    fun check(c: android.content.Context, pin: String): Boolean {
        val sp = Prefs.sp(c)
        return hash(sp.getString("pin_salt", "").orEmpty(), pin) == sp.getString("pin_hash", "")
    }

    fun clear(c: android.content.Context) = Prefs.sp(c).edit().remove("pin_hash").remove("pin_salt").apply()

    /** Called when the app comes to the front: ask again if it was away for more than 30 seconds. */
    fun appForeground() {
        if (leftAt != 0L && SystemClock.elapsedRealtime() - leftAt > AFTER_MS) unlocked = false
    }

    fun appBackground() {
        leftAt = SystemClock.elapsedRealtime()
    }
}

class PinActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin)
        val box = findViewById<EditText>(R.id.pinBox)
        fun tryPin() {
            if (Lock.check(this, box.text.toString())) {
                Lock.unlocked = true
                finish()
            } else if (box.text.length >= 4) {
                toast(getString(R.string.pin_wrong))
                box.setText("")
            }
        }
        findViewById<Button>(R.id.pinGo).setOnClickListener { tryPin() }
        box.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { if ((s?.length ?: 0) == 6) tryPin() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        // Back does not skip the lock: it leaves the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
    }
}

class ProfileActivity : BaseActivity() {
    private lateinit var first: EditText
    private lateinit var last: EditText
    private lateinit var email: EditText
    private lateinit var pinState: TextView
    private lateinit var pinSet: Button
    private lateinit var pinRemove: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        bar(R.string.pf_title)
        first = findViewById(R.id.first)
        last = findViewById(R.id.last)
        email = findViewById(R.id.email)
        pinState = findViewById(R.id.pinState)
        pinSet = findViewById(R.id.pinSet)
        pinRemove = findViewById(R.id.pinRemove)
        val sp = Prefs.sp(this)
        first.setText(sp.getString("pf_first", ""))
        last.setText(sp.getString("pf_last", ""))
        email.setText(sp.getString("pf_email", ""))
        findViewById<Button>(R.id.save).setOnClickListener {
            val mail = email.text.toString().trim()
            if (mail.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(mail).matches()) {
                toast(getString(R.string.pf_email_bad))
                return@setOnClickListener
            }
            sp.edit().putString("pf_first", first.text.toString().trim()).putString("pf_last", last.text.toString().trim())
                .putString("pf_email", mail).apply()
            toast(getString(R.string.pf_saved))
        }
        pinSet.setOnClickListener {
            if (Lock.hasPin(this)) ask(R.string.pf_pin_current) { cur -> if (Lock.check(this, cur)) newPin() else toast(getString(R.string.pin_wrong)) }
            else newPin()
        }
        pinRemove.setOnClickListener {
            ask(R.string.pf_pin_current) { cur ->
                if (Lock.check(this, cur)) { Lock.clear(this); refresh() } else toast(getString(R.string.pin_wrong))
            }
        }
        refresh()
    }

    private fun refresh() {
        val on = Lock.hasPin(this)
        pinState.setText(if (on) R.string.pf_pin_on else R.string.pf_pin_off)
        pinSet.setText(if (on) R.string.pf_pin_change else R.string.pf_pin_set)
        pinRemove.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun ask(title: Int, done: (String) -> Unit) {
        val box = EditText(this)
        box.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        box.filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        box.gravity = android.view.Gravity.CENTER
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> done(box.text.toString()) }.show()
    }

    private fun newPin() {
        ask(R.string.pf_pin_new) { a ->
            if (a.length < 4) { toast(getString(R.string.pf_pin_short)); return@ask }
            ask(R.string.pf_pin_again) { b ->
                if (a != b) toast(getString(R.string.pf_pin_mismatch))
                else { Lock.set(this, a); Lock.unlocked = true; refresh() }
            }
        }
    }
}
