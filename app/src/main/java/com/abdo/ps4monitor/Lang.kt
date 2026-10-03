package com.abdo.ps4monitor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** 0 = follow phone, 1 = English, 2 = Arabic. Observable: changing it re-composes the whole UI instantly. */
object Lang {
    var mode by mutableIntStateOf(0)
    val isAr: Boolean get() = when (mode) { 1 -> false; 2 -> true; else -> Locale.getDefault().language == "ar" }
}
fun tr(en: String, ar: String) = if (Lang.isAr) ar else en

/**
 * Translates the English status/error sentences produced by the monitor engine at DISPLAY time.
 * Records keep their original English text, so switching language also re-translates old downloads.
 */
object Tx {
    private val m = mapOf(
        "The app closed while sending. The request was NOT repeated; check the PS4 or retry." to "أُغلق التطبيق أثناء الإرسال. لم يُعَد إرسال الطلب؛ تحقق من الـPS4 أو أعد المحاولة.",
        "ezRemote accepted the request. Waiting for the PS4 to start." to "قبل ezRemote الطلب. بانتظار أن يبدأ الـPS4 التحميل.",
        "Monitoring resumed (nothing was sent to ezRemote)." to "استُؤنفت المراقبة (لم يُرسل شيء إلى ezRemote).",
        "Monitoring stopped by you. The PS4 download itself is not cancelled." to "أوقفتَ المراقبة. التحميل على الـPS4 نفسه لم يُلغَ.",
        "File chosen by you." to "الملف اختير يدويًا.",
        "Detected on the PS4 (not started from this app)." to "اكتُشف على الـPS4 (لم يبدأ من هذا التطبيق).",
        "That is not a valid http(s) link." to "هذا ليس رابط http(s) صالحًا.",
        "This PS4 has no IP address yet." to "لا يوجد عنوان IP لهذا الـPS4.",
        "Download not found." to "التحميل غير موجود.",
        "That PS4 profile no longer exists." to "ملف هذا الـPS4 لم يعد موجودًا.",
        "This download was detected on the PS4, so there is no link to resend." to "اكتُشف هذا التحميل على الـPS4 لذا لا يوجد رابط لإعادة إرساله.",
        "Retry is only available for failed or not-started downloads." to "إعادة المحاولة متاحة فقط للتحميلات الفاشلة أو التي لم تبدأ.",
        "PS4 monitoring connection interrupted. Reconnecting…" to "انقطع اتصال المراقبة بالـPS4. جارٍ إعادة الاتصال…",
        "Download file detected" to "اكتُشف ملف التحميل",
        "File detected; waiting for data…" to "اكتُشف الملف؛ بانتظار البيانات…",
        "Waiting for PS4 download to start…" to "بانتظار أن يبدأ الـPS4 التحميل…",
        "ezRemote accepted the request but no download activity was detected." to "قبل ezRemote الطلب لكن لم يُكتشف أي نشاط تحميل.",
        "Temporary file disappeared — confirming…" to "اختفى الملف المؤقت — جارٍ التأكد…",
        "The download file disappeared before any data was seen." to "اختفى ملف التحميل قبل ظهور أي بيانات.",
        "The temporary file disappeared and no finished file was found (the download may have been cancelled on the PS4)." to "اختفى الملف المؤقت ولم يوجد ملف مكتمل (ربما أُلغي التحميل على الـPS4).",
        "The temporary file disappeared but the finished file could not be identified." to "اختفى الملف المؤقت لكن تعذّر تحديد الملف المكتمل.",
        "Looking for the finished file…" to "جارٍ البحث عن الملف المكتمل…",
        "Checking downloaded file…" to "جارٍ فحص الملف المحمّل…",
        "The finished file disappeared while it was being checked." to "اختفى الملف المكتمل أثناء الفحص.",
        "The finished file is stable. Expected size was unknown, so this was verified by file lifecycle only." to "الملف المكتمل مستقر. الحجم المتوقع غير معروف لذا تم التحقق من دورة حياة الملف فقط.",
        "The finished file matches the expected size." to "الملف المكتمل يطابق الحجم المتوقع.",
        "Expected size reached — waiting for the PS4 to finish the file…" to "بلغ الحجم المتوقع — بانتظار أن ينهي الـPS4 الملف…",
        "PS4 connection lost" to "انقطع الاتصال بالـPS4",
        "Reached the expected size and stayed stable (the PS4 kept the temporary file name)." to "بلغ الحجم المتوقع وبقي ثابتًا (أبقى الـPS4 اسم الملف المؤقت).",
        "PKG header (agrees with PFS image end)" to "ترويسة PKG (تطابق نهاية صورة PFS)", "PKG header" to "ترويسة PKG", "PKG header (unconfirmed)" to "ترويسة PKG (غير مؤكد)",
        "entered by you" to "أدخلته أنت",
        "known" to "معروف",
        // connection / probe
        "The PS4 did not answer in time." to "لم يستجب الـPS4 في الوقت المناسب.",
        "Cannot reach the PS4 web server. Is ezRemote running with its web server enabled?" to "تعذّر الوصول إلى خادم الويب في الـPS4. هل ezRemote يعمل وخادم الويب مفعّل؟",
        "PS4 address not found." to "عنوان الـPS4 غير موجود.",
        "PS4 web connection problem." to "مشكلة في اتصال ويب الـPS4.",
        "Timeout" to "انتهت المهلة", "Network Unavailable" to "الشبكة غير متاحة", "FTP Connection Failed" to "فشل اتصال FTP",
        "FTP Authentication Failed" to "فشل تسجيل الدخول إلى FTP", "Permission Denied" to "تم رفض الإذن",
        "ezRemote web" to "ويب ezRemote", "OK" to "يعمل",
    )
    private val pats: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("This link is already being downloaded to (.*)\\.") to { r -> "هذا الرابط قيد التحميل بالفعل إلى ${r.groupValues[1]}." },
        Regex("No download progress detected for (\\d+) s\\.") to { r -> "لم يُكتشف تقدم في التحميل منذ ${r.groupValues[1]} ثانية." },
        Regex("The finished file is (.*) but (.*) was expected\\.") to { r -> "حجم الملف المكتمل ${r.groupValues[1]} لكن المتوقع ${r.groupValues[2]}." },
        Regex("ezRemote answered HTTP (\\d+)\\.") to { r -> "أجاب ezRemote برمز HTTP ${r.groupValues[1]}." },
        Regex("ezRemote rejected the request\\.") to { _ -> "رفض ezRemote الطلب." },
        Regex("ezRemote rejected the request: (.*)") to { r -> "رفض ezRemote الطلب: ${r.groupValues[1]}" },
        Regex("File Not Found \\((.*)\\)") to { r -> "المجلد غير موجود (${r.groupValues[1]})" },
        Regex("connected, but (.*)") to { r -> "متصل، لكن ${t(r.groupValues[1])}" },
        Regex("server size, (.*)") to { _ -> "حجم من الخادم" },
        Regex("Now monitoring (.*)") to { r -> "جارٍ مراقبة ${r.groupValues[1]}" },
        Regex("sent to (.*)") to { r -> "أُرسل إلى ${r.groupValues[1]}" },
        Regex("connection lost, reconnecting…") to { _ -> "انقطع الاتصال، جارٍ إعادة الاتصال…" },
        Regex("connection restored") to { _ -> "عاد الاتصال" },
        Regex("stalled") to { _ -> "متعثّر" },
        Regex("progress resumed") to { _ -> "عاد التقدم" },
        Regex("download started") to { _ -> "بدأ التحميل" },
        Regex("completed") to { _ -> "اكتمل" },
        Regex("download has not started") to { _ -> "لم يبدأ التحميل" },
    )
    fun t(s: String): String {
        if (!Lang.isAr || s.isBlank()) return s
        m[s]?.let { return it }
        for ((r, f) in pats) r.matchEntire(s)?.let { return f(it) }
        val i = s.indexOf(": ")
        if (i > 0) { val l = s.substring(0, i); val r = s.substring(i + 2); val a = t(l); val b = t(r); if (a != l || b != r) return "$a: $b" }
        return s
    }
    fun lines(s: String) = if (!Lang.isAr) s else s.lines().joinToString("\n") { t(it) }
    fun ev(s: String): String { if (!Lang.isAr) return s; val i = s.indexOf("  "); return if (i < 0) t(s) else s.substring(0, i) + "  " + t(s.substring(i + 2)) }
}
