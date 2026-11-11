"""Структурная проверка Swift-файлов без компилятора.

Считает баланс скобок вне строк и комментариев — ловит обрывы и склейки
при ручном редактировании. Запуск: python tools/swift_structure_check.py ios

Это не замена `swiftc`: синтаксис проверяет Xcode/CI на macOS.
"""
import pathlib
import sys

OPENING = "({["
CLOSING = ")}]"
PAIRS = {")": "(", "}": "{", "]": "["}


def strip_non_code(source: str) -> str:
    """Убирает строки и комментарии, сохраняя переводы строк."""
    result = []
    index = 0
    length = len(source)
    in_line_comment = False
    in_block_comment = False
    in_string = False
    escaped = False

    while index < length:
        char = source[index]
        pair = source[index:index + 2]

        if in_line_comment:
            if char == "\n":
                in_line_comment = False
                result.append(char)
            else:
                result.append(" ")
            index += 1
            continue

        if in_block_comment:
            if pair == "*/":
                in_block_comment = False
                result.append("  ")
                index += 2
                continue
            result.append("\n" if char == "\n" else " ")
            index += 1
            continue

        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            result.append("\n" if char == "\n" else " ")
            index += 1
            continue

        if pair == "//":
            in_line_comment = True
            result.append("  ")
            index += 2
            continue
        if pair == "/*":
            in_block_comment = True
            result.append("  ")
            index += 2
            continue
        if char == '"':
            in_string = True
            result.append(" ")
            index += 1
            continue

        result.append(char)
        index += 1

    return "".join(result)


def check(path: pathlib.Path) -> list[str]:
    """Возвращает список проблем файла."""
    source = path.read_text(encoding="utf-8")
    code = strip_non_code(source)
    stack: list[tuple[str, int]] = []
    problems: list[str] = []
    line = 1

    for char in code:
        if char == "\n":
            line += 1
        elif char in OPENING:
            stack.append((char, line))
        elif char in CLOSING:
            if not stack:
                problems.append(f"{path}: лишняя '{char}' на строке {line}")
                continue
            opening, opening_line = stack.pop()
            if PAIRS[char] != opening:
                problems.append(
                    f"{path}: '{char}' на строке {line} закрывает "
                    f"'{opening}' со строки {opening_line}"
                )
    for opening, opening_line in stack:
        problems.append(
            f"{path}: не закрыта '{opening}' со строки {opening_line}"
        )
    return problems


def main() -> None:
    """Проверяет все *.swift в переданных каталогах."""
    roots = sys.argv[1:] or ["ios"]
    problems: list[str] = []
    files = 0
    for root in roots:
        for path in pathlib.Path(root).rglob("*.swift"):
            files += 1
            problems.extend(check(path))

    for problem in problems:
        print(problem)
    print(f"проверено файлов: {files}, проблем: {len(problems)}")
    raise SystemExit(1 if problems else 0)


if __name__ == "__main__":
    main()
