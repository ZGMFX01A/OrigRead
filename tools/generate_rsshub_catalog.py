"""从 RSSHub 官方 routes.json 生成原读可直接消费的精简路由目录。"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from urllib.parse import urlsplit


PARAMETER = re.compile(r":([A-Za-z_][A-Za-z0-9_]*)(?:\{[^}]*})?(\?)?")
HOST = re.compile(
    r"^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+"
    r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$"
)


def normalize_source(source: str) -> tuple[str, str, str | None] | None:
    """将 Radar source 归一化为 host、路径模板和查询模板。"""
    value = source.strip()
    if not value:
        return None
    if "://" not in value:
        value = f"https://{value}"
    try:
        parsed = urlsplit(value)
        host = (parsed.hostname or "").lower().removeprefix("www.")
    except ValueError:
        return None
    # Radar 中偶尔包含 ${domain}、:subdomain 等运行时主机模板，App 不得猜测目标站点。
    if not HOST.fullmatch(host):
        return None
    path = parsed.path or "/"
    return host, path.rstrip("/") or "/", parsed.query or None


def default_target(namespace: str, route_path: str) -> str:
    """默认目标直接使用官方 route path，保留参数和可选标记。"""
    resolved = route_path.rstrip("/")
    return f"/{namespace}{resolved}" if resolved else f"/{namespace}"


def normalize_target(namespace: str, target: str) -> str:
    """Radar target 通常是命名空间内相对路径，统一补全为 RSSHub 完整路由。"""
    normalized = f"/{target.lstrip('/')}"
    namespace_prefix = f"/{namespace}"
    if normalized == namespace_prefix or normalized.startswith(f"{namespace_prefix}/"):
        return normalized
    return f"{namespace_prefix}{normalized}"


def parameter_names(value: str) -> set[str]:
    return {match.group(1) for match in PARAMETER.finditer(value)}


def literal_prefix(path_template: str) -> str:
    """动态路径只保留首个参数或通配符之前的静态前缀，用于快速排序。"""
    segments: list[str] = []
    for segment in path_template.strip("/").split("/"):
        if not segment or ":" in segment or "*" in segment:
            break
        segments.append(segment)
    return f"/{'/'.join(segments)}" if segments else "/"


def build_catalog(source_path: Path) -> dict:
    raw = json.loads(source_path.read_text(encoding="utf-8"))
    entries: list[dict] = []

    for namespace, namespace_data in raw.items():
        routes = namespace_data.get("routes") or {}
        for route_path, route in routes.items():
            route_name = route.get("name") or namespace_data.get("name") or namespace
            fallback_target = default_target(namespace, route_path)
            for radar in route.get("radar") or []:
                if not isinstance(radar, dict):
                    continue
                target = radar.get("target") or fallback_target
                if not isinstance(target, str):
                    continue
                target = normalize_target(namespace, target)
                sources = radar.get("source") or []
                if isinstance(sources, str):
                    sources = [sources]
                for source in sources:
                    normalized = normalize_source(source)
                    if normalized is None:
                        continue
                    host, source_path, source_query = normalized
                    source_parameters = parameter_names(source_path)
                    if source_query:
                        source_parameters |= parameter_names(source_query)
                    target_parameters = parameter_names(target)
                    # 无法从用户 URL 得到的目标参数不能凭空猜测；保留为需要用户补充的候选。
                    entry = {
                        "id": f"{namespace}:{route_path}:{host}:{source_path}:{source_query or ''}",
                        "name": radar.get("title") or route_name,
                        "host": host,
                        "pathPrefix": literal_prefix(source_path),
                        "target": target,
                    }
                    if source_parameters or target_parameters or source_query or "*" in source_path:
                        entry["sourcePathTemplate"] = source_path
                        if source_query:
                            entry["sourceQueryTemplate"] = source_query
                    entries.append(entry)

    unique = {entry["id"]: entry for entry in entries}
    return {
        "schemaVersion": 2,
        "source": "https://raw.githubusercontent.com/DIYgod/RSSHub/refs/heads/gh-pages/build/routes.json",
        "license": "AGPL-3.0",
        "routeCount": len(unique),
        "routes": sorted(unique.values(), key=lambda item: (item["host"], -len(item["pathPrefix"]), item["name"])),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    catalog = build_catalog(args.source)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(catalog, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"generated {len(catalog['routes'])} routes -> {args.output}")


if __name__ == "__main__":
    main()
