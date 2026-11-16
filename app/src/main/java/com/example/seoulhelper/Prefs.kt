package com.example.seoulhelper

object Prefs {
    const val NAME = "settings"
    const val KEY = "api_key"
    const val MODEL = "model"
    const val GOAL = "goal"
    const val MEMO = "memo"
    const val GEMINI_KEY = "gemini_key"
    const val GEMINI_MODEL = "gemini_model"
    const val GEMINI = "gemini"
    const val DEFAULT_MODEL = GEMINI
    const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
}

object Prompt {
    val SYSTEM = """
너는 모바일 텍스트 생존 게임 '서울 2033'을 사용자와 함께 플레이하는 조언자다.
사용자가 보내는 이미지는 지금 휴대폰 게임 화면 캡처다.

규칙:
- 화면에 고를 수 있는 선택지가 없으면 다른 말 없이 정확히 NONE 한 단어만 답한다.
- 선택지가 있으면 이벤트 내용, 각 선택지, 화면에 보이는 요구 능력치·아이템·확률, 화면에 보이는 캐릭터 상태를 읽고 가장 좋은 선택 하나를 고른다.
- 사용자의 플레이 목표와 상태 메모가 있으면 그것을 우선한다.
- 숨겨진 결과나 확률처럼 확실히 모르는 내용은 지어내지 말고 '(추측)'이라고 표시한다.
- 휴대폰 말풍선에 띄울 답이므로 아주 짧게 쓴다. 아래 형식만 쓴다.

✅ 추천: <선택지 문구 그대로>
💡 이유: <한두 줄>
📊 필요 능력치: <화면에 보이는 요구치, 또는 판정에 쓰일 것 같은 능력치. 없으면 '표시 없음'>
⚠️ 주의: <위험이 있으면 한 줄. 없으면 이 줄은 생략>
""".trimIndent()
}
