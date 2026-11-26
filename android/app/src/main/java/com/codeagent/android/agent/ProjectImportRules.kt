package com.codeagent.android.agent

/** Pure validation rules for SAF project imports. */
internal object ProjectImportRules {
    private const val MAX_DIRECTORY_DEPTH = 64

    fun requireSafeName(name: String) {
        require(
            name.isNotBlank() &&
                name != "." &&
                name != ".." &&
                '/' !in name &&
                '\\' !in name &&
                '\u0000' !in name
        ) { "نام فایل یا پوشه نامعتبر است." }
    }

    fun requireDepth(depth: Int) {
        require(depth in 0..MAX_DIRECTORY_DEPTH) { "عمق پوشه‌های پروژه بیش از حد مجاز است." }
    }
}
