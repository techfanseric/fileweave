package me.zhanghai.android.files.lanshare

/** Shared browser chrome: directory, image and clipboard views keep the same quiet hierarchy. */
internal object LanSharePage {
    enum class Language(val tag: String) {
        ENGLISH("en"), CHINESE("zh-CN");

        fun text(english: String, chinese: String): String =
            if (this == CHINESE) chinese else english

        companion object {
            fun fromTag(tag: String?): Language = if (tag == "zh-CN") CHINESE else ENGLISH
        }
    }

    fun render(title: String, body: String, language: Language = Language.ENGLISH,
               currentTarget: String = "/"): String = """<!doctype html>
<html lang="${language.tag}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark"><title>${escape(title)} · FileWeave</title>
<style>
:root{color-scheme:light dark;--bg:#f7f8fa;--surface:#fff;--ink:#20252c;--muted:#66707d;--line:#dce1e8;--accent:#285c96;--hover:#eef3f9}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,sans-serif}
main{max-width:960px;margin:auto;padding:24px 20px 40px}.brand{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:28px}
.brand strong{font-size:16px;font-weight:650}.brand span,small{color:var(--muted);font-size:13px}h1{font-size:24px;font-weight:650;line-height:1.3;margin:0 0 20px;overflow-wrap:anywhere}
p{margin:12px 0;color:var(--muted)}a{color:var(--accent);text-underline-offset:3px;overflow-wrap:anywhere}
a:focus-visible,button:focus-visible,input:focus-visible,textarea:focus-visible{outline:3px solid var(--accent);outline-offset:3px}
nav{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px;margin:0 0 16px}nav a,.download{display:inline-flex;align-items:center;justify-content:center;min-height:44px;padding:8px 12px;border:1px solid var(--line);border-radius:8px;text-decoration:none;font-size:14px}
section{background:var(--surface);border:1px solid var(--line);border-radius:12px;overflow:hidden}
.entry{display:flex;gap:16px;align-items:center;min-height:72px;padding:12px 16px;border-bottom:1px solid var(--line)}.entry:last-child{border-bottom:0}.entry:hover{background:var(--hover)}
.filename{display:flex;flex:1;min-width:0;gap:2px;flex-direction:column;color:var(--ink);text-decoration:none}.filename span{overflow-wrap:anywhere}.filename small{font-size:13px}.download{flex-shrink:0;background:var(--surface)}
.preview{padding:16px;text-align:center}.preview img{display:block;max-width:100%;height:auto;margin:auto}
textarea{display:block;width:100%;min-height:240px;padding:16px;font:16px/1.6 system-ui;resize:vertical;border:1px solid var(--line);border-radius:12px;margin:16px 0;background:var(--surface);color:var(--ink)}
form{max-width:360px;margin:24px 0}label{display:block;margin-bottom:8px}input,button{font:18px system-ui;padding:12px;border:1px solid var(--line);border-radius:8px;width:100%;min-height:48px;margin-bottom:12px}input{background:var(--surface);color:var(--ink);letter-spacing:.2em}button{background:var(--accent);color:#fff;cursor:pointer}.empty{padding:24px;margin:0}
.language{display:flex;gap:12px;align-items:center;font-size:14px;margin:0}.language a[aria-current=true]{color:var(--ink);font-weight:650;text-decoration-thickness:2px;text-underline-offset:5px}.language a{min-height:44px;display:inline-flex;align-items:center}.brand>div{display:flex;align-items:center;gap:16px;flex-wrap:wrap;justify-content:flex-end}footer{margin-top:24px;color:var(--muted);font-size:13px}footer details{margin-top:8px}footer summary{cursor:pointer;min-height:44px;display:list-item;padding:12px 0}footer p{max-width:65ch;margin:0}
@media(prefers-color-scheme:dark){:root{--bg:#171a20;--surface:#20242c;--ink:#e4e8ee;--muted:#a7b0bd;--line:#3d4551;--accent:#a9cafa;--hover:#293240}button{color:#17263a}}
@media(max-width:480px){main{padding:20px 16px 32px}.brand{margin-bottom:24px}h1{font-size:22px}.entry{gap:12px;padding:12px}.download{padding:8px 10px}}
</style></head><body><main><header class="brand"><strong>FileWeave</strong><div><span>${language.text("Local network · Read only", "局域网 · 只读")}</span><nav class="language" aria-label="${language.text("Language", "语言")}"><a href="${escape(languageTarget(currentTarget, Language.ENGLISH))}" lang="en"${if (language == Language.ENGLISH) " aria-current=\"true\"" else ""}>English</a><a href="${escape(languageTarget(currentTarget, Language.CHINESE))}" lang="zh-CN"${if (language == Language.CHINESE) " aria-current=\"true\"" else ""}>简体中文</a></nav></div></header><h1>${escape(title)}</h1>$body
<footer>${language.text("Available while sharing is running.", "共享期间可持续访问。")}<details><summary>${language.text("About access", "访问说明")}</summary><p>${language.text("Both devices must be on the same local network. The link stops working when sharing or the phone service ends. Clipboard text is only the snapshot explicitly shared from the phone.", "两台设备需在同一局域网。手机停止分享或服务结束后，链接失效；剪贴板只显示在手机上主动分享的文本快照。")}</p></details></footer>
</main></body></html>"""

    internal fun languageTarget(target: String, language: Language): String {
        // Keep the exact scoped path and page flags. Never copy a manual access code into links.
        val query = target.substringAfter('?', "").split('&').filter {
            it.isNotEmpty() && it.substringBefore('=') !in setOf("lang", "code")
        }
        return target.substringBefore('?') + "?" + (query + "lang=${language.tag}").joinToString("&")
    }

    private fun escape(value: String): String = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
