#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/AddEditArticleScreen.kt"

cp "$FILE" "$FILE.bak_edit_mode"

# добавляем repo import
grep -q SQLiteRepo "$FILE" || sed -i '2 a import com.ml.app.data.SQLiteRepo\nimport androidx.compose.ui.platform.LocalContext\nimport kotlinx.coroutines.launch\nimport androidx.compose.runtime.rememberCoroutineScope' "$FILE"

# добавляем состояния режима
sed -i '36 a\
    val ctx = LocalContext.current\
    val repo = remember { SQLiteRepo(ctx) }\
    val scope = rememberCoroutineScope()\
\
    var editMode by remember { mutableStateOf(false) }\
    var bagList by remember { mutableStateOf<List<String>>(emptyList()) }\
' "$FILE"

# добавляем UI переключатель режима
sed -i '85 a\
        Spacer(modifier = Modifier.height(8.dp))\
\
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {\
            Button(onClick = { editMode = false }) { Text("Новый") }\
            Button(onClick = {\
                editMode = true\
                scope.launch {\
                    bagList = repo.loadTimeline(180).flatMap { it.byBags }.map { it.bagId }.distinct().sorted()\
                }\
            }) { Text("Редактировать") }\
        }\
\
        if (editMode) {\
            Spacer(modifier = Modifier.height(12.dp))\
            Text("Выберите артикул")\
\
            for (bag in bagList) {\
                OutlinedButton(\
                    modifier = Modifier.fillMaxWidth(),\
                    onClick = { }\
                ) {\
                    Text(bag)\
                }\
            }\
\
            Spacer(modifier = Modifier.height(12.dp))\
        }\
' "$FILE"

git add "$FILE"
git commit -m "UI: add edit mode to article screen" || echo "Нет изменений"
git push
