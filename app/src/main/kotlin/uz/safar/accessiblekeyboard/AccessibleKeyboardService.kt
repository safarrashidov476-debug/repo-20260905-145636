package uz.safar.accessiblekeyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import java.util.Locale

/**
 * Ko'zi ojiz foydalanuvchilar uchun TalkBack bilan to'liq mos klaviatura.
 *
 * Ikki kiritish rejimi bor:
 *  1) Standart (KEY_SINGLE_TAP = false) — oddiy Button xatti-harakati. TalkBack
 *     birinchi bosishda tugmani o'qiydi, ikkinchi (qo'sh) bosishda faollashtiradi.
 *  2) Bitta bosib kiritish (KEY_SINGLE_TAP = true, tajribali foydalanuvchilar uchun) —
 *     "tekshirib-teginish" uslubi: barmoq tugma ustiga tegishi bilan u ovozda o'qiladi
 *     (TalkBack o'zi qiladi), keyin:
 *       - barmoqni o'sha tugma ustida ko'tarsa — harf kiritiladi (ACTION_HOVER_EXIT'da,
 *         qisqa kechikish bilan);
 *       - barmoqni qo'ymasdan qo'shni tugmaga surib o'tsa — avvalgi tugma bekor qilinadi,
 *         yangisi o'qiladi (sirg'anib tuzatish, "slide to correct");
 *       - barmoqni klaviatura bo'sh joyiga (tugmalar orasidan tashqariga) surib qo'ysa —
 *         hech narsa kiritilmaydi (bekor qilish gesti).
 *
 * Bundan tashqari:
 *  - Har bir so'z tugagach (bo'sh joy/tinish belgisidan keyin) butun so'z ovozda
 *    o'qiladi — bu foydalanuvchiga xato-to'g'rini eshitib tekshirish imkonini beradi.
 *  - Backspace tugmasini uzoq bosish — butun so'zni bir zumda o'chiradi.
 *  - Har xil tugma turlari (harf / bo'sh joy / o'chirish / kirish / bufer) uchun
 *    tebranish davomiyligi farqlanadi, shu bilan foydalanuvchi ekranga qaramasdan
 *    ham qaysi turdagi tugmani bosganini teri orqali sezadi.
 *  - Nusxa/Kesish/Joylash tugmalari va ko'p slotli "bufer" qatori — standart
 *    klaviaturada bo'lmagan, ko'zi ojiz foydalanuvchi uchun matn belgilash
 *    (selection handles) qiyin bo'lgani sababli qo'shilgan qulaylik: agar hech
 *    narsa belgilanmagan bo'lsa, butun maydon matni nusxalanadi; so'nggi 5 ta
 *    nusxa alohida tugmalarda saqlanadi va istalganini tanlab joylashtirish mumkin.
 */
