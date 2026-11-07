# Артефакты тестирования

- `results.csv` — результаты прогона тестового набора через приложение (WER, DER, ошибка границ, время обработки, RTF)
  по каждому файлу. Методика и выводы — `docs/TEST_RESULTS.md`.

Тестовые аудио и эталоны генерируются скриптами `tools/eval/make_piper_dialogs.py` и `tools/eval/make_test_dialog.py`
(в Git не хранятся). Прогон: `tools/eval/device_eval.sh <файлы>`.
