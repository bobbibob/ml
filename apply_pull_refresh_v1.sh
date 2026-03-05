set -euo pipefail

FILE="app/src/main/java/com/ml/app/ui/SummaryScreen.kt"

python3 - <<'PY'
from pathlib import Path
p = Path("app/src/main/java/com/ml/app/ui/SummaryScreen.kt")
txt = p.read_text(encoding="utf-8")

# 1) Добавляем нужные импорты для pullRefresh (Material)
need_imports = [
  "import androidx.compose.material.ExperimentalMaterialApi",
  "import androidx.compose.material.pullrefresh.PullRefreshIndicator",
  "import androidx.compose.material.pullrefresh.pullRefresh",
  "import androidx.compose.material.pullrefresh.rememberPullRefreshState",
]
for imp in need_imports:
  if imp not in txt:
    # вставим после DatePickerDialog импорта (или просто после package/import блока)
    pass

# грубо вставим после строки с DatePickerDialog, если найдём
anchor = "import android.app.DatePickerDialog"
if anchor in txt:
  parts = txt.split(anchor, 1)
  head = parts[0] + anchor + parts[1].splitlines(True)[0]
  rest = parts[1].splitlines(True)[1:]
  # но проще: вставим импорты сразу после anchor строки
  lines = txt.splitlines()
  out = []
  inserted = False
  for line in lines:
    out.append(line)
    if (not inserted) and line.strip() == anchor:
      out.extend(need_imports)
      inserted = True
  txt = "\n".join(out) + ("\n" if not out[-1].endswith("\n") else "")
else:
  # fallback: вставим после package строки
  lines = txt.splitlines()
  out = []
  inserted = False
  for i,line in enumerate(lines):
    out.append(line)
    if (not inserted) and line.startswith("import "):
      # вставим после первого import
      out.extend(need_imports)
      inserted = True
      # но чтобы не вставлять несколько раз — сразу отметим
  txt = "\n".join(out) + "\n"

# 2) Включаем OptIn для ExperimentalMaterialApi (pullRefresh)
# У тебя уже есть @OptIn(ExperimentalMaterial3Api::class) — добавим туда ExperimentalMaterialApi
txt = txt.replace(
  "@OptIn(ExperimentalMaterial3Api::class)",
  "@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)"
)

# 3) Убираем кнопку "Обновить" в хедере на таймлайне.
# Идея: оставить только "Назад" в деталях, а на таймлайне ничего.
txt = txt.replace(
  '        } else {\n          TextButton(onClick = { vm.refreshTimeline() }) { Text("Обновить", color = TextBlack) }\n        }',
  '        }'
)

# 4) Добавляем pull-to-refresh вокруг основного контента.
# Ищем место перед Column(...) и оборачиваем его в Box + pullRefreshState.
# Внутри: val pullState = rememberPullRefreshState(...)
marker = "  Column(\n    modifier = Modifier"
if marker in txt and "rememberPullRefreshState" not in txt:
  insert = """  val refreshing = state.loading
  val pullState = rememberPullRefreshState(
    refreshing = refreshing,
    onRefresh = { vm.checkRemoteAndUpdateIfChanged() }
  )

  Box(
    modifier = Modifier
      .fillMaxSize()
      .pullRefresh(pullState)
  ) {
"""
  txt = txt.replace(marker, insert + marker)

  # закрываем Box в конце SummaryScreen перед последней закрывающей скобкой функции.
  # Добавим индикатор прямо перед самым последним блоком status (если он есть),
  # иначе просто в конец Column-контента.
  # Найдём последнюю строку "}" функции SummaryScreen и вставим перед ней.
  # Проще: вставим PullRefreshIndicator перед окончанием Box — после блока status, если есть.
  indicator = """
    PullRefreshIndicator(
      refreshing = refreshing,
      state = pullState,
      modifier = Modifier.align(Alignment.TopCenter)
    )
  }
"""
  # вставим перед самым последним "}" файла? нет. найдём "}" закрывающую SummaryScreen:
  # эвристика: вставить перед строкой "}" которая закрывает @Composable fun SummaryScreen
  lines = txt.splitlines()
  out=[]
  inserted=False
  # найдём последнюю строку, которая равна "}" и после неё идёт пусто или @Composable другого
  # проще: вставить indicator перед первой строкой, которая начинается с "}" и дальше идёт "@Composable" (TimelineList)
  for i,line in enumerate(lines):
    if (not inserted) and line.strip() == "}" and i+1 < len(lines) and lines[i+1].lstrip().startswith("@Composable"):
      out.append(indicator.rstrip("\n"))
      out.append(line)
      inserted=True
    else:
      out.append(line)
  txt = "\n".join(out) + "\n"

# 5) На экране "Нет базы" — не показываем кнопку, просто текст (у тебя уже так)
# 6) Убедимся, что Alignment импортирован (он есть), иначе PullRefreshIndicator не соберётся
if "import androidx.compose.ui.Alignment" not in txt:
  # вставим после import Modifier
  txt = txt.replace("import androidx.compose.ui.Modifier\n", "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.Alignment\n")

p.write_text(txt, encoding="utf-8")
print("OK: patched SummaryScreen.kt (pull-to-refresh + removed refresh button).")
PY

echo "DONE. Now commit & push:"
echo "  git add app/src/main/java/com/ml/app/ui/SummaryScreen.kt"
echo "  git commit -m \"UI: pull-to-refresh pack update (no refresh button)\""
echo "  git push"
