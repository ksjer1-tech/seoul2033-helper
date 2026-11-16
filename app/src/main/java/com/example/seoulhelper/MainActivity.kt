package com.example.seoulhelper

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }
    private lateinit var keyEdit: EditText
    private lateinit var goalEdit: EditText
    private lateinit var memoEdit: EditText
    private lateinit var modelGroup: RadioGroup

    private lateinit var geminiKeyEdit: EditText
    private lateinit var geminiModelEdit: EditText

    private val models = listOf(
        Prefs.GEMINI to "Gemini (무료, 하루 사용량 제한)",
        "claude-haiku-5-5" to "Claude Haiku (유료, 저렴)",
        "claude-sonnet-5-5" to "Claude Sonnet (유료, 더 똑똑함)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 15f
            setPadding(0, pad, 0, pad / 4)
        }

        root.addView(TextView(this).apply { text = "서울 2033 도우미"; textSize = 22f })

        root.addView(label("Gemini API 키 (무료, aistudio.google.com)"))
        geminiKeyEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "AIza... 또는 AQ...."
            setText(prefs.getString(Prefs.GEMINI_KEY, ""))
        }
        root.addView(geminiKeyEdit)

        root.addView(label("Gemini 모델 이름"))
        geminiModelEdit = EditText(this).apply {
            setText(prefs.getString(Prefs.GEMINI_MODEL, Prefs.DEFAULT_GEMINI_MODEL))
        }
        root.addView(geminiModelEdit)

        root.addView(label("Claude API 키 (선택, 유료)"))
        keyEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "sk-ant-..."
            setText(prefs.getString(Prefs.KEY, ""))
        }
        root.addView(keyEdit)

        root.addView(label("사용할 AI"))
        modelGroup = RadioGroup(this)
        val saved = prefs.getString(Prefs.MODEL, Prefs.DEFAULT_MODEL)
        models.forEachIndexed { index, (modelId, name) ->
            val rb = RadioButton(this)
            rb.text = name
            rb.id = index + 1
            rb.tag = modelId
            modelGroup.addView(rb)
            if (modelId == saved) modelGroup.check(rb.id)
        }
        root.addView(modelGroup)

        root.addView(label("플레이 목표 (선택)"))
        goalEdit = EditText(this).apply {
            hint = "예: 최대한 오래 생존 / 특정 엔딩 / 동료 살리기"
            setText(prefs.getString(Prefs.GOAL, ""))
        }
        root.addView(goalEdit)

        root.addView(label("내 상태 메모 (선택)"))
        memoEdit = EditText(this).apply {
            hint = "화면에 안 보이는 정보. 예: 근력 높음, 식량 부족, 동료 2명"
            minLines = 2
            setText(prefs.getString(Prefs.MEMO, ""))
        }
        root.addView(memoEdit)

        root.addView(Button(this).apply {
            text = "시작"
            setOnClickListener { start() }
        })
        root.addView(Button(this).apply {
            text = "중지"
            setOnClickListener {
                save()
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            }
        })

        root.addView(TextView(this).apply {
            setPadding(0, pad, 0, 0)
            text = """
사용법
1. API 키를 넣고 [시작]
2. '다른 앱 위에 표시' 권한을 켜기
3. 화면 녹화 창이 뜨면 '앱 하나'를 고르고 서울 2033을 선택 (전체 화면도 가능)
4. 게임 위에 🎯 버튼이 떠요
   · 탭: 지금 화면 분석
   · 길게 누르기: 자동 모드 (화면이 바뀌면 알아서 분석)
   · 드래그: 위치 옮기기
5. 추천 말풍선은 탭하면 접히고, 드래그로 옮길 수 있어요
            """.trimIndent()
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun save() {
        val checked = modelGroup.findViewById<RadioButton>(modelGroup.checkedRadioButtonId)
        prefs.edit()
            .putString(Prefs.KEY, keyEdit.text.toString().trim())
            .putString(Prefs.GEMINI_KEY, geminiKeyEdit.text.toString().trim())
            .putString(
                Prefs.GEMINI_MODEL,
                geminiModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_GEMINI_MODEL }
            )
            .putString(Prefs.MODEL, (checked?.tag as? String) ?: Prefs.DEFAULT_MODEL)
            .putString(Prefs.GOAL, goalEdit.text.toString().trim())
            .putString(Prefs.MEMO, memoEdit.text.toString().trim())
            .apply()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun start() {
        save()
        val useGemini = prefs.getString(Prefs.MODEL, Prefs.DEFAULT_MODEL) == Prefs.GEMINI
        if ((useGemini && geminiKeyEdit.text.isBlank()) || (!useGemini && keyEdit.text.isBlank())) {
            toast("고른 AI의 API 키를 먼저 입력해 주세요")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("'다른 앱 위에 표시' 권한을 켠 뒤 다시 [시작]을 눌러 주세요")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        launchCapture()
    }

    private fun launchCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) launchCapture()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
            startForegroundService(i)
            toast("시작! 이제 서울 2033을 켜세요")
            moveTaskToBack(true)
        } else {
            toast("화면 캡처 권한이 있어야 화면을 볼 수 있어요")
        }
    }

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIF = 2
    }
}
