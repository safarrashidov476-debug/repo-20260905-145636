package uz.safar.accessiblekeyboard

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(
            AccessibleKeyboardService.PREFS,
            MODE_PRIVATE
        )

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(layout)
        setContentView(scroll)

        layout.addView(TextView(this).apply {
            text = "Accessible Keyboard sozlamalari"
            textSize = 20f
            contentDescription = "Accessible Keyboard sozlamalari sahifasi"
        })

        layout.addView(makeSwitch(
            "Bosganda tebranish",
            AccessibleKeyboardService.KEY_VIBRATE,
            true
        ))
        layout.addView(makeSwitch(
            "Bosganda tovush signali",
            AccessibleKeyboardService.KEY_SOUND,
            true
        ))
        layout.addView(makeSwitch(
            "Har bir tugmani ovozda o'qish (TTS)",
            AccessibleKeyboardService.KEY_SPEAK,
            false
        ))
        layout.addView(makeSwitch(
            "Bir marta bosib matn kiritish (tajribali foydalanuvchilar uchun)",
            AccessibleKeyboardService.KEY_SINGLE_TAP,
            false
        ))
        layout.addView(makeSwitch(
            "Har bir so'z tugagach uni ovozda o'qish",
            AccessibleKeyboardService.KEY_READ_WORD,
            true
        ))

        layout.addView(Button(this).apply {
            text = "Klaviaturani yoqish"
            contentDescription = "Tizim tili va klaviatura sozlamalarini ochish, u yerda Accessible Keyboard'ni yoqing"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })

        layout.addView(Button(this).apply {
            text = "Klaviaturani tanlash"
            contentDescription = "Joriy faol klaviaturani almashtirish oynasini ochish"
            setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        })
    }

    private fun makeSwitch(label: String, key: String, default: Boolean): Switch {
        return Switch(this).apply {
            text = label
            contentDescription = label
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                prefs.edit().putBoolean(key, checked).apply()
            }
        }
    }
}
