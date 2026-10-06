@file:OptIn(ExperimentalMaterial3Api::class)

package com.rafkhata.app.ui.lecture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rafkhata.app.R
import com.rafkhata.app.data.api.LectureDto
import com.rafkhata.app.data.api.NotesDto
import com.rafkhata.app.ui.common.ErrorBanner
import com.rafkhata.app.ui.common.LoadingBox
import com.rafkhata.app.ui.common.NOTES_LANGS
import com.rafkhata.app.ui.common.isProcessing
import com.rafkhata.app.ui.common.lectureStatusText
import com.rafkhata.app.ui.common.notesLangLabel

/** Holds the notes WebView so the screen can print it to PDF. */
class WebViewHolder {
    var webView: WebView? = null
}

@Composable
fun LangChips(current: String?, available: List<String>, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NOTES_LANGS.forEach { lang ->
            FilterChip(
                selected = lang == current,
                onClick = { onSelect(lang) },
                label = { Text(if (lang in available || lang == current) notesLangLabel(lang) else "+ " + notesLangLabel(lang)) },
            )
        }
    }
}

@Composable
fun NotesTab(
    lecture: LectureDto?,
    lang: String?,
    notes: Content<NotesDto>,
    canRetry: Boolean,
    holder: WebViewHolder,
    onLang: (String) -> Unit,
    onGenerate: (String) -> Unit,
    onRetryProcessing: () -> Unit,
    onSeek: (Double) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (lecture != null && lecture.status != "ready") {
            ProcessingCard(lecture, canRetry, onRetryProcessing)
        }
        if (lecture?.status == "ready") {
            LangChips(lang, lecture.notesLangs, onLang)
        }
        when (notes) {
            Content.Loading -> LoadingBox()
            Content.Pending -> CenteredMessage(stringResource(R.string.notes_being_written), busy = true)
            Content.Missing -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.notes_missing), textAlign = TextAlign.Center)
                if (lang != null && lecture?.status == "ready") {
                    Button(onClick = { onGenerate(lang) }, modifier = Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.generate_notes_in, notesLangLabel(lang)))
                    }
                }
            }
            is Content.Failed -> ErrorBanner(notes.error, onRetry = null)
            is Content.Ready -> NotesWebView(
                html = notes.value.html,
                onSeek = onSeek,
                holder = holder,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ProcessingCard(lecture: LectureDto, canRetry: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            lectureStatusText(lecture),
            style = MaterialTheme.typography.titleSmall,
            color = if (lecture.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        if (lecture.isProcessing()) {
            if (lecture.progress > 0) {
                LinearProgressIndicator(progress = { lecture.progress / 100f }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.processing_hint), style = MaterialTheme.typography.bodySmall)
        }
        if (lecture.status == "failed") {
            lecture.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (canRetry) Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
fun CenteredMessage(text: String, busy: Boolean = false) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.padding(bottom = 12.dp))
        Text(text, textAlign = TextAlign.Center)
    }
}

@SuppressLint("SetJavaScriptEnabled") // only our bundled KaTeX runs; links are intercepted below
@Composable
private fun NotesWebView(html: String, onSeek: (Double) -> Unit, holder: WebViewHolder, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val shell = remember { context.assets.open("notes/shell.html").bufferedReader().use { it.readText() } }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val page = remember(html, dark) {
        shell.replace("__THEME__", if (dark) "dark" else "light").replace("__CONTENT__", html)
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        when (url.scheme) {
                            "rafkhata" -> if (url.host == "seek") {
                                url.getQueryParameter("t")?.toDoubleOrNull()?.let { currentOnSeek(it) }
                            }
                            "http", "https" -> runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                        }
                        return true
                    }
                }
                holder.webView = this
            }
        },
        update = { view ->
            if (view.tag != page) {
                view.tag = page
                view.loadDataWithBaseURL("file:///android_asset/notes/", page, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            if (holder.webView === view) holder.webView = null
            view.destroy()
        },
    )
}

/** Prints the notes page; Android's print dialog offers "Save as PDF". Bangla text shapes correctly. */
fun printNotes(context: Context, webView: WebView, title: String) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    val jobName = title.ifBlank { context.getString(R.string.app_name) }
    printManager.print(
        jobName,
        webView.createPrintDocumentAdapter(jobName),
        PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build(),
    )
}
