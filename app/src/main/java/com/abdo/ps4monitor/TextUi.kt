@file:OptIn(ExperimentalMaterial3Api::class)
package com.abdo.ps4monitor
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun textRoute(ps4Id: String, path: String) = "text/${Uri.encode(ps4Id)}/${Uri.encode(path)}"

/** Small text viewer / editor over ezRemote getContent + edit (files up to 256 KB). Saving overwrites the file on the PS4. */
@Composable fun TextScreen(ps4Id: String, path: String, nav: NavController) {
    val ps4 = Ps4Repo.get(ps4Id); val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        if (ps4 == null) { err = "PS4 profile not found"; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                val size = EzRemote.fileSize(ps4, path, 10_000)
                if (size > 256 * 1024) throw EzError(tr("Too large to edit here (max 256 KB).", "كبير جدًا للتحرير هنا (الحد 256 KB)."))
                val c = EzRemote.getContent(ps4, path, 15_000)
                original = c; text = c
            } catch (e: Exception) { err = Tx.t(PkgInspector.friendly(e)) }
        }
    }
    val dirty = original != null && text != original
    BackHandler(enabled = dirty) { leave = true }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackHeader(path.substringAfterLast('/'), nav) {
            IconButton(enabled = dirty && !saving, onClick = {
                val p = ps4 ?: return@IconButton
                saving = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { try { EzRemote.edit(p, path, text) } catch (e: Exception) { EzRemote.OpResult(false, EzRemote.friendly(e)) } }
                    saving = false
                    if (r.ok) { original = text; Toast.makeText(ctx, tr("Saved on the PS4.", "تم الحفظ على الـPS4."), Toast.LENGTH_SHORT).show() }
                    else Toast.makeText(ctx, Tx.t(r.message), Toast.LENGTH_LONG).show()
                }
            }) { Ico(R.drawable.ic_check_circle) }
        }
        Dim(path, maxLines = 2)
        when {
            err != null -> Text(err.orEmpty(), color = MaterialTheme.colorScheme.error)
            original == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().weight(1f), textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
        }
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text(tr("Discard changes?", "تجاهل التعديلات؟")) },
        text = { Text(tr("The file has unsaved changes.", "الملف فيه تعديلات غير محفوظة.")) },
        confirmButton = { TextButton(onClick = { leave = false; nav.popBackStack() }) { Lbl(tr("Discard", "تجاهل"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { leave = false }) { Lbl(tr("Keep editing", "متابعة التحرير")) } })
}
