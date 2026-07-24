#!/usr/bin/env python3
"""Generate random agent-context records as JSON."""

import argparse
import hashlib
import json
import random
import uuid
from pathlib import Path


USER_NAMES = [
    "root",
    "ubuntu",
    "runner",
    "agent",
    "audit",
    "service",
    "security",
    "ops",
    "worker",
]

HOME_PATHS = [
    "/home/{user}",
    "/opt/{user}",
    "/srv/{user}",
    "/var/lib/{user}",
    "/tmp/{user}",
]

PLATFORMS = ["linux", "linux", "linux", "darwin", "windows"]
ARCHS = ["x86_64", "x86_64", "arm64", "aarch64"]
AGENT_KINDS = [
    "security-audit-trace",
    "tender-search-agent",
    "document-analysis-agent",
    "risk-control-agent",
]
CHANNELS = ["s01", "s02", "s10", "s20", "s30", "s40", "beta", "prod"]
SKILL_NAMES = [
    "tender-search",
    "security-check",
    "audit-engine",
    "trace-analysis",
]


def random_hash() -> str:
    """Return a SHA-256 hash derived from random data."""
    random_data = f"{uuid.uuid4()}{random.random()}"
    return hashlib.sha256(random_data.encode()).hexdigest()


def random_version() -> str:
    """Return a random semantic version string."""
    return f"{random.randint(1, 5)}.{random.randint(0, 9)}.{random.randint(0, 20)}"


def generate_hostname() -> str:
    """Return a randomized hostname."""
    prefix = random.choice(
        ["audit", "trace", "security", "worker", "agent", "runtime"]
    )
    return f"{prefix}-{uuid.uuid4()}"


def generate_context() -> dict[str, object]:
    """Generate one random agent-context record."""
    username = random.choice(USER_NAMES)
    return {
        "device_features": {
            "hostname": generate_hostname(),
            "platform": random.choice(PLATFORMS),
            "arch": random.choice(ARCHS),
            "username": username,
            "home_path": random.choice(HOME_PATHS).format(user=username),
            "mac_hash": random_hash(),
        },
        "agent_kind": random.choice(AGENT_KINDS),
        "agent_version": str(random.randint(1, 5)),
        "skill_version": f"{random.choice(SKILL_NAMES)}-{random_version()}",
        "ch": random.choice(CHANNELS),
    }


def parse_args() -> argparse.Namespace:
    """Parse command-line arguments."""
    parser = argparse.ArgumentParser(
        description="生成随机 agent context JSON 数据。"
    )
    parser.add_argument("-n", "--number", type=int, default=5, help="生成数量")
    parser.add_argument("-o", "--output", default="agent_context.json", help="输出文件")
    args = parser.parse_args()
    if args.number < 0:
        parser.error("生成数量不能为负数")
    return args


def main() -> None:
    """Generate records and write them to the configured output path."""
    args = parse_args()
    data = [generate_context() for _ in range(args.number)]
    output_path = Path(args.output)

    with output_path.open("w", encoding="utf-8") as output_file:
        json.dump(data, output_file, indent=2, ensure_ascii=False)
        output_file.write("\n")

    print(f"生成完成: {output_path}")


if __name__ == "__main__":
    main()
