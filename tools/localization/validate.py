"""Validate Android English/Chinese strings and optionally refresh the JVM fallback.

Run: python tools/localization/validate.py [--write-fallbacks]
Edit res/values/strings.xml (English) and res/values-zh/strings.xml (Chinese).
Keep resource IDs stable; they are used by UI, services and workers.
"""
from pathlib import Path
import json
import re
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
res = root / 'app/src/main/res'

def read(folder):
    items = {}
    for item in ET.parse(res / folder / 'strings.xml').getroot():
        key = item.attrib['name']
        assert key not in items, f'Duplicate string: {folder}/{key}'
        text = ''.join(item.itertext())
        if text.startswith('"') and text.endswith('"'):
            text = text[1:-1]  # AAPT quotes preserve deliberate spaces; not display text.
        items[key] = re.sub(r'\\([nrt\'"\\])', lambda m: {'n':'\n','r':'\r','t':'\t'}.get(m[1],m[1]), text)
    return items

english, chinese = read('values'), read('values-zh')
assert english.keys() == chinese.keys(), 'English and Chinese resource keys differ'
for key, text in english.items():
    assert not re.search('[\u3400-\u9fff]', text), f'Untranslated English UI: {key}'
    placeholders = lambda s: sorted(re.findall(r'(?<!%)%([1-9][0-9]*)\$s', s))
    assert placeholders(text) == placeholders(chinese[key]), f'Placeholder mismatch: {key}'
    assert text.strip() and chinese[key].strip(), f'Empty resource: {key}'
    assert text.count('%') == len(placeholders(text)) + text.count('%%') * 2, f'Unescaped format percent: {key}' if placeholders(text) else ''

if '--write-fallbacks' in sys.argv:
    lines = ['package app.shiguang.mobile', '', '// Chinese fallback for JVM-only tests before an Android Application exists.', 'internal val textFallbacks = mapOf(']
    for key, text in chinese.items():
        lines.append(f'    R.string.{key} to ' + json.dumps(text,ensure_ascii=False).replace('$','\\$') + ',')
    lines.append(')')
    (root/'app/src/main/java/app/shiguang/mobile/TextFallbacks.kt').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print(f'Validated {len(english)} English/Chinese resources and matching format arguments.')
