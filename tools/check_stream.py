"""Проверка разметки записи потока fake_viewer (.stream)."""
import sys

with open(sys.argv[1] if len(sys.argv) > 1 else "screenshots/h264.jpg.stream",
          "rb") as handle:
    data = handle.read()

print("размер", len(data))
offset = 0
count = 0
while offset + 5 <= len(data) and count < 5:
    kind = data[offset]
    size = int.from_bytes(data[offset + 1:offset + 5], "big")
    print(count, "kind", kind, "size", size)
    offset += 5 + size
    count += 1
print("следующий offset", offset)
