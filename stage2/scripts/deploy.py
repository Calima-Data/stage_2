#!/usr/bin/env python3
"""Runs the whole system on this machine: every service, its replicas and the Control Module.

    python scripts/deploy.py up --data-dir C:/tmp/search --ingestion 1 --indexing 2 --search 2
    python scripts/deploy.py status --data-dir C:/tmp/search
    python scripts/deploy.py down --data-dir C:/tmp/search

Ports: Control 7000; Ingestion 7001, 7011, ...; Indexing 7002, 7012, ...; Search 7003, 7013, ...
Use a --data-dir outside any folder synced to the cloud: SQLite must see a local file system.
"""
import argparse
import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

import requests

STAGE2 = Path(__file__).resolve().parent.parent
MOCK_PORT = 7900
CONTROL_PORT = 7000
BASE_PORTS = {"ingestion": 7001, "indexing": 7002, "search": 7003}
JARS = {
    "ingestion": STAGE2 / "ingestion-service/target/ingestion-service-1.0.0.jar",
    "indexing": STAGE2 / "indexing-service/target/indexing-service-1.0.0.jar",
    "search": STAGE2 / "search-service/target/search-service-1.0.0.jar",
    "control": STAGE2 / "control-module/target/control-module-1.0.0.jar",
    "tools": STAGE2 / "benchmark-tools/target/benchmark-tools-1.0.0.jar",
}


@dataclass
class Topology:
    """The deployment under test. Replica counts and thread settings are always explicit."""
    data_dir: Path
    ingestion: int = 1
    indexing: int = 1
    search: int = 1
    control_workers: int = 4
    http_threads: int = 0  # 0 keeps the framework default
    search_pool: int = 16
    search_cache: int = 1000
    synchronous: str = "FULL"
    heap: str = "1g"
    gutenberg_url: str = "https://www.gutenberg.org/cache/epub/{id}/pg{id}.txt"
    extra_env: dict = field(default_factory=dict)

    def port(self, service, replica=0):
        return BASE_PORTS[service] + 10 * replica

    def urls(self, service):
        return [f"http://localhost:{self.port(service, i)}" for i in range(getattr(self, service))]

    @property
    def control_url(self):
        return f"http://localhost:{CONTROL_PORT}"

    def paths(self):
        return {
            "DATALAKE_PATH": str(self.data_dir / "datalake"),
            "DATAMART_PATH": str(self.data_dir / "datamart" / "search.db"),
            "INGESTION_LEDGER_PATH": str(self.data_dir / "state" / "ingestion.db"),
            "CONTROL_DB_PATH": str(self.data_dir / "state" / "control.db"),
        }

    def describe(self):
        return {
            "ingestion_replicas": self.ingestion, "indexing_replicas": self.indexing, "search_replicas": self.search,
            "control_replicas": 1, "control_workers": self.control_workers,
            "http_threads": self.http_threads or "framework default (250 max)",
            "search_db_connections": self.search_pool, "search_cache_entries": self.search_cache,
            "sqlite_synchronous": self.synchronous, "heap_per_jvm": self.heap,
        }


