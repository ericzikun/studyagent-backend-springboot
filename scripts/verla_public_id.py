#!/usr/bin/env python3
"""
Verla Public ID 编解码工具 - 研发 / 数据分析离线 decode 用

与 VerlaPublicIdCodec.java / TaskIdEncoder.java 共用 Sqids 字母表。
迁移期 URL 可能仍是纯数字，本脚本同样支持。

依赖: pip install sqids

用法:
  python verla_public_id.py decode vc_FxnXM1kBN
  python verla_public_id.py encode conversation 42
"""

from __future__ import annotations

import sys

DEFAULT_ALPHABET = "FxnXM1kBN6cuhsAvjW3Co7l2RePyY8DwaU04Tzt9fHQrqSVKdpimLGIJOgb5ZE"
MIN_LENGTH = 5

PREFIX_TO_TYPE = {
    "vc": "conversation",
    "vt": "turn",
    "vs": "session",
    "vm": "message",
    "va": "artifact",
}

TYPE_TO_PREFIX = {v: k for k, v in PREFIX_TO_TYPE.items()}


def get_sqids():
    try:
        from sqids import Sqids
    except ImportError:
        print("错误: 需要安装 sqids 库", file=sys.stderr)
        print("  pip install sqids", file=sys.stderr)
        sys.exit(1)
    return Sqids(alphabet=DEFAULT_ALPHABET, min_length=MIN_LENGTH)


def is_plain_numeric(value: str) -> bool:
    return value.isdigit()


def decode_public_id(raw: str) -> tuple[str | None, int]:
    trimmed = (raw or "").strip()
    if not trimmed:
        raise ValueError("empty public id")
    if is_plain_numeric(trimmed):
        return ("numeric", int(trimmed))

    prefix = None
    encoded = trimmed
    if "_" in trimmed:
        prefix, encoded = trimmed.split("_", 1)
        prefix = prefix.lower()
        if prefix not in PREFIX_TO_TYPE:
            raise ValueError(f"unknown prefix: {prefix}")

    sqids = get_sqids()
    numbers = sqids.decode(encoded)
    if not numbers:
        raise ValueError(f"invalid sqids payload: {raw}")
    internal_id = numbers[0]
    entity_type = PREFIX_TO_TYPE.get(prefix) if prefix else "legacy_task"
    return (entity_type, internal_id)


def encode_public_id(entity_type: str, internal_id: int) -> str:
    if internal_id <= 0:
        raise ValueError("internal id must be positive")
    sqids = get_sqids()
    encoded = sqids.encode([internal_id])
    prefix = TYPE_TO_PREFIX.get(entity_type)
    if prefix is None:
        if entity_type in ("legacy_task", "task"):
            return encoded
        raise ValueError(f"unknown entity type: {entity_type}")
    return f"{prefix}_{encoded}"


def main() -> None:
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    command = sys.argv[1].strip().lower()
    if command == "decode":
        raw = sys.argv[2]
        entity_type, internal_id = decode_public_id(raw)
        print(f"{raw} -> type={entity_type}, internal_id={internal_id}")
        return

    if command == "encode":
        entity_type = sys.argv[2].strip().lower()
        internal_id = int(sys.argv[3])
        public_id = encode_public_id(entity_type, internal_id)
        print(f"{entity_type}:{internal_id} -> {public_id}")
        return

    print(f"unknown command: {command}", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()