class AccessibleKeyboardService : InputMethodService(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var capsOn = false
    private var symbolsOn = false
    private lateinit var prefs: SharedPreferences
    private var rootView: LinearLayout? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pendingAction: (() -> Unit)? = null
    private val commitRunnable = Runnable {
        pendingAction?.invoke()
        pendingAction = null
    }

    private val wordBuffer = StringBuilder()

    /** So'nggi nusxalangan/kesilgan matnlar ro'yxati — eng yangisi boshida. */
    private val clipboardBuffer = mutableListOf<String>()

    private val lettersRows = listOf(
        "q w e r t y u i o p",
        "a s d f g h j k l",
        "z x c v b n m"
    )
    private val symbolsRows = listOf(
        "1 2 3 4 5 6 7 8 9 0",
        "@ # $ _ & - + ( )",
        "\" ' : ; / *"
    )

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SPEAK, false) || prefs.getBoolean(KEY_READ_WORD, true)) {
            tts = TextToSpeech(this, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("uz")
        }
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(6, 10, 6, 10)
        }
        // Barmoq tugmalar orasidagi bo'sh joyga (yoki klaviaturadan tashqariga) surilsa,
        // kutilayotgan kiritishni bekor qilamiz — "bekor qilish" gesti.
        root.setOnHoverListener { _, event ->
            if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                cancelPending()
            }
            false
        }
        rootView = root
        buildRows(root)
        return root
    }

    private fun buildRows(root: LinearLayout) {
        root.removeAllViews()
        val rows = if (symbolsOn) symbolsRows else lettersRows

        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            for (ch in row.split(" ")) {
                val label = if (capsOn && !symbolsOn) ch.uppercase() else ch
                rowLayout.addView(makeKey(label))
            }
            root.addView(rowLayout)
        }

        root.addView(makeControlRow())
        root.addView(makeBottomRow())
        root.addView(makeClipboardRow())
        makeBufferRow()?.let { root.addView(it) }
    }

    /** Nusxalash / kesish / joylashtirish tugmalari. */
    private fun makeClipboardRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(Button(this).apply {
            text = "Nusxa"
            contentDescription = "Belgilangan matnni (yoki butun matnni) nusxalash"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setupKeyBehavior(this) { copyToBuffer(cut = false) }
        })
        row.addView(Button(this).apply {
            text = "Kesish"
            contentDescription = "Belgilangan matnni kesish"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setupKeyBehavior(this) { copyToBuffer(cut = true) }
        })
        row.addView(Button(this).apply {
            text = "Joylash"
            contentDescription = "Buferdagi so'nggi nusxadan matnni joylashtirish"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setupKeyBehavior(this) { pasteFromBuffer(0) }
        })
        return row
    }

    /**
     * So'nggi bir nechta nusxalangan matn uchun alohida tugmalar qatori — oddiy
     * klaviaturada faqat bitta joriy buferga ega bo'lasiz, bu yerda bir nechtasidan
     * birini tanlab joylashtirish mumkin.
     */
    private fun makeBufferRow(): LinearLayout? {
        if (clipboardBuffer.isEmpty()) return null
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        clipboardBuffer.forEachIndexed { index, text ->
            row.addView(Button(this).apply {
                this.text = "B${index + 1}"
                contentDescription = "Bufer ${index + 1}: $text"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setupKeyBehavior(this) { pasteFromBuffer(index) }
            })
        }
        return row
    }

    /**
     * Har bir tugmaga uchala xatti-harakatni ham ulaydi:
     *  - oddiy onClick (TalkBack qo'sh-bosishi yoki TalkBacksiz bitta bosish uchun)
     *  - hover asosidagi "surib-tuzatib, ko'tarib kiritish" (single_tap_type yoqilganda)
     *  - uzoq bosish (agar longAction berilgan bo'lsa)
     * Sozlama har safar to'g'ridan-to'g'ri prefs'dan o'qiladi, shu bois Sozlamalar
     * ekranida o'zgartirilgach klaviaturani qayta qurishga hojat yo'q.
     */
    private fun setupKeyBehavior(view: View, longAction: (() -> Unit)? = null, action: () -> Unit) {
        view.setOnClickListener { action() }
        if (longAction != null) {
            view.setOnLongClickListener {
                cancelPending()
                longAction()
                true
            }
        }
        view.setOnHoverListener { _, event ->
            if (!prefs.getBoolean(KEY_SINGLE_TAP, false)) return@setOnHoverListener false
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    // Boshqa tugmadan surilib kelindi — o'sha tugmaning kiritilishini bekor qilamiz.
                    cancelPending()
                    true
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    // Barmoq shu tugmadan chiqdi: qo'shni tugmaga surildimi yoki ko'tarildimi —
                    // buni bilolmaymiz, shu sabab qisqa kechikish bilan "armaymiz". Agar shu vaqt
                    // ichida boshqa hech narsa uni bekor qilmasa (ya'ni barmoq shu yerda ko'tarilgan
                    // bo'lsa), harakat bajariladi.
                    pendingAction = action
                    handler.postDelayed(commitRunnable, HOVER_COMMIT_DELAY_MS)
                    true
                }
                else -> false
            }
        }
    }

    private fun cancelPending() {
        handler.removeCallbacks(commitRunnable)
        pendingAction = null
    }

    private fun makeKey(label: String): Button {
        return Button(this).apply {
            text = label
            contentDescription = label
            textSize = 18f
            minHeight = 130
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setupKeyBehavior(this) { onKey(label) }
        }
    }

    private fun makeControlRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(Button(this).apply {
            text = if (symbolsOn) "ABC" else "?123"
            contentDescription = if (symbolsOn) "Harflarga qaytish" else "Raqam va belgilar"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setupKeyBehavior(this) {
                symbolsOn = !symbolsOn
                rootView?.let { buildRows(it) }
                speak(if (symbolsOn) "Raqam va belgilar" else "Harflar")
            }
        })
        row.addView(Button(this).apply {
            text = if (capsOn) "ABC" else "abc"
            contentDescription = if (capsOn) "Katta harf yoqilgan" else "Kichik harf"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setupKeyBehavior(this) {
                capsOn = !capsOn
                rootView?.let { buildRows(it) }
                speak(if (capsOn) "Katta harf" else "Kichik harf")
            }
        })
        row.addView(Button(this).apply {
            text = "⌫"
            contentDescription = "Ortga o'chirish. Uzoq bosilsa — butun so'z o'chadi"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setupKeyBehavior(this, longAction = { deleteWord() }) {
                deleteOneChar()
            }
        })
        return row
    }

    private fun makeBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(Button(this).apply {
            text = ","
            contentDescription = "Vergul"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setupKeyBehavior(this) { onKey(",") }
        })
        row.addView(Button(this).apply {
            text = "bo'sh joy"
            contentDescription = "Bo'sh joy. Uzoq bosilsa — nuqta qo'yiladi"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
            setupKeyBehavior(this, longAction = { onKey(". ") }) { onKey(" ") }
        })
        row.addView(Button(this).apply {
            text = "."
            contentDescription = "Nuqta"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setupKeyBehavior(this) { onKey(".") }
        })
        row.addView(Button(this).apply {
            text = "⏎"
            contentDescription = "Kirish"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setupKeyBehavior(this) {
                flushWord()
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                feedback("Kirish", FeedbackKind.ENTER)
            }
        })
        return row
    }

    private fun onKey(label: String) {
        currentInputConnection?.commitText(label, 1)
        feedback(label, if (label == " " || label == ". ") FeedbackKind.SPACE else FeedbackKind.LETTER)

        if (label.isBlank() || label in PUNCTUATION) {
            flushWord()
        } else {
            wordBuffer.append(label)
        }

        if (capsOn && label.length == 1 && label != " ") {
            capsOn = false
            rootView?.let { buildRows(it) }
        }
    }

    /** So'z tugaganda (bo'sh joy yoki tinish belgisidan keyin) uni ovozda o'qiydi. */
    private fun flushWord() {
        if (wordBuffer.isNotEmpty()) {
            if (prefs.getBoolean(KEY_READ_WORD, true)) {
                speak(wordBuffer.toString())
            }
            wordBuffer.clear()
        }
    }

    private fun deleteOneChar() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        if (wordBuffer.isNotEmpty()) {
            wordBuffer.deleteCharAt(wordBuffer.length - 1)
        }
        feedback("O'chirildi", FeedbackKind.DELETE)
    }

    /** Backspace uzoq bosilganda — kursor oldidagi butun so'zni bir zumda o'chiradi. */
    private fun deleteWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
        var count = 0
        var i = before.length - 1
        while (i >= 0 && before[i].isWhitespace()) { count++; i-- }
        while (i >= 0 && !before[i].isWhitespace()) { count++; i-- }
        if (count == 0) count = 1
        ic.deleteSurroundingText(count, 0)
        wordBuffer.clear()
        feedback("So'z o'chirildi", FeedbackKind.DELETE)
    }

    /**
     * Belgilangan matnni buferga qo'shadi va tizim clipboard'iga ham yozadi.
     * Agar hech narsa belgilanmagan bo'lsa, joriy matn maydonining butun matnini oladi.
     * [cut] = true bo'lsa, belgilangan qism o'chiriladi ham.
     */
    private fun copyToBuffer(cut: Boolean) {
        val ic = currentInputConnection ?: return
        var text = ic.getSelectedText(0)?.toString()
        val hadSelection = !text.isNullOrEmpty()
        if (text.isNullOrEmpty()) {
            val before = ic.getTextBeforeCursor(2000, 0)?.toString() ?: ""
            val after = ic.getTextAfterCursor(2000, 0)?.toString() ?: ""
            text = before + after
        }
        if (text.isNullOrEmpty()) {
            speak(if (cut) "Kesish uchun matn yo'q" else "Nusxalash uchun matn yo'q")
            return
        }
        clipboardBuffer.remove(text)
        clipboardBuffer.add(0, text)
        while (clipboardBuffer.size > BUFFER_MAX) {
            clipboardBuffer.removeAt(clipboardBuffer.size - 1)
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("akb_buffer", text))

        if (cut && hadSelection) {
            ic.commitText("", 1)
        }
        speak(if (cut) "Kesildi" else "Nusxalandi")
        feedback(if (cut) "Kesildi" else "Nusxalandi", FeedbackKind.BUFFER)
        rootView?.let { buildRows(it) }
    }

    /** Bufer bo'sh bo'lsa, tizim clipboard'idagi joriy matnni ishlatadi. */
    private fun pasteFromBuffer(index: Int) {
        val text = clipboardBuffer.getOrNull(index)
            ?: (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrEmpty()) {
            speak("Bufer bo'sh")
            return
        }
        currentInputConnection?.commitText(text, 1)
        feedback("Joylashtirildi", FeedbackKind.BUFFER)
    }

    private enum class FeedbackKind { LETTER, SPACE, DELETE, ENTER, BUFFER }

    private fun feedback(text: String, kind: FeedbackKind) {
        if (prefs.getBoolean(KEY_VIBRATE, true)) {
            val durationMs = when (kind) {
                FeedbackKind.LETTER -> 15L
                FeedbackKind.SPACE -> 25L
                FeedbackKind.DELETE -> 35L
                FeedbackKind.ENTER -> 50L
                FeedbackKind.BUFFER -> 40L
            }
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(durationMs)
        }
        if (prefs.getBoolean(KEY_SOUND, true)) {
            (getSystemService(AUDIO_SERVICE) as? AudioManager)
                ?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
        }
        if (prefs.getBoolean(KEY_SPEAK, false)) {
            speak(text)
        }
    }

    private fun speak(text: String) {
        if (tts == null) {
            tts = TextToSpeech(this, this)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        handler.removeCallbacks(commitRunnable)
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "akb_prefs"
        const val KEY_VIBRATE = "vibrate_on_key"
        const val KEY_SOUND = "sound_on_key"
        const val KEY_SPEAK = "speak_on_key"
        const val KEY_SINGLE_TAP = "single_tap_type"
        const val KEY_READ_WORD = "read_word_on_finish"
        private const val HOVER_COMMIT_DELAY_MS = 50L
        private const val BUFFER_MAX = 5
        private val PUNCTUATION = setOf(",", ".", "!", "?", ":", ";")
    }
}