class Deployment:
    """Starts and stops the processes of a Topology, and remembers them in a file."""

    def __init__(self, topology):
        self.topology = topology
        self.processes = {}
        self.logs = topology.data_dir / "logs"

    def start_process(self, name, java_args, port, env):
        self.logs.mkdir(parents=True, exist_ok=True)
        full_env = {**os.environ, **{k: str(v) for k, v in env.items()}, "PORT": str(port)}
        with open(self.logs / f"{name}.log", "ab") as log:
            process = subprocess.Popen(["java", f"-Xmx{self.topology.heap}", *java_args], env=full_env,
                                       stdout=log, stderr=subprocess.STDOUT, cwd=str(self.topology.data_dir))
        self.processes[name] = {"process": process, "port": port}
        return process

    def service_env(self, service):
        t = self.topology
        env = {**t.paths(), "SQLITE_SYNCHRONOUS": t.synchronous, "INGESTION_URLS": ",".join(t.urls("ingestion")),
               "INDEXING_URLS": ",".join(t.urls("indexing")), "SEARCH_URLS": ",".join(t.urls("search")),
               "GUTENBERG_URL_TEMPLATE": t.gutenberg_url, "CONTROL_WORKERS": t.control_workers,
               "SEARCH_CACHE_SIZE": t.search_cache, **t.extra_env}
        if t.http_threads:
            env["HTTP_THREADS"] = t.http_threads
        if service == "search":
            env["SQLITE_POOL_SIZE"] = t.search_pool
        return env

    def start_service(self, service, replica=0, **extra_env):
        name = f"{service}-{replica}"
        self.start_process(name, ["-jar", str(JARS[service])], self.topology.port(service, replica),
                           {**self.service_env(service), **extra_env})
        wait_ready(f"http://localhost:{self.topology.port(service, replica)}/status")
        return name

    def start_control(self, **extra_env):
        self.start_process("control", ["-jar", str(JARS["control"])], CONTROL_PORT,
                           {**self.service_env("control"), **extra_env})
        wait_ready(f"{self.topology.control_url}/status")

    def start_mock(self, corpus_dir, max_book_id=20000, latency_ms=0):
        """Starts the local Gutenberg stand-in and points the Ingestion Service at it."""
        self.start_process("mock-gutenberg", ["-cp", str(JARS["tools"]), "com.calima.search.tools.MockGutenberg"],
                           MOCK_PORT, {"CORPUS_DIR": corpus_dir, "MAX_BOOK_ID": max_book_id, "LATENCY_MS": latency_ms})
        wait_ready(f"http://localhost:{MOCK_PORT}/1", accept_status=(200, 404))
        self.topology.gutenberg_url = f"http://localhost:{MOCK_PORT}/{{id}}"

    def up(self):
        t = self.topology
        # Ingestion first: it owns the readiness information that Indexing depends on.
        for service in ("ingestion", "indexing", "search"):
            for replica in range(getattr(t, service)):
                self.start_service(service, replica)
        self.start_control()
        self.save_state()

    def save_state(self):
        state = {name: {"pid": p["process"].pid, "port": p["port"]} for name, p in self.processes.items()}
        (self.topology.data_dir / "deployment.json").write_text(json.dumps(state, indent=2))

    def kill(self, name):
        """Stops a process abruptly, as a crash would."""
        entry = self.processes.pop(name)
        entry["process"].kill()
        entry["process"].wait()

    def wait_exit(self, name, timeout=30):
        """Waits for a process to stop by itself (a configured crash point) and returns its exit code."""
        code = self.processes[name]["process"].wait(timeout=timeout)
        self.processes.pop(name)
        return code

    def down(self):
        for name in list(self.processes):
            self.kill(name)
        state = self.topology.data_dir / "deployment.json"
        if state.exists():
            state.unlink()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.down()


def wait_ready(url, timeout=60, accept_status=(200,)):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if requests.get(url, timeout=2).status_code in accept_status:
                return
        except requests.RequestException:
            pass
        time.sleep(0.1)
    raise RuntimeError(f"{url} did not become ready within {timeout}s")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("command", choices=["up", "down", "status"])
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--ingestion", type=int, default=1, help="Ingestion Service replicas")
    parser.add_argument("--indexing", type=int, default=1, help="Indexing Service replicas")
    parser.add_argument("--search", type=int, default=1, help="Search Service replicas")
    parser.add_argument("--control-workers", type=int, default=4, help="threads of the Control Module")
    parser.add_argument("--http-threads", type=int, default=0, help="request threads per service; 0 keeps the framework default")
    parser.add_argument("--search-pool", type=int, default=16, help="read connections per Search replica")
    parser.add_argument("--search-cache", type=int, default=1000, help="result cache entries; 0 disables it")
    parser.add_argument("--synchronous", default="FULL", choices=["FULL", "NORMAL"])
    parser.add_argument("--heap", default="1g", help="-Xmx of each JVM")
    parser.add_argument("--gutenberg-url", default=Topology.gutenberg_url, help="download URL template")
    args = parser.parse_args()

    data_dir = args.data_dir.resolve()
    state_file = data_dir / "deployment.json"
    if args.command == "up":
        if state_file.exists():
            sys.exit(f"Already running according to {state_file}; run 'down' first")
        data_dir.mkdir(parents=True, exist_ok=True)
        topology = Topology(data_dir=data_dir, ingestion=args.ingestion, indexing=args.indexing, search=args.search,
                            control_workers=args.control_workers, http_threads=args.http_threads,
                            search_pool=args.search_pool, search_cache=args.search_cache,
                            synchronous=args.synchronous, heap=args.heap, gutenberg_url=args.gutenberg_url)
        deployment = Deployment(topology)
        deployment.up()
        for name, entry in deployment.processes.items():
            print(f"{name:14s} pid {entry['process'].pid:<7d} http://localhost:{entry['port']}")
        print(f"Logs in {deployment.logs}")
    elif args.command == "status":
        if not state_file.exists():
            sys.exit("Not running")
        for name, entry in json.loads(state_file.read_text()).items():
            try:
                status = requests.get(f"http://localhost:{entry['port']}/status", timeout=2).json()
                print(f"{name:14s} pid {entry['pid']:<7d} {status['status']}")
            except requests.RequestException as error:
                print(f"{name:14s} pid {entry['pid']:<7d} UNREACHABLE ({type(error).__name__})")
    else:
        if not state_file.exists():
            sys.exit("Not running")
        for name, entry in json.loads(state_file.read_text()).items():
            if os.name == "nt":
                subprocess.run(["taskkill", "/F", "/PID", str(entry["pid"])], capture_output=True)
            else:
                subprocess.run(["kill", "-9", str(entry["pid"])], capture_output=True)
            print(f"stopped {name}")
        state_file.unlink()


if __name__ == "__main__":
    main()
