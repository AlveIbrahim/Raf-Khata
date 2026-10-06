package com.rafkhata.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rafkhata.app.R

/** Notes languages the backend supports. */
val NOTES_LANGS = listOf("bn", "en", "mixed")

@Composable
fun notesLangLabel(lang: String): String = when (lang) {
    "bn" -> stringResource(R.string.lang_bn)
    "en" -> stringResource(R.string.lang_en)
    "mixed" -> stringResource(R.string.lang_mixed)
    else -> lang
}

/** Radio list of notes languages. [allowDefault] adds a "same as my profile" option (null). */
@Composable
fun NotesLangPicker(selected: String?, onSelect: (String?) -> Unit, allowDefault: Boolean = false) {
    val options: List<String?> = if (allowDefault) listOf(null) + NOTES_LANGS else NOTES_LANGS
    Column(Modifier.selectableGroup()) {
        options.forEach { lang ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = lang == selected, onClick = { onSelect(lang) }, role = Role.RadioButton)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = lang == selected, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(if (lang == null) stringResource(R.string.lang_default) else notesLangLabel(lang))
                    if (lang == "mixed") {
                        Text(
                            stringResource(R.string.lang_mixed_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
