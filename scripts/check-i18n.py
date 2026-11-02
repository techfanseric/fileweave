#!/usr/bin/env python3
"""Check English / Simplified Chinese UI resources without an Android build."""
from collections import Counter
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[1] / 'app/src/main/res'
UI_TAGS = {'string', 'plurals', 'string-array'}
FORMAT = re.compile(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]')
HAN = re.compile(r'[\u3400-\u9fff]')


def resources(directory):
    result = {}
    for path in sorted(directory.glob('*.xml')):
        for node in ET.parse(path).getroot():
            if node.tag not in UI_TAGS or node.get('translatable') == 'false':
                continue
            # Upstream stores stable preference keys and protocol values here.
            if path.name.startswith('donottranslate'):
                continue
            name = node.get('name')
            if name in result:
                raise AssertionError(f'Duplicate resource: {directory.name}/{name}')
            result[name] = (path, node)
    return result


def text(node):
    return ''.join(node.itertext())


def arguments(node):
    return Counter(FORMAT.findall(text(node)))


def main():
    english = resources(RES / 'values')
    chinese = resources(RES / 'values-zh-rCN')
    errors = []
    for name, (path, source) in english.items():
        target = chinese.get(name)
        if target is None:
            errors.append(f'{name}: missing Simplified Chinese resource')
            continue
        translation = target[1]
        if source.tag != translation.tag:
            errors.append(f'{name}: resource types differ')
        if source.tag == 'plurals':
            source_other = source.find("item[@quantity='other']")
            if source_other is None or translation.find("item[@quantity='other']") is None:
                errors.append(f'{name}: plurals must include other')
            else:
                for item in translation:
                    equivalent = source.find(f"item[@quantity='{item.get('quantity')}']")
                    if equivalent is None:
                        equivalent = source_other
                    if arguments(equivalent) != arguments(item):
                        errors.append(f'{name}/{item.get("quantity")}: format arguments differ')
        elif source.tag == 'string-array':
            if len(source) != len(translation):
                errors.append(f'{name}: array lengths differ')
            for original, translated in zip(source, translation):
                if arguments(original) != arguments(translated):
                    errors.append(f'{name}: array format arguments differ')
        elif arguments(source) != arguments(translation):
            errors.append(f'{name}: format arguments differ')
        if HAN.search(text(source)):
            errors.append(f'{name}: Chinese text in default English UI resource')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print(f'PASS: {len(english)} English UI resources have Simplified Chinese translations; '
          'types, format arguments, plural forms and English text are consistent.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
