# Consensus Benchmark Visualizer

Standalone web application for exploring and comparing consensus protocol benchmark results. Three views: **Results** (per-system drill-down with aggregated stats and time-series), **Compare** (cross-system side-by-side for any scenario), and **Algorithms** (animated network topology with message-passing visualizations for each protocol).

## Quick start

```bash
RESULTS_DIR=/absolute/path/to/results docker compose up -d
# Open http://localhost:8090
```

The `RESULTS_DIR` must contain the benchmark output tree:
```
results/
  <system>/<scenario>/<run_id>/
    manifest.json
    throughput.csv
    latency.csv
    correctness.txt
```

If you don't have results yet, create a minimal stub to see the UI:
```bash
mkdir -p /tmp/demo-results/kraft/baseline/run001
echo '{"system":"kraft","scenario":"baseline","run_id":"run001","cluster_size":3,"status":"complete"}' > /tmp/demo-results/kraft/baseline/run001/manifest.json
echo '180,10000\n185,10200' > /tmp/demo-results/kraft/baseline/run001/throughput.csv
echo 'metric,value_us\navg,5000\np50,4800\np95,12000\np99,15000\np99_9,20000\nmax,30000' > /tmp/demo-results/kraft/baseline/run001/latency.csv
RESULTS_DIR=/tmp/demo-results docker compose up -d
```

## Architecture

| Service | Tech | Role |
|---|---|---|
| postgres | PostgreSQL 16 | Persistent store for imported runs |
| backend | Spring Boot 3 / Java 21 | Reads results tree at startup, serves REST API |
| frontend | React 18 + TypeScript + Vite | SPA served by nginx, proxies /api to backend |

The backend imports every `manifest.json` on startup (idempotent — existing rows are updated). The frontend is compiled at build time into static assets served by nginx.

## API endpoints

| Endpoint | Returns |
|---|---|
| `GET /api/systems` | List of systems with results |
| `GET /api/systems/{s}/scenarios` | Scenarios for a system |
| `GET /api/systems/{s}/scenarios/{sc}/runs` | All runs for (system, scenario) |
| `GET /api/systems/{s}/scenarios/{sc}/aggregate` | Aggregated stats (mean, median, CI, std) |
| `GET /api/compare/{scenario}` | Cross-system comparison for one scenario |
| `GET /api/algorithms` | List of algorithm animation specs |
| `GET /api/algorithms/{name}` | Animation spec with nodes, edges, scenarios |

## Development (without Docker)

Backend:
```bash
cd backend
APP_RESULTS_DIR=/path/to/results mvn spring-boot:run
# Listens on :8080, uses H2 in-memory DB by default
```

Frontend:
```bash
cd frontend
npm install
npm run dev
# Listens on :5173, proxies /api to localhost:8080
```

## Adding a new algorithm animation

Drop a JSON file into `backend/src/main/resources/algorithms/` following the schema in `frontend/src/api.ts` (`AlgorithmSpec` interface). The file defines nodes (label, role, SVG coordinates), edges, and scripted animation steps. No frontend code changes needed.

## License

Part of the thesis "Evolution and Comparison of Consensus Protocols in Distributed Systems." All code is academic-use.
