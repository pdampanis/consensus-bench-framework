import { useEffect, useMemo, useState } from "react";
import { api, Aggregate, BenchmarkRun } from "../api";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

/**
 * Picks a (system, scenario) and shows:
 *   - aggregated statistics table
 *   - per-run throughput bar chart
 *   - per-run p99 write latency line chart
 * This is the "drill into one configuration" view.
 */
export default function ResultsView() {
  const [systems, setSystems] = useState<string[]>([]);
  const [scenarios, setScenarios] = useState<string[]>([]);
  const [system, setSystem] = useState<string>("");
  const [scenario, setScenario] = useState<string>("");
  const [agg, setAgg] = useState<Aggregate | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.listSystems().then(setSystems).catch((e) => setError(String(e)));
  }, []);

  useEffect(() => {
    if (!system) return;
    api.listScenarios(system).then((list) => {
      setScenarios(list);
      setScenario(list[0] ?? "");
    });
  }, [system]);

  useEffect(() => {
    if (!system || !scenario) return;
    api.aggregate(system, scenario).then(setAgg).catch((e) => setError(String(e)));
  }, [system, scenario]);

  const tputRows = useMemo(
    () =>
      (agg?.rawRuns ?? []).map((r) => ({
        runId: r.runId,
        throughput: r.throughputOps ?? 0,
      })),
    [agg]
  );

  const latencyRows = useMemo(
    () =>
      (agg?.rawRuns ?? []).map((r) => ({
        runId: r.runId,
        p95: (r.writeP95Us ?? 0) / 1000,
        p99: (r.writeP99Us ?? 0) / 1000,
        p999: (r.writeP999Us ?? 0) / 1000,
      })),
    [agg]
  );

  return (
    <>
      {error && (
        <div className="panel" style={{ color: "var(--err)" }}>
          {error}
        </div>
      )}

      <div className="panel">
        <div style={{ display: "flex", gap: 16, alignItems: "center" }}>
          <label>
            System:&nbsp;
            <select value={system} onChange={(e) => setSystem(e.target.value)}>
              <option value="">— pick —</option>
              {systems.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
          <label>
            Scenario:&nbsp;
            <select value={scenario} onChange={(e) => setScenario(e.target.value)}>
              {scenarios.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
          {agg && (
            <span style={{ color: "var(--muted)" }}>
              {agg.nRuns} run{agg.nRuns === 1 ? "" : "s"}
            </span>
          )}
        </div>
      </div>

      {agg && agg.nRuns > 0 && (
        <>
          <div className="grid-2">
            <div className="panel">
              <h2>Throughput (ops/s)</h2>
              <Stats s={agg.throughputOps} unit="ops/s" />
            </div>
            <div className="panel">
              <h2>p99 write latency (μs)</h2>
              <Stats s={agg.writeP99Us} unit="μs" />
            </div>
          </div>

          <div className="panel">
            <h2>Throughput per run</h2>
            <ResponsiveContainer width="100%" height={240}>
              <BarChart data={tputRows}>
                <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
                <XAxis dataKey="runId" stroke="#94a3b8" />
                <YAxis stroke="#94a3b8" />
                <Tooltip
                  contentStyle={{ background: "#1e293b", border: "1px solid #334155" }}
                />
                <Bar dataKey="throughput" fill="var(--accent)" />
              </BarChart>
            </ResponsiveContainer>
          </div>

          <div className="panel">
            <h2>Latency percentiles per run (ms)</h2>
            <ResponsiveContainer width="100%" height={240}>
              <LineChart data={latencyRows}>
                <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
                <XAxis dataKey="runId" stroke="#94a3b8" />
                <YAxis stroke="#94a3b8" />
                <Tooltip
                  contentStyle={{ background: "#1e293b", border: "1px solid #334155" }}
                />
                <Legend />
                <Line type="monotone" dataKey="p95" stroke="#60a5fa" dot={false} />
                <Line type="monotone" dataKey="p99" stroke="#facc15" dot={false} />
                <Line type="monotone" dataKey="p999" stroke="#ef4444" dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>

          <div className="panel">
            <h2>Raw runs</h2>
            <RunsTable runs={agg.rawRuns} />
          </div>
        </>
      )}
    </>
  );
}

function Stats({ s, unit }: { s?: any; unit: string }) {
  if (!s || s.n === undefined) return <p>No data</p>;
  const f = (x: number) => (x >= 1000 ? x.toFixed(0) : x.toFixed(1));
  return (
    <div>
      <div className="stat">
        <span className="label">n runs</span>
        <span className="value">{s.n}</span>
      </div>
      <div className="stat">
        <span className="label">mean</span>
        <span className="value">{f(s.mean)} {unit}</span>
      </div>
      <div className="stat">
        <span className="label">median</span>
        <span className="value">{f(s.median)} {unit}</span>
      </div>
      <div className="stat">
        <span className="label">std</span>
        <span className="value">{f(s.std)}</span>
      </div>
      <div className="stat">
        <span className="label">95% CI (bootstrap)</span>
        <span className="value">[{f(s.ci95Low)}, {f(s.ci95High)}]</span>
      </div>
      <div className="stat">
        <span className="label">min / max</span>
        <span className="value">{f(s.min)} / {f(s.max)}</span>
      </div>
    </div>
  );
}

function RunsTable({ runs }: { runs: BenchmarkRun[] }) {
  return (
    <div style={{ overflowX: "auto" }}>
      <table style={{ width: "100%", fontSize: 13, borderCollapse: "collapse" }}>
        <thead>
          <tr style={{ color: "var(--muted)" }}>
            <th style={{ textAlign: "left", padding: 4 }}>run</th>
            <th style={{ textAlign: "right", padding: 4 }}>throughput</th>
            <th style={{ textAlign: "right", padding: 4 }}>avg (μs)</th>
            <th style={{ textAlign: "right", padding: 4 }}>p99 (μs)</th>
            <th style={{ textAlign: "right", padding: 4 }}>p99.9 (μs)</th>
            <th style={{ textAlign: "center", padding: 4 }}>linearizable</th>
          </tr>
        </thead>
        <tbody>
          {runs.map((r) => (
            <tr key={r.id} style={{ borderTop: "1px solid #334155" }}>
              <td style={{ padding: 4 }}>{r.runId}</td>
              <td style={{ padding: 4, textAlign: "right" }}>
                {r.throughputOps?.toFixed(0) ?? "—"}
              </td>
              <td style={{ padding: 4, textAlign: "right" }}>
                {r.writeAvgUs?.toFixed(0) ?? "—"}
              </td>
              <td style={{ padding: 4, textAlign: "right" }}>
                {r.writeP99Us?.toFixed(0) ?? "—"}
              </td>
              <td style={{ padding: 4, textAlign: "right" }}>
                {r.writeP999Us?.toFixed(0) ?? "—"}
              </td>
              <td style={{ padding: 4, textAlign: "center" }}>
                {r.consistencyResult === "PASS" && <span style={{ color: "var(--ok)" }}>PASS</span>}
                {r.consistencyResult === "FAIL" && <span style={{ color: "var(--err)" }}>FAIL</span>}
                {(!r.consistencyResult || r.consistencyResult === "NOT_CHECKED") && (
                  <span style={{ color: "var(--muted)" }}>—</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
